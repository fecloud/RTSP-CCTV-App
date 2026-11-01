import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Release signing credentials, resolved in priority order:
 *   1. `keystore.properties` in the repo root (git-ignored) -- for local release builds
 *   2. `RELEASE_KEYSTORE_*` environment variables -- for CI
 *   3. nothing -- the release build is then left UNSIGNED
 *
 * The keystore itself is never committed. It lives outside the repository and is
 * injected into CI from the `RELEASE_KEYSTORE_BASE64` secret. Forks and pull-request
 * builds have no credentials and fall through to (3) rather than failing.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey))?.takeIf { it.isNotEmpty() }

val releaseStorePath = signingValue("storeFile", "RELEASE_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")

val hasReleaseSigning = releaseStorePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStorePath).exists()

/**
 * Short commit hash of the checkout being built, surfaced in the dashboard footer so a
 * running server's build can be identified without cross-referencing versionCode against
 * CI history. Falls back to "unknown" rather than failing the build -- a source archive
 * (no `.git`) or a `git` binary missing from PATH shouldn't block compiling.
 */
val gitCommitHash: String = runCatching {
    providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", "rev-parse", "--short=8", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown").ifEmpty { "unknown" }

/**
 * Wall-clock time this build was configured, in the build machine's local timezone --
 * paired with [gitCommitHash] in the dashboard footer so two APKs built from the exact
 * same commit (e.g. a rebuild after only changing a signing config) can still be told
 * apart.
 */
val buildTimestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

/** Filename-safe counterpart of [buildTimestamp], used to rename the release APK below. */
val apkTimestamp: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

android {
    namespace = "com.zektopic.cctvapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zektopic.cctvapp"
        minSdk = 23
        targetSdk = 36
        // CI overrides these (-PversionCode from the run number, -PversionName from the
        // tag). Android refuses to install a build whose versionCode has not increased,
        // so a pinned versionCode made every published release un-updatable.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.1.0"

        buildConfigField("String", "GIT_COMMIT", "\"$gitCommitHash\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Sign debug builds with the same release key when it's available locally,
            // so a debug APK can be installed over (or alongside upgrades from) a
            // release APK without Android's INSTALL_FAILED_UPDATE_INCOMPATIBLE --
            // otherwise debug and release fall back to two different signers (the
            // auto-generated ~/.android/debug.keystore vs. the real release key) and
            // switching between them on the same device means uninstalling first.
            // Falls through to AGP's default debug signing when no keystore.properties
            // exists (e.g. in CI, or for a contributor without release credentials).
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release signing credentials found -- the release APK will be UNSIGNED. " +
                        "Set them in keystore.properties or RELEASE_KEYSTORE_* environment variables."
                )
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Workaround for third-party JNI libs that are not yet 16KB page aligned.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Renames the release APK from the default app-release.apk to something that identifies
// the exact build (commit + local build time) without cross-referencing CI history.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("cctv-git-$gitCommitHash-$apkTimestamp.apk")
        }
    }
}

/**
 * Patches one class out of the `io.getstream:stream-webrtc-android` AAR: upstream
 * `org.webrtc.audio.WebRtcAudioRecord` insists on opening its own `android.media.AudioRecord`,
 * which this app's target hardware's `AudioPolicyManager` refuses a second concurrent session for
 * while `RootEncoder`'s `MicrophoneSource` already holds one (see
 * `com.zektopic.cctvapp.webrtc.WebRtcMicFeed`'s kdoc). `webrtc-audio-patch/org/webrtc/audio/`
 * holds a rewritten version of that one file (plus a new, app-agnostic `ExternalAudioSource`
 * interface) that pulls PCM from `WebRtcMicFeed` instead -- every `@CalledByNative` method keeps
 * its original signature, so the native `.so` bundled in the very same AAR binds to the
 * replacement exactly as it would the original. See that file's header comment for the full
 * story, and the plan this was implemented from for the end-to-end design.
 *
 * This is deliberately NOT the `includeBuild`/`dependencySubstitution` mechanism documented in
 * CLAUDE.md for `third_party/RootEncoder`/`third_party/RTSP-Server` -- that one replaces an entire
 * Jitpack-sourced library with a full source build; this is a single-class binary patch of one
 * Maven Central AAR, a different enough problem shape that reusing that mechanism isn't a fit.
 *
 * Gradle has no per-class `exclude()` for a dependency's jar contents, so the only way to drop
 * just the original `WebRtcAudioRecord.class` is to rebuild the AAR itself with it swapped out,
 * and depend on that local copy instead of the Maven coordinate.
 */
