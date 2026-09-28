pluginManagement {
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2") {
            name = "GoogleRedirector"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2") {
            name = "GoogleRedirector"
        }
        mavenCentral()
    }
}

rootProject.name = "LedController"
include(":app")
