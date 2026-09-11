import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RTSP CCTV APP"
include(":app")

// Local per-developer switch: set `useSourceDeps=true` in (git-ignored) local.properties
// to build RootEncoder/RTSP-Server from the `third_party/` git submodules instead of the
// Jitpack AARs. Off by default -- most contributors never need the submodules checked out.
val localProperties = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val useSourceDeps = localProperties.getProperty("useSourceDeps")?.toBoolean() ?: false

if (useSourceDeps) {
    val rootEncoderDir = rootDir.resolve("third_party/RootEncoder")
    val rtspServerDir = rootDir.resolve("third_party/RTSP-Server")
    check(rootEncoderDir.resolve("settings.gradle.kts").exists() && rtspServerDir.resolve("settings.gradle.kts").exists()) {
        "useSourceDeps=true in local.properties requires the third_party submodules to be " +
            "checked out. Run: git submodule update --init --recursive"
    }

    includeBuild(rootEncoderDir) {
        dependencySubstitution {
            substitute(module("com.github.pedroSG94.RootEncoder:library")).using(project(":library"))
        }
    }
    includeBuild(rtspServerDir) {
        dependencySubstitution {
            substitute(module("com.github.pedroSG94:RTSP-Server")).using(project(":rtspserver"))
        }
    }
}
