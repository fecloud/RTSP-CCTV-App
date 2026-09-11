import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin)
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

android {
    namespace = "com.zektopic.cctvapp"
    compileSdk {
        version = release(36)
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
            // Kept off deliberately: RootEncoder resolves classes reflectively, and a
            // mis-shrunk release only fails at runtime. Enabling R8 needs a full
            // on-device pass first -- see README roadmap.
            isMinifyEnabled = false
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

kotlin {
    // Must match compileOptions above -- the Kotlin plugin defaults its JVM target higher
    // than that and Gradle refuses to mix targets between the Java and Kotlin compile tasks.
    // Deliberately NOT jvmToolchain(11): that forces Gradle's toolchain auto-provisioning,
    // which on this machine picks a JBR-DCEVM install with no `jlink`, breaking javac's
    // JDK-image transform. Setting the compiler's target directly avoids touching the
    // toolchain/JDK selection at all.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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
    implementation(libs.bugly.crashreport)
}
