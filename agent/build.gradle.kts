plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// MUST NOT depend on :vault, :voice or :integrations - enforced by
// :verifyModuleGraph in the root build, not by convention.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Official Anthropic SDK. Wrapped behind LlmPort so nothing above :agent ever
    // sees Jackson, Optional<T> or an SDK builder - which is also what makes the
    // "Fake LlmPort" testing bar in CLAUDE.md reachable. See D-044.
    implementation(libs.anthropic)

    // ConversationStore and CostMeter persist to app.db. :agent owns its own
    // tables and its own migration lineage there; see D-045.
    implementation(libs.sqlite.jdbc)

    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.logback.classic)
}
