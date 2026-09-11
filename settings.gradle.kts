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
    }
}

rootProject.name = "agentic-scheduler"

include(":shared:domain")
include(":shared:application")
include(":shared:database")
include(":apps:android")
include(":apps:desktop")
include(":apps:wear")
