rootProject.name = "second-brain"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// All seven modules from ARCHITECTURE.md §1. Declared up front even where the
// source tree is still empty, so the dependency graph can be verified from
// Step 1 onward rather than discovered at Step 5.
include(
    ":model",
    ":ports",
    ":vault",
    ":voice",
    ":agent",
    ":integrations",
    ":app",
)
