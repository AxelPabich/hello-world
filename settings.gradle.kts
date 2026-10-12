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

rootProject.name = "Buzzer"

include(":app")

// Spiellogik als reines Kotlin-Modul (ohne Android), eigenständig testbar: `cd core && gradle test`
includeBuild("core")
