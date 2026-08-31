package com.secondbrain.model

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** EC-G1: a named, actionable failure at startup. Never a 401 mid-conversation. */
class ConfigException(message: String) : Exception(message)

/**
 * Loads ~/.secondbrain/config.toml.
 *
 * Precedence, highest first:
 *   1. Environment variable  SECONDBRAIN_<SECTION>_<KEY>   (upper snake case)
 *   2. config.toml
 *   3. the default in [AppConfig]
 *
 * Every required key is validated here and only here, so a missing Gemini key is
 * a one-line error at second zero rather than a 401 discovered halfway through
 * dictating a thought (EC-G1).
 */
object ConfigLoader {

    const val ENV_PREFIX: String = "SECONDBRAIN_"
    private const val CONFIG_FILE = "config.toml"

    private val json = Json { ignoreUnknownKeys = true }

    /** Keys with no default. Absent from both file and env means hard failure. */
    private val required = listOf(
        "stt.model", "stt.api_key",
        "tts.model", "tts.voice", "tts.base_url",
    )

    fun load(
        explicitPath: Path? = null,
        env: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): AppConfig {
        val rootDir = resolveRoot(env, userHome)
        val path = explicitPath ?: rootDir.resolve(CONFIG_FILE)

        val fileTable: Map<String, Map<String, String>> =
            if (Files.exists(path)) parseFlat(Files.readString(path), path.toString())
            else emptyMap()

        val merged = applyEnvOverrides(fileTable, env)
        validateRequired(merged, path)

        return try {
            json.decodeFromString(AppConfig.serializer(), toJsonObject(merged))
        } catch (e: Exception) {
            throw ConfigException(
                "config.toml at " + path + " parsed but does not match the expected shape: " +
                    e.message + "\nCompare against config.example.toml at the repository root."
            )
        }
    }

    fun resolveRoot(
        env: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): Path {
        val raw = env[ENV_PREFIX + "PATHS_ROOT"] ?: PathsConfig().root
        return expandHome(raw, userHome)
    }

    /**
     * Windows has no tilde, and the shell never expands it for us because the
     * value comes out of a TOML file. Do it explicitly.
     */
    fun expandHome(raw: String, userHome: String = System.getProperty("user.home")): Path {
        val trimmed = raw.trim()
        return when {
            trimmed == "~" -> Paths.get(userHome)
            trimmed.startsWith("~/") || trimmed.startsWith("~\\") ->
                Paths.get(userHome, trimmed.substring(2))
            else -> Paths.get(trimmed)
        }.normalize().toAbsolutePath()
    }

    // ── TOML to flat section/key/value ───────────────────────────────────────

