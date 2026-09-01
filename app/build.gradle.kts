import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

// Composition root. The only module that knows concrete implementations exist.
//
// Step 4 (D-058): the plain `application` plugin is gone. The Compose Desktop
// plugin's own `compose.desktop.application {}` block below supplies `mainClass`
// and, with it, an equivalent `:app:run` task — SETUP_GUIDE.md's
// `./gradlew.bat :app:run` keeps working unchanged — plus native packaging
// (`packageMsi` etc.), which is the right distribution shape for a desktop GUI
// app and makes the old `application` plugin's `distTar`/`distZip`/`startScripts`
// tasks pure dead weight now that there is a real window instead of a stub
// println. Running both plugins at once is not just redundant: they collide,
// since each registers its own task literally named `run`.
//
// `capture` below does not depend on `application` at all — `sourceSets` comes
// from the Kotlin plugin — so the Step 3 harness is unaffected.
dependencies {
    implementation(project(":model"))
    implementation(project(":ports"))
    implementation(project(":vault"))
    implementation(project(":voice"))
    implementation(project(":agent"))
    implementation(project(":integrations"))
    implementation(libs.kotlinx.coroutines.core)
    // Compose Desktop's own event loop runs on AWT/Swing; this is what makes
    // Dispatchers.Main resolve to it instead of throwing at startup.
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.slf4j.api)
    // The composition root builds the HttpClients GeminiStt/KokoroTts run on
    // (:voice's HttpClients.create) and owns closing them, so it needs the
    // io.ktor.client.HttpClient type itself - :voice declares Ktor as
    // `implementation`, which does not leak it to :app's compile classpath.
    implementation(libs.bundles.ktor.client)
    runtimeOnly(libs.logback.classic)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // No icon-font dependency. compose.materialIconsExtended is pinned to a
    // 1.7.3 snapshot that will not receive updates (a real warning, not a
    // guess - it printed on the first resolve). The design board's own
    // language is "system type, hairline rules, no brand color beyond one
    // system blue" - a handful of hand-drawn glyphs (a chevron, a dot) cost
    // nothing and owe no version anyone has to track.
}

// :app is Compose UI, exercised manually per CLAUDE.md's testing table ("`:app`
// | Manual. Compose UI tests are not worth the time on this timeline"). The few
// pure functions this module does unit-test (NoteMarkdown, TreeFlatten,
// CostConfirmation, ConversationDigest) need nothing beyond the plain
// JUnit 5 + kotlinx-coroutines-test every subproject already gets from the
// root build script - no compose.desktop.uiTestJUnit4.

compose.desktop {
    application {
        mainClass = "com.secondbrain.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "SecondBrain"
            packageVersion = "1.0.0"
        }
    }
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

// D-082/D-087: dumps the real Zepto MCP tools/list once a sign-in exists, so
// McpCommerceAdapter's role bindings and response parsers can be checked
// against what the server actually returns instead of the doc-derived guess
// D-079 shipped with. See ZeptoDiscovery.kt.
tasks.register<JavaExec>("zeptoDiscover") {
    group = "verification"
    description = "Dumps Zepto's real tools/list (names, descriptions, schemas). Requires a completed sign-in."
    mainClass.set("com.secondbrain.app.ZeptoDiscoveryKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8")
}
