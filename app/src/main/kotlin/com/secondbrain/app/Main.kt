package com.secondbrain.app

/**
 * The composition root and the product's real entry point (ARCHITECTURE.md
 * section 1: ":app owns Compose UI, ProposalWindow, composition root, main()").
 *
 * Deliberately a stub at Step 1. The Compose Multiplatform plugin is not applied
 * to this module yet either -- the UI is Step 4, specified against the design
 * board in artifacts/, and pulling the Compose dependency tree in now to print
 * one line of text would be dead weight.
 *
 * Until then the voice loop is exercised through its own harness:
 *
 *     ./gradlew :voice:run
 */
fun main() {
    println(
        """
        Second Brain

        The application shell arrives in Step 4 (Dashboard UI).
        Build order is in ARCHITECTURE.md section 7 and CLAUDE.md; do not start a
        step before the previous step's exit criteria pass.

        Step 1 is validated through the voice harness:

            ./gradlew :voice:run

        Configure it first: copy config.example.toml to
        ${System.getProperty("user.home")}${java.io.File.separator}.secondbrain${java.io.File.separator}config.toml
        and fill in the two required keys.
        """.trimIndent()
    )
}
