pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Public SDK binary repository. Required by the independent sample APK.
        maven("https://raw.githubusercontent.com/mentoster/flip7-angle-lab/maven")
    }
}

rootProject.name = "Flip7AngleLab"
include(":app")
include(":hinge-angle-sdk")
include(":sample-app")
