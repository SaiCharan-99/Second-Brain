package com.secondbrain.voice

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Masks anything key-shaped before it can reach a log file.
 *
 * CLAUDE.md hard prohibition: "Never write an API key into a log, a transcript,
 * a note, a commit, or a chat message." Ktor's Logging plugin logs request
 * headers and URLs by default, and Gemini takes its key as a `key=` query
 * parameter, so without this the very first STT call writes the key to
 * ~/.secondbrain/logs. That is not hypothetical -- it is the default behaviour
 * of the client this module uses.
 *
 * Two layers, because either alone is insufficient:
 *   1. [KtorRedactingLogger] wraps Ktor's own logger.
 *   2. [redact] is applied to any string that might carry a secret, so an
 *      exception message quoting a request URL is covered too.
 */
object SecretRedactor {

    const val MASK: String = "***REDACTED***"

    /**
     * Secrets registered from the loaded config. Pattern matching catches the
     * shapes we predicted; this catches the ones we did not.
     */
    private val knownSecrets = CopyOnWriteArraySet<String>()

    /** (pattern, replacement). `$1` keeps the identifying prefix so logs stay readable. */
    private val rules: List<Pair<Regex, String>> = listOf(
        // Gemini / Google pass the key as a query parameter.
        Regex("""([?&](?:key|api_key|apikey|access_token|token)=)[^&\s"']+""", RegexOption.IGNORE_CASE)
            to "$1$MASK",
        Regex("""(?i)(authorization\s*[:=]\s*(?:bearer\s+|basic\s+|token\s+)?)\S+""")
            to "$1$MASK",
        Regex("""(?i)(x-(?:goog-)?api-key\s*[:=]\s*)\S+""")
            to "$1$MASK",
        // JSON request/response bodies.
        Regex("""(?i)("(?:api_?key|apikey|access_token|refresh_token|secret|password)"\s*:\s*")[^"]*""")
            to "$1$MASK",
        // A config.toml line quoted into an error message.
        Regex("""(?im)^(\s*(?:api_?key|apikey|token|secret|password)\s*=\s*).*$""")
            to "$1\"$MASK\"",
        // Provider key shapes, recognisable with no surrounding context at all.
        Regex("""AIza[0-9A-Za-z_\-]{20,}""") to MASK,
        Regex("""sk-ant-[0-9A-Za-z_\-]{10,}""") to MASK,
        Regex("""ya29\.[0-9A-Za-z_\-]{20,}""") to MASK,
    )

    /** Call once at startup with every secret the loaded config actually holds. */
    fun register(vararg secrets: String?) {
        secrets.filterNotNull()
            .map { it.trim() }
            .filter { it.length >= 8 }
            .forEach { knownSecrets.add(it) }
    }

    internal fun clearRegistered() = knownSecrets.clear()

    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        var out: String = input
        knownSecrets.forEach { secret -> out = out.replace(secret, MASK) }
        rules.forEach { (re, replacement) -> out = re.replace(out, replacement) }
        return out
    }

    /** Wraps a Ktor logger so nothing reaches the appender un-redacted. */
    class KtorRedactingLogger(
        private val delegate: io.ktor.client.plugins.logging.Logger,
    ) : io.ktor.client.plugins.logging.Logger {
        override fun log(message: String) = delegate.log(redact(message))
    }
}
