package com.secondbrain.vault

import com.secondbrain.model.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * config.example.toml is the file the user copies. If it does not parse, the very
 * first thing they do fails with a message about their own typo.
 */
class ConfigTemplateTest {

    private fun template(): Path {
        // The test runs from the :vault module directory.
        val candidates = listOf(
            Path.of("config.example.toml"),
            Path.of("../config.example.toml"),
        )
        return candidates.first { Files.exists(it) }
    }

    @Test
    @DisplayName("the committed template parses, once the two REQUIRED keys are filled in")
    fun `template parses`() {
        val filled = Files.readString(template())
            .replace("""api_key = ""       """, """api_key = "AIza-fake"  """)
            .replace("""api_key = """"", """api_key = "AIza-fake"""")
            .replace("""base_url = """"", """base_url = "http://localhost:8880"""")

        val tmp = Files.createTempFile("cfg", ".toml")
        Files.writeString(tmp, filled)

        val config = ConfigLoader.load(tmp, env = emptyMap(), userHome = tmp.parent.toString())

        // Spot-check that each section actually landed, including the new one.
        assertEquals("gemini-2.5-flash", config.stt.model)
        assertEquals("af_heart", config.tts.voice)
        assertEquals(16_000, config.audio.sampleRateHz)
        assertEquals(400L, config.gate.minUtteranceMs)
        assertEquals(30, config.sessions.retentionDays)
        assertEquals(60, config.speech.maxSpeechSeconds)

        assertEquals(0.72, config.vault.folderSimilarityThreshold)
        assertEquals(0.6, config.vault.folderJaccardWeight)
        assertEquals(3, config.vault.maxFolderDepth)
        assertEquals(12, config.vault.maxTopLevelFolders)
        assertEquals(0.85, config.vault.linkFuzzyThreshold)
        assertEquals(80, config.vault.maxSlugLength)
        assertEquals(20, config.vault.treeFolderListingCap)
        assertEquals(300L, config.vault.watchDebounceMs)
        assertEquals(5, config.vault.atomicMoveAttempts)

        Files.deleteIfExists(tmp)
    }

    @Test
    @DisplayName("the [vault] section is actually read, not silently ignored")
    fun `vault section is wired`() {
        // The assertions above all happen to equal the AppConfig defaults, so they
        // would pass even if [vault] were being dropped on the floor. This changes
        // a value away from its default and checks it lands.
        val filled = Files.readString(template())
            .replace("""api_key = """"", """api_key = "AIza-fake"""")
            .replace("""base_url = """"", """base_url = "http://localhost:8880"""")
            .replace("folder_similarity_threshold = 0.72", "folder_similarity_threshold = 0.55")
            .replace("max_top_level_folders = 12", "max_top_level_folders = 20")
            .replace("max_slug_length = 80", "max_slug_length = 42")

        val tmp = Files.createTempFile("cfg", ".toml")
        Files.writeString(tmp, filled)

        val config = ConfigLoader.load(tmp, env = emptyMap(), userHome = tmp.parent.toString())

        assertEquals(0.55, config.vault.folderSimilarityThreshold)
        assertEquals(20, config.vault.maxTopLevelFolders)
        assertEquals(42, config.vault.maxSlugLength)

        Files.deleteIfExists(tmp)
    }

    @Test
    @DisplayName("the template never contains a real-looking key (EC-G4)")
    fun `no secrets in the template`() {
        val text = Files.readString(template())
        assertTrue(
            Regex("""AIza[0-9A-Za-z_\-]{20,}""").findAll(text).none(),
            "config.example.toml appears to contain a real Google API key",
        )
        assertTrue(
            Regex("""sk-ant-[0-9A-Za-z_\-]{10,}""").findAll(text).none(),
            "config.example.toml appears to contain a real Anthropic key",
        )
    }

    @Test
    @DisplayName("every VaultConfig key is documented in the template")
    fun `no undocumented vault keys`() {
        val text = Files.readString(template())
        listOf(
            "folder_similarity_threshold", "folder_jaccard_weight", "max_folder_depth",
            "max_top_level_folders", "link_fuzzy_threshold", "max_slug_length",
            "tree_default_depth", "tree_folder_listing_cap", "watch_debounce_ms",
            "atomic_move_attempts", "atomic_move_backoff_ms",
        ).forEach { key ->
            assertTrue(text.contains(key), "R7: '$key' is a threshold with no entry in config.example.toml")
        }
    }
}