val webrtcAudioPatchSrcDir = rootProject.layout.projectDirectory.dir("webrtc-audio-patch")
val webrtcDep = libs.stream.webrtc.android.get()
val webrtcOriginalConfig = configurations.detachedConfiguration(
    dependencies.create("${webrtcDep.group}:${webrtcDep.name}:${webrtcDep.version}")
)
val webrtcOriginalArtifacts = webrtcOriginalConfig.resolvedConfiguration.resolvedArtifacts
val webrtcAarFile: File = webrtcOriginalArtifacts.first {
    it.moduleVersion.id.group == webrtcDep.group && it.moduleVersion.id.name == webrtcDep.name
}.file
// Everything stream-webrtc-android itself depends on (e.g. androidx.annotation) -- resolved by
// Gradle from its real POM/module metadata rather than hand-guessed, so a future version bump
// that changes its dependencies doesn't silently go stale here. Declared below as normal Maven
// coordinates (not raw `files()`) so they still go through Gradle's version conflict resolution
// against the rest of the app's dependency graph -- `files()` would bypass that and risk a
// duplicate-class build failure if some other dependency pulls a different version of the same
// library (confirmed: this is exactly what happened with kotlin-stdlib/org.jetbrains:annotations
// before this comment was written).
val webrtcTransitiveArtifacts = webrtcOriginalArtifacts.filterNot {
    it.moduleVersion.id.group == webrtcDep.group && it.moduleVersion.id.name == webrtcDep.name
}
val webrtcTransitiveCoordinates: List<String> = webrtcTransitiveArtifacts.map {
    "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}:${it.moduleVersion.id.version}"
}
// Only used as compile-time classpath for compileWebRtcAudioPatch below -- javac doesn't care
// about version conflicts, so raw files are fine there (unlike the app's own implementation
// dependencies, see webrtcTransitiveCoordinates's comment).
val webrtcTransitiveJars: List<File> = webrtcTransitiveArtifacts.map { it.file }

val extractOriginalWebRtcClasses = tasks.register<Copy>("extractOriginalWebRtcClasses") {
    from(zipTree(webrtcAarFile)) { include("classes.jar") }
    into(layout.buildDirectory.dir("webrtcAudioPatch/original"))
}

val compileWebRtcAudioPatch = tasks.register<JavaCompile>("compileWebRtcAudioPatch") {
    dependsOn(extractOriginalWebRtcClasses)
    source = fileTree(webrtcAudioPatchSrcDir)
    classpath = files(androidComponents.sdkComponents.bootClasspath) +
        files(layout.buildDirectory.file("webrtcAudioPatch/original/classes.jar")) +
        files(webrtcTransitiveJars)
    destinationDirectory.set(layout.buildDirectory.dir("webrtcAudioPatch/classes"))
    sourceCompatibility = JavaVersion.VERSION_11.toString()
    targetCompatibility = JavaVersion.VERSION_11.toString()
}

val patchedWebRtcAarFile = layout.buildDirectory.file("webrtcAudioPatch/stream-webrtc-android-patched.aar")

val patchWebRtcAar = tasks.register("patchWebRtcAar") {
    dependsOn(compileWebRtcAudioPatch)
    inputs.file(webrtcAarFile)
    inputs.dir(compileWebRtcAudioPatch.flatMap { it.destinationDirectory })
    outputs.file(patchedWebRtcAarFile)
    doLast {
        val originalClassesJar = layout.buildDirectory.file("webrtcAudioPatch/original/classes.jar").get().asFile
        val patchedClassesDir = compileWebRtcAudioPatch.get().destinationDirectory.get().asFile
        val outputAar = patchedWebRtcAarFile.get().asFile
        val tempDir = outputAar.parentFile.resolve("tmp")
        tempDir.mkdirs()
        val patchedClassesJar = File(tempDir, "classes-patched.jar")

        // Drop WebRtcAudioRecord.class and every inner/anonymous class nested under it (e.g.
        // its AudioRecordThread, and whatever numbered anonymous classes the compiler emitted --
        // the exact set/names of those don't need to match between the original and our rewrite,
        // only that none of the original's are left behind to collide with ours).
        val classPrefixToDrop = "org/webrtc/audio/WebRtcAudioRecord"

        ZipFile(originalClassesJar).use { originalZip ->
            ZipOutputStream(patchedClassesJar.outputStream()).use { out ->
                originalZip.entries().asSequence()
                    .filterNot { it.name.startsWith(classPrefixToDrop) }
                    .forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) originalZip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                patchedClassesDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(patchedClassesDir).path.replace(File.separatorChar, '/')
                        out.putNextEntry(ZipEntry(relativePath))
                        classFile.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
        }

        outputAar.parentFile.mkdirs()
        ZipFile(webrtcAarFile).use { originalZip ->
            ZipOutputStream(outputAar.outputStream()).use { out ->
                originalZip.entries().asSequence()
                    .filterNot { it.name == "classes.jar" }
                    .forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) originalZip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                out.putNextEntry(ZipEntry("classes.jar"))
                patchedClassesJar.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        tempDir.deleteRecursively()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // NOTE: these two must stay in lockstep. RTSP-Server pins a specific RootEncoder
    // version transitively, and a mismatch resolves to whatever is newer.
    // Never use `master-SNAPSHOT` here: it is a moving target that silently changed the
    // resolved RootEncoder to 2.8.0 (which demands compileSdk 37) and broke the build
    // on a commit whose CI had previously passed.
    implementation(libs.rootencoder.library)
    implementation(libs.rtsp.server)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.bugly.crashreport)
    // Patched locally -- see the webrtcAudioPatchSrcDir/patchWebRtcAar block above for why this
    // isn't `implementation(libs.stream.webrtc.android)` directly.
    implementation(files(patchedWebRtcAarFile).builtBy(patchWebRtcAar))
    webrtcTransitiveCoordinates.forEach { implementation(it) }
}
