plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Step 2. Declared now so verifyModuleGraph covers it from Step 1 onward.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
