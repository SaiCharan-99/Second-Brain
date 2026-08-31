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
