plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Steps 5-7.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Step 7: the Zepto MCP client. MCP is JSON-RPC 2.0 over streamable HTTP,
    // so this is Ktor + kotlinx-serialization rather than a further SDK -
    // ARCHITECTURE section 7 Step 7's own call ("a minimal client is roughly
    // 300 lines and removes a dependency risk on a deadline"), and unlike the
    // Anthropic case in D-044 there is no typed SDK drift to protect against.
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)

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
