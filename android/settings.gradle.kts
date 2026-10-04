pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google") {
            name = "GoogleMavenFallback"
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Some networks return 404 for every dl.google.com Maven request.
        // Keep the official repository first and use this only for Google artifacts.
        maven("https://maven.aliyun.com/repository/google") {
            name = "GoogleMavenFallback"
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        // BRIDGEY SMB POC (feasibility spike, see SmbPocServer.kt) - the only maintained Android-
        // friendly build of org.filesys/jfileserver is buttercookie42's fork, published via JitPack
        // rather than Maven Central. DELETE this block if the SMB POC is removed.
        maven("https://jitpack.io") {
            name = "JitPackSmbPoc"
            content { includeGroup("com.github.buttercookie42") }
        }
    }
}

rootProject.name = "Bridgey"
include(":app", ":core:discovery")
project(":core").projectDir = file("../platform/android")
project(":core:discovery").projectDir = file("../platform/android/discovery")
