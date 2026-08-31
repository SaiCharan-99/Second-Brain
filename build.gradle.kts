import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared configuration for every module.
// ─────────────────────────────────────────────────────────────────────────────
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(false)
                freeCompilerArgs.addAll("-Xjsr305=strict")
            }
        }

        dependencies {
            add("testImplementation", rootProject.libs.junit.jupiter)
            add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
            add("testImplementation", rootProject.libs.kotlinx.coroutines.test)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// verifyModuleGraph — ARCHITECTURE.md §1 as an executable rule.
//
// Gradle does not enforce "must NOT depend on". The single most load-bearing
// constraint in this architecture is that :agent cannot see :vault, :voice or
// :integrations — it talks to ports only. A rule that important needs a
// machine, not a code review, so this task fails the build on any edge outside
// the table in §1 / CLAUDE.md's module map.
// ─────────────────────────────────────────────────────────────────────────────
val allowedEdges: Map<String, Set<String>> = mapOf(
    ":model"        to emptySet(),
    ":ports"        to setOf(":model"),
    ":vault"        to setOf(":model", ":ports"),
    ":voice"        to setOf(":model", ":ports"),
    ":agent"        to setOf(":model", ":ports"),
    ":integrations" to setOf(":model", ":ports"),
    ":app"          to setOf(":model", ":ports", ":vault", ":voice", ":agent", ":integrations"),
)

// Captured at configuration time so the check itself is configuration-cache safe.
val observedGraph: Property<String> = objects.property(String::class.java)

gradle.projectsEvaluated {
    val lines = subprojects.sortedBy { it.path }.map { sp ->
        val deps = sp.configurations
            .flatMap { cfg -> cfg.dependencies.withType(ProjectDependency::class.java) }
            .map { it.path }
            .distinct()
            .sorted()
        "${sp.path}=${deps.joinToString(",")}"
    }
    observedGraph.set(lines.joinToString("\n"))
}

val verifyModuleGraph = tasks.register("verifyModuleGraph") {
    group = "verification"
    description = "Fails if any module declares a dependency edge outside ARCHITECTURE.md §1."

    val expected = allowedEdges
    val observed = observedGraph
    inputs.property("observedGraph", observed)

    doLast {
        val violations = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        observed.get().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val (module, depsRaw) = line.split("=", limit = 2)
            val deps = depsRaw.split(",").filter { it.isNotBlank() }
            val allowed = expected[module]
            if (allowed == null) {
                unknown += module
                return@forEach
            }
            deps.filterNot { it in allowed }.forEach { bad ->
                violations += "  $module  ->  $bad   (allowed: ${allowed.ifEmpty { setOf("<none>") }.joinToString(", ")})"
            }
        }

        if (unknown.isNotEmpty()) {
            violations += unknown.map { "  $it is not listed in ARCHITECTURE.md section 1 - add it to allowedEdges or remove the module" }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module dependency graph violates ARCHITECTURE.md section 1:")
                    appendLine()
                    violations.forEach { appendLine(it) }
                    appendLine()
                    appendLine("If you believe the edge is correct, you have found a missing PORT, not a")
                    appendLine("missing dependency. See CLAUDE.md 'Module map'. Changing this table requires")
                    appendLine("a DECISIONS.md entry.")
                }
            )
        }
        logger.lifecycle("verifyModuleGraph: OK - ${expected.size} modules, all edges within ARCHITECTURE.md section 1.")
    }
}

// Every `check` run verifies the architecture.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.named("check").configure { dependsOn(verifyModuleGraph) }
    }
}

tasks.register("printModuleGraph") {
    group = "help"
    description = "Prints the observed inter-module dependency graph."
    val observed = observedGraph
    doLast { println(observed.get()) }
}
