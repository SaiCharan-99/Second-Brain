plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Step 3. MUST NOT depend on :vault, :voice or :integrations — enforced by
// :verifyModuleGraph in the root build, not by convention.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