    /**
     * A deliberately small TOML reader: this file is a handful of string, int,
     * bool and float scalars under single-level sections. Anything more exotic
     * is rejected loudly rather than silently half-understood.
     */
    internal fun parseFlat(text: String, origin: String): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var section = ""
        text.lineSequence().forEachIndexed { idx, rawLine ->
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) return@forEachIndexed

            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw ConfigException(origin + ":" + (idx + 1) + ": malformed section header: " + rawLine)
                }
                section = line.substring(1, line.length - 1).trim()
                if (section.contains('.')) {
                    throw ConfigException(
                        origin + ":" + (idx + 1) + ": nested sections are not supported ([" + section +
                            "]). config.toml uses single-level sections only."
                    )
                }
                out.getOrPut(section) { LinkedHashMap() }
                return@forEachIndexed
            }

            val eq = line.indexOf('=')
            if (eq <= 0) {
                throw ConfigException(origin + ":" + (idx + 1) + ": expected key = value, got: " + rawLine)
            }
            val key = line.substring(0, eq).trim()
            val value = unquote(line.substring(eq + 1).trim())
            if (section.isEmpty()) {
                throw ConfigException(
                    origin + ":" + (idx + 1) + ": key '" + key + "' appears before any [section]."
                )
            }
            out.getOrPut(section) { LinkedHashMap() }[key] = value
        }
        return out
    }

    private fun stripComment(line: String): String {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == '#' && !inQuotes -> return line.substring(0, i)
            }
        }
        return line
    }

    private fun unquote(v: String): String =
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v

    // ── env overrides ───────────────────────────────────────────────────────

    internal fun applyEnvOverrides(
        table: Map<String, Map<String, String>>,
        env: Map<String, String>,
    ): Map<String, Map<String, String>> {
        val out: MutableMap<String, LinkedHashMap<String, String>> =
            table.mapValues { LinkedHashMap(it.value) }.toMutableMap()
        env.forEach { (name, value) ->
            if (!name.startsWith(ENV_PREFIX)) return@forEach
            val rest = name.removePrefix(ENV_PREFIX)
            val split = rest.indexOf('_')
            if (split <= 0) return@forEach
            val section = rest.substring(0, split).lowercase()
            val key = rest.substring(split + 1).lowercase()
            out.getOrPut(section) { LinkedHashMap() }[key] = value
        }
        return out
    }

    // ── validation ──────────────────────────────────────────────────────────

    private fun validateRequired(table: Map<String, Map<String, String>>, path: Path) {
        val missing = required.filter { dotted ->
            val parts = dotted.split(".", limit = 2)
            table[parts[0]]?.get(parts[1]).isNullOrBlank()
        }
        if (missing.isEmpty()) return

        throw ConfigException(
            buildString {
                appendLine("Configuration is incomplete. Missing required key(s):")
                missing.forEach { key ->
                    val envName = ENV_PREFIX + key.replace('.', '_').uppercase()
                    appendLine("  - " + key + "        (or set " + envName + ")")
                }
                appendLine()
                appendLine("Expected file: " + path)
                if (Files.notExists(path)) {
                    appendLine("That file does not exist. Copy config.example.toml from the")
                    appendLine("repository root to " + path + " and fill in your keys.")
                } else {
                    appendLine("The file exists but does not define the key(s) above.")
                }
                appendLine()
                appendLine("Never commit config.toml - it is in .gitignore for a reason (EC-G4).")
            }
        )
    }

    // ── flat table to JSON for kotlinx-serialization ─────────────────────────

    private val knownNumeric = setOf(
        "sample_rate_hz", "capture_chunk_ms", "playback_chunk_ms",
        "min_utterance_ms", "max_utterance_ms", "calibration_ms",
        "vad_silence_timeout_ms", "barge_in_grace_ms",
        "inline_limit_bytes", "max_attempts", "initial_backoff_ms",
        "request_timeout_ms", "thinking_cue_after_ms",
        "retention_days", "max_speech_seconds",
        // vault
        "max_folder_depth", "max_top_level_folders", "max_slug_length",
        "tree_default_depth", "tree_folder_listing_cap", "watch_debounce_ms",
        "atomic_move_attempts", "atomic_move_backoff_ms",
    )
    private val knownDecimal = setOf(
        "energy_margin_db", "speed",
        // vault
        "folder_similarity_threshold", "folder_jaccard_weight", "link_fuzzy_threshold",
    )
    private val knownBoolean = setOf(
        "allow_energy_barge_in_same_device", "delete_wav_on_commit", "fallback_enabled",
    )

    internal fun toJsonObject(table: Map<String, Map<String, String>>): String = buildString {
        append('{')
        var first = true
        table.forEach { (section, entries) ->
            if (entries.isEmpty()) return@forEach
            if (!first) append(',')
            first = false
            append('"').append(section).append("\":{")
            var innerFirst = true
            entries.forEach { (k, v) ->
                if (!innerFirst) append(',')
                innerFirst = false
                append('"').append(k).append("\":").append(encodeScalar(k, v))
            }
            append('}')
        }
        append('}')
    }

    private fun encodeScalar(key: String, value: String): String = when {
        key in knownNumeric || key in knownDecimal -> value
        key in knownBoolean -> value.lowercase()
        else -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
