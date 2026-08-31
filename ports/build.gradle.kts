plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Interfaces only. Depends on :model and nothing else.
dependencies {
    api(project(":model"))
    api(libs.kotlinx.coroutines.core)
}
