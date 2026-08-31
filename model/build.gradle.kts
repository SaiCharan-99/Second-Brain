plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// CLAUDE.md module map: ":model - Data classes only. kotlinx-serialization only".
// ConfigLoader lives here rather than in a new :config module, and reads TOML
// with a ~60-line hand-rolled scalar reader instead of a third-party parser, so
// the "kotlinx-serialization only" rule holds literally. See DECISIONS.md D-012.
dependencies {
    api(libs.kotlinx.serialization.json)
}
