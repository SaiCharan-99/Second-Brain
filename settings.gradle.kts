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
        // Compose Multiplatform's desktop artifacts (:app, Step 4) pull in a
        // handful of transitive androidx.* modules - annotation, collection,
        // lifecycle, savedstate - that are published only to Google's Maven
        // repository, never to Maven Central. Measured: :app:compileKotlin
        // fails to resolve androidx.collection:collection etc. without this,
        // even though nothing in this repo targets Android (D-058).
        google()
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
