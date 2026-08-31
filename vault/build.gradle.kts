plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // FTS5 verified present in this version, along with UPDATE/DELETE on a plain
    // (non-contentless) fts5 table and snippet() returning real text. See D-026.
    implementation(libs.sqlite.jdbc)

    // Slug transliteration. java.text.Normalizer produces an EMPTY string for
    // Telugu, Devanagari and every other non-Latin script, so this is
    // load-bearing rather than convenience. Verified before acceptance. See D-029.
    implementation(libs.icu4j)

    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.logback.classic)
}
