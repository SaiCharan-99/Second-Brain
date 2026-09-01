plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Steps 5-7.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Google OAuth + Gmail + Calendar (Steps 5-6). Official client library
    // rather than a hand-rolled REST caller - see the versions.toml comment
    // for why, mirroring D-044's reasoning for the Anthropic SDK.
    implementation(libs.google.api.client)
    implementation(libs.google.oauth.client.jetty)
    implementation(libs.google.api.services.gmail)
    implementation(libs.google.api.services.calendar)

    // TokenStore's own tiny SQLite file (GoogleConfig.tokenStorePath / D-068+).
    implementation(libs.sqlite.jdbc)

    testRuntimeOnly(libs.logback.classic)
}
