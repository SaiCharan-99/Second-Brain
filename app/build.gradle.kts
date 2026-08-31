plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Composition root. The only module that knows concrete implementations exist.
// The Compose Multiplatform plugin is deliberately NOT applied yet — the UI is
// Step 4, and pulling the Compose dependency tree in to print one line of text
// would be dead weight.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(project(":vault"))
    implementation(project(":voice"))
    implementation(project(":agent"))
    implementation(project(":integrations"))
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.secondbrain.app.MainKt")
}

// Step 3's quality gate: 20 typed thoughts against the real vault and the real
// model, reporting folder sprawl and per-capture cost. See CaptureHarness.kt.
tasks.register<JavaExec>("capture") {
    group = "verification"
    description = "Runs the Step 3 capture harness over a file of thoughts (or stdin)."
    mainClass.set("com.secondbrain.app.CaptureHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8")
}
