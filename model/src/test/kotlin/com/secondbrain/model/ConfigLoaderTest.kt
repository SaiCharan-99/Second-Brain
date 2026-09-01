package com.secondbrain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * EC-G1 ("fail fast at startup with a named, actionable error. Never a 401
 * discovered mid-conversation") and EC-G3 ("model IDs live in config.toml").
 */
class ConfigLoaderTest {

    private val minimal = """
        [stt]
        model = "gemini-2.5-flash"
        api_key = "AIza-test-key-value"

        [tts]
        model = "kokoro"
        voice = "af_heart"
        base_url = "https://tts.example.com"
        api_key = "AIza-tts-test-key"
    """.trimIndent()

    private fun write(dir: Path, content: String): Path {
        val f = dir.resolve("config.toml")
        Files.writeString(f, content)
        return f
    }

    @Test
    fun `loads a minimal config and applies every default`(@TempDir dir: Path) {
        val config = ConfigLoader.load(write(dir, minimal), env = emptyMap(), userHome = dir.toString())

        assertEquals("gemini-2.5-flash", config.stt.model)
        assertEquals("af_heart", config.tts.voice)
        // R7: caps come from AppConfig defaults, not from anywhere else.
        assertEquals(16_000, config.audio.sampleRateHz)
        assertEquals(400L, config.gate.minUtteranceMs)
        assertEquals(60_000L, config.gate.maxUtteranceMs)
        assertEquals(1_200L, config.gate.vadSilenceTimeoutMs)
        assertEquals(GateMode.PUSH_TO_TALK, config.gate.mode)
        assertEquals(BargeInMode.KEYPRESS, config.gate.bargeIn)
        assertEquals(30, config.sessions.retentionDays)
        assertFalse(config.sessions.deleteWavOnCommit)
        assertEquals(60, config.speech.maxSpeechSeconds)
    }

    @Test
    @DisplayName("EC-G1 a missing key names the key, the env override and the file path")
    fun `missing required key`(@TempDir dir: Path) {
        val partial = """
            [stt]
            model = "gemini-2.5-flash"

            [tts]
            model = "kokoro"
            voice = "af_heart"
            base_url = "https://tts.example.com"
        """.trimIndent()

        val e = assertThrows(ConfigException::class.java) {
            ConfigLoader.load(write(dir, partial), env = emptyMap(), userHome = dir.toString())
        }
        assertTrue(e.message!!.contains("stt.api_key"), e.message)
        assertTrue(e.message!!.contains("SECONDBRAIN_STT_API_KEY"), e.message)
        assertTrue(e.message!!.contains("config.toml"), e.message)
    }

    @Test
    @DisplayName("EC-G1 an absent file tells you exactly what to copy where")
    fun `absent file`(@TempDir dir: Path) {
        val e = assertThrows(ConfigException::class.java) {
            ConfigLoader.load(dir.resolve("nope.toml"), env = emptyMap(), userHome = dir.toString())
        }
        assertTrue(e.message!!.contains("config.example.toml"), e.message)
    }

    @Test
    fun `env vars override the file`(@TempDir dir: Path) {
        val config = ConfigLoader.load(
            write(dir, minimal),
            env = mapOf("SECONDBRAIN_STT_MODEL" to "gemini-from-env"),
            userHome = dir.toString(),
        )
        assertEquals("gemini-from-env", config.stt.model)
    }

    @Test
    fun `env vars alone can satisfy every required key`(@TempDir dir: Path) {
        val config = ConfigLoader.load(
            dir.resolve("absent.toml"),
            env = mapOf(
                "SECONDBRAIN_STT_MODEL" to "m",
                "SECONDBRAIN_STT_API_KEY" to "k",
                "SECONDBRAIN_TTS_API_KEY" to "k2",
                "SECONDBRAIN_TTS_MODEL" to "kokoro",
                "SECONDBRAIN_TTS_VOICE" to "af",
                "SECONDBRAIN_TTS_BASE_URL" to "https://x",
            ),
            userHome = dir.toString(),
        )
        assertEquals("m", config.stt.model)
        assertEquals("https://x", config.tts.baseUrl)
        assertEquals("k2", config.tts.apiKey)
    }

    @Test
    fun `unrelated env vars are ignored`(@TempDir dir: Path) {
        val config = ConfigLoader.load(
            write(dir, minimal),
            env = mapOf("PATH" to "/usr/bin", "HOME" to "/root", "SECONDBRAIN" to "x"),
            userHome = dir.toString(),
        )
        assertEquals("gemini-2.5-flash", config.stt.model)
    }

    @Test
    fun `parses ints, doubles and booleans, not just strings`(@TempDir dir: Path) {
        val content = minimal + "\n\n" + """
            [gate]
            min_utterance_ms = 250
            energy_margin_db = 9.5
            allow_energy_barge_in_same_device = true
            mode = "ENERGY_VAD"
            barge_in = "ENERGY"

            [sessions]
            retention_days = 7
            delete_wav_on_commit = true
        """.trimIndent()

        val config = ConfigLoader.load(write(dir, content), env = emptyMap(), userHome = dir.toString())

        assertEquals(250L, config.gate.minUtteranceMs)
        assertEquals(9.5, config.gate.energyMarginDb)
        assertTrue(config.gate.allowEnergyBargeInSameDevice)
        assertEquals(GateMode.ENERGY_VAD, config.gate.mode)
        assertEquals(BargeInMode.ENERGY, config.gate.bargeIn)
        assertEquals(7, config.sessions.retentionDays)
        assertTrue(config.sessions.deleteWavOnCommit)
    }

    @Test
    fun `comments and blank lines are ignored, and a hash inside a string survives`(@TempDir dir: Path) {
        val content = """
            # Second Brain config
            [stt]
            model = "gemini-2.5-flash"   # the model id
            api_key = "AIza-with-a-#-inside"

            [tts]
            model = "kokoro"
            voice = "af_heart"
            base_url = "https://tts.example.com"
            api_key = "AIza-tts-test-key"
        """.trimIndent()

        val config = ConfigLoader.load(write(dir, content), env = emptyMap(), userHome = dir.toString())
        assertEquals("gemini-2.5-flash", config.stt.model)
        assertEquals("AIza-with-a-#-inside", config.stt.apiKey)
    }

    @Test
    fun `a malformed line names the file and the line number`(@TempDir dir: Path) {
        val e = assertThrows(ConfigException::class.java) {
            ConfigLoader.load(write(dir, "[stt]\nthis is not a key value pair\n"), env = emptyMap(), userHome = dir.toString())
        }
        assertTrue(e.message!!.contains(":2:"), e.message)
    }

    @Test
    fun `a key before any section is rejected rather than silently dropped`(@TempDir dir: Path) {
        val e = assertThrows(ConfigException::class.java) {
            ConfigLoader.load(write(dir, "model = \"x\"\n[stt]\n"), env = emptyMap(), userHome = dir.toString())
        }
        assertTrue(e.message!!.contains("before any [section]"), e.message)
    }

    @Test
    fun `a nested section is rejected loudly instead of half-understood`(@TempDir dir: Path) {
        val e = assertThrows(ConfigException::class.java) {
            ConfigLoader.load(write(dir, "[stt.retry]\nmax = 3\n"), env = emptyMap(), userHome = dir.toString())
        }
        assertTrue(e.message!!.contains("nested sections"), e.message)
    }

    @Test
    @DisplayName("Windows has no tilde, so expandHome must do it explicitly")
    fun `home expansion`() {
        val home = if (System.getProperty("os.name").lowercase().contains("windows")) "C:\\Users\\test" else "/home/test"
        assertEquals(
            Path.of(home).resolve(".secondbrain").normalize().toAbsolutePath(),
            ConfigLoader.expandHome("~/.secondbrain", home),
        )
        assertEquals(Path.of(home).normalize().toAbsolutePath(), ConfigLoader.expandHome("~", home))
        // An absolute path is left alone.
        val abs = Path.of(home).resolve("elsewhere").toAbsolutePath()
        assertEquals(abs.normalize(), ConfigLoader.expandHome(abs.toString(), home))
    }

    @Test
    @DisplayName("hard prohibition: redacted() masks the keys and nothing else")
    fun `redaction`(@TempDir dir: Path) {
        val config = ConfigLoader.load(write(dir, minimal), env = emptyMap(), userHome = dir.toString())
        val safe = config.redacted()

        assertEquals(AppConfig.MASK, safe.stt.apiKey)
        assertEquals(AppConfig.MASK, safe.tts.apiKey)
        assertEquals("gemini-2.5-flash", safe.stt.model)
        assertEquals("https://tts.example.com", safe.tts.baseUrl)
        assertFalse(safe.toString().contains("AIza-test-key-value"), "the stt key leaked through toString()")
        assertFalse(safe.toString().contains("AIza-tts-test-key"), "the tts key leaked through toString()")
    }

    @Test
    @DisplayName("D-065: tts.api_key is required now — Gemini TTS genuinely needs one, unlike a self-hosted Kokoro")
    fun `a missing tts api key fails fast by name`(@TempDir dir: Path) {
        val noTtsKey = """
            [stt]
            model = "gemini-2.5-flash"
            api_key = "AIza-test-key-value"

            [tts]
            model = "kokoro"
            voice = "af_heart"
            base_url = "https://tts.example.com"
        """.trimIndent()

        val e = assertThrows(ConfigException::class.java) {
            ConfigLoader.load(write(dir, noTtsKey), env = emptyMap(), userHome = dir.toString())
        }
        assertTrue(e.message!!.contains("tts.api_key"), e.message)
        assertTrue(e.message!!.contains("SECONDBRAIN_TTS_API_KEY"), e.message)
    }

    @Test
    @DisplayName("D-065: an absent [tts] section still loads, defaulting to Gemini TTS")
    fun `tts defaults to Gemini when the section is entirely absent`(@TempDir dir: Path) {
        val sttOnly = """
            [stt]
            model = "gemini-3.5-transcribe"
            api_key = "AIza-test-key-value"
        """.trimIndent()

        val config = ConfigLoader.load(
            write(dir, sttOnly),
            env = mapOf("SECONDBRAIN_TTS_API_KEY" to "AIza-tts-test-key"),
            userHome = dir.toString(),
        )
        assertEquals("gemini-3.1-flash-tts-preview", config.tts.model)
        assertEquals("Kore", config.tts.voice)
        assertEquals("https://generativelanguage.googleapis.com", config.tts.baseUrl)
        assertEquals("gemini-3.5-transcribe", config.stt.model)
    }
}
