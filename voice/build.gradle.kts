plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))

    implementation(libs.kotlinx.coroutines.core)
    // Swing dispatcher: the Step 1 push-to-talk harness needs real key-down /
    // key-up events, which a console app cannot observe. Swing ships with the
    // JDK, so this costs no dependency and keeps Compose in Step 4. See E5.
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.client)
    implementation(libs.slf4j.api)

    runtimeOnly(libs.logback.classic)
}

// Step 1 exit criterion: `./gradlew :voice:run`.
// This is a test harness, not the product entry point — :app owns main() per
// ARCHITECTURE.md §1. Named `harness` so it never reads as production.
application {
    mainClass.set("com.secondbrain.voice.harness.VoiceHarnessKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Non-interactive verification of the Step 1 voice loop, minus the two paths
// that need credentials. See harness/SmokeCheck.kt.
tasks.register<JavaExec>("smokeCheck") {
    group = "verification"
    description = "Runs the Step 1 audio smoke check against real hardware (no API keys needed)."
    mainClass.set("com.secondbrain.voice.harness.SmokeCheckKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8")
}
