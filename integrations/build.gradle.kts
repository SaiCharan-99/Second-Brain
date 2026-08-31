plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Steps 5-7.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
