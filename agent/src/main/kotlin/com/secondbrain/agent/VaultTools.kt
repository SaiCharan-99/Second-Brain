package com.secondbrain.agent

import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import com.secondbrain.model.TreeNode
import com.secondbrain.model.VaultConfig
import com.secondbrain.ports.VaultStore
import com.secondbrain.ports.WriteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The seven vault tools, plus `ask_user`, as the model sees them.
 *
 * `:agent` never sees `:vault` — this talks to [VaultStore]. Every tool is
 * AUTONOMOUS: read-only, or a write that is cheap to undo inside our own vault
 * (section 4). `request_typed_input` is deliberately **not** registered: nothing
 * in Step 3 has a verbatim field, and an unused tool costs bytes in the cached
 * prefix while inviting the model to use it wrongly (H25 / D-054).
 *
 * Every schema is a string literal, fixed at construction. Schemas render first
 * in the cache key, so a schema built per-request from a map would invalidate the
 * cache on every call while looking byte-identical to a reader.
 */
class VaultTools(
    private val vault: VaultStore,
    private val vaultConfig: VaultConfig,
    /**
     * `ask_user`: speaks the question and returns the spoken answer.
     *
     * Suspends the whole agent loop on a voice round-trip — TTS, then mic, then
     * STT. Consequences the artifacts do not reach are handled by the caller that
     * supplies this: a silent user, an utterance the EC-V1 gate discards, a
     * barge-in mid-question, and the fact that two clarifying questions burn two
     * of the twelve iterations (H11 / D-055).
     */
    private val askUser: suspend (question: String) -> AskResult,
) {

    private val log = LoggerFactory.getLogger(VaultTools::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Outcome of asking the user something out loud. */
    sealed interface AskResult {
        data class Answered(val text: String) : AskResult

        /**
         * No usable answer: silence, a gate-discarded utterance, or a failed
         * transcription. Distinct from an answer of "no" (H11).
         */
        data class NoAnswer(val reason: String) : AskResult
    }

    fun register(builder: ToolRegistry.Builder): ToolRegistry.Builder = builder
        .autonomous("vault_tree", TREE_DESC, TREE_SCHEMA) { input -> tree(input) }
        .autonomous("vault_read", READ_DESC, READ_SCHEMA) { input -> read(input) }
        .autonomous("vault_search", SEARCH_DESC, SEARCH_SCHEMA) { input -> search(input) }
        .autonomous("vault_create_folder", CREATE_FOLDER_DESC, CREATE_FOLDER_SCHEMA) { input -> createFolder(input) }
        .autonomous("vault_write_note", WRITE_NOTE_DESC, WRITE_NOTE_SCHEMA) { input -> writeNote(input) }
        .autonomous("vault_append_note", APPEND_NOTE_DESC, APPEND_NOTE_SCHEMA) { input -> appendNote(input) }
        .autonomous("vault_move_note", MOVE_NOTE_DESC, MOVE_NOTE_SCHEMA) { input -> moveNote(input) }
        .autonomous("ask_user", ASK_USER_DESC, ASK_USER_SCHEMA) { input -> ask(input) }

    // ── handlers ────────────────────────────────────────────────────────────

    private suspend fun tree(input: String): ToolOutcome {
        val depth = optInt(input, "depth") ?: vaultConfig.treeDefaultDepth
        val root = vault.tree(depth)

        val rendered = renderTree(root)
        val capped = capTokens(rendered, TREE_TOKEN_CAP)

        return ToolOutcome(
            buildJsonObject {
                put("tree", capped.text)
                put("total_notes", root.rollupNoteCount)
                put("top_level_folders", root.children.size)
                put("max_top_level_folders", vaultConfig.maxTopLevelFolders)
                if (capped.truncated) {
                    put("truncated", true)
                    put("note", "The tree was truncated to fit. Ask for a shallower depth if you need the rest.")
                }
            }.toString()
        )
    }

    /**
     * Indented text rather than nested JSON.
     *
     * A tree is what the model reads to choose a folder, and indented text costs
     * roughly half the tokens of the equivalent JSON for the same information.
     * EC-A5 caps this response at ~2000 tokens; halving the encoding is most of
     * how it fits.
     */
    internal fun renderTree(node: TreeNode, indent: Int = 0): String = buildString {
        node.children.forEach { child ->
            append("  ".repeat(indent))
            append(child.name)
            append("  (")
            append(child.rollupNoteCount)
            append(" notes")
            if (child.danglingCount > 0) {
                append(", ").append(child.danglingCount).append(" dangling")
            }
            append(")")
            // EC-A5: past the listing cap a folder reports a count, not a listing.
            if (child.rollupNoteCount > vaultConfig.treeFolderListingCap) {
                append(" [large]")
            }
            append('\n')
            append(renderTree(child, indent + 1))
        }
    }

    private suspend fun read(input: String): ToolOutcome {
        val path = requireString(input, "path")
        val note = vault.read(path)
            ?: return ToolOutcome(
                buildJsonObject {
                    put("error", "not_found")
                    put("message", "No note at '$path'.")
                }.toString(),
                isError = true,
            )

        return ToolOutcome(
            buildJsonObject {
                put("path", note.path)
                put("title", note.title)
                put("summary", note.summary)
                put("tags", JsonArray(note.tags.map { JsonPrimitive(it) }))
                put("created", note.created.toString())
                put("updated", note.updated.toString())
                put("body", note.bodyMarkdown)
            }.toString(),
            notePath = note.path,
        )
    }

    private suspend fun search(input: String): ToolOutcome {
        val query = requireString(input, "query")
        val limit = optInt(input, "limit") ?: 8
        val hits = vault.search(query, limit)

        return ToolOutcome(
            buildJsonObject {
                put("query", query)
                put("hits", buildJsonArray {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("path", hit.path)
                            put("title", hit.title)
                            put("summary", hit.summary)
                            put("excerpt", hit.snippet)
                        })
                    }
                })
                if (hits.isEmpty()) put("note", "Nothing matched. The vault may not have this yet.")
            }.toString()
        )
    }

    private suspend fun createFolder(input: String): ToolOutcome {
        val path = requireString(input, "path")

        return when (val verdict = vault.createFolder(path)) {
            is FolderVerdict.Accepted -> ToolOutcome(
                buildJsonObject {
                    put("created", verdict.path)
                }.toString()
            )

            is FolderVerdict.Rejected -> ToolOutcome(
                // A rejection is information the model must act on, so it is
                // structured exactly as section 5 WF-1 rule 3 specifies.
                buildJsonObject {
                    put("rejected", true)
                    put("reason", verdict.reason.name)
                    verdict.useInstead?.let { put("use_instead", it) }
                    verdict.score?.let { put("score", it) }
                    put("message", verdict.detail)
                }.toString(),
                isError = false,
            )
        }
    }

    private suspend fun writeNote(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val draft = NoteDraft(
            folder = obj["folder"]?.jsonPrimitive?.content ?: "Inbox",
            title = requireString(input, "title"),
            tags = obj["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            summary = obj["summary"]?.jsonPrimitive?.content ?: "",
            bodyMarkdown = obj["body"]?.jsonPrimitive?.content ?: "",
            source = NoteSource.VOICE,
        )
        val confirmNew = obj["confirm_new"]?.jsonPrimitive?.content?.toBoolean() ?: false

        return when (val result = vault.writeNote(draft, confirmNew)) {
            is WriteResult.Written -> ToolOutcome(
                buildJsonObject {
                    put("path", result.path)
                    if (result.resolvedLinks.isNotEmpty()) {
                        put("resolved_links", JsonArray(result.resolvedLinks.map { JsonPrimitive(it) }))
                    }
                    if (result.danglingLinks.isNotEmpty()) {
                        put("dangling_links", JsonArray(result.danglingLinks.map { JsonPrimitive(it) }))
                        put(
                            "dangling_note",
                            "Those links point at notes that do not exist yet. That is fine and recorded; " +
                                "do not rename them to make them resolve.",
                        )
                    }
                    if (result.slugSuffixed) put("slug_suffixed", true)
                }.toString(),
                notePath = result.path,
            )

            is WriteResult.Rejected -> ToolOutcome(
                buildJsonObject {
                    put("rejected", true)
                    put("reason", result.reason)
                    put("message", result.detail)
                    result.existingPath?.let { put("existing_note", it) }
                    result.score?.let { put("score", it) }
                    if (result.reason == "duplicate") {
                        put(
                            "next_step",
                            "Read the existing note. Append to it if this belongs there, " +
                                "or write again with confirm_new true if it is genuinely a separate thought.",
                        )
                    }
                }.toString(),
                // Not an error: a rejection the model is expected to act on.
                isError = false,
            )
        }
    }

    private suspend fun appendNote(input: String): ToolOutcome {
        val path = requireString(input, "path")
        val heading = requireString(input, "heading")
        val markdown = requireString(input, "text")

        return when (val result = vault.appendNote(path, heading, markdown)) {
            is WriteResult.Written -> ToolOutcome(
                buildJsonObject {
                    put("appended_to", result.path)
                    put("heading", heading)
                }.toString(),
                notePath = result.path,
            )
            is WriteResult.Rejected -> rejection(result)
        }
    }

    private suspend fun moveNote(input: String): ToolOutcome {
        val path = requireString(input, "path")
        val toFolder = requireString(input, "to_folder")

        return when (val result = vault.moveNote(path, toFolder)) {
            is WriteResult.Written -> ToolOutcome(
                buildJsonObject {
                    put("moved_to", result.path)
                    put("moved_from", path)
                }.toString(),
                notePath = result.path,
            )
            is WriteResult.Rejected -> rejection(result)
        }
    }

    private suspend fun ask(input: String): ToolOutcome {
        val question = requireString(input, "question")
        log.info("ask_user: {}", question)

        return when (val answer = askUser(question)) {
            is AskResult.Answered -> ToolOutcome(
                buildJsonObject { put("answer", answer.text) }.toString()
            )
            is AskResult.NoAnswer -> ToolOutcome(
                // Distinguished from an answer of "no" on purpose. Treating silence
                // as a negative is how you file a note the user never confirmed.
                buildJsonObject {
                    put("answer", JsonPrimitive(null as String?))
                    put("no_answer", true)
                    put("reason", answer.reason)
                    put("next_step", "The user did not answer. Decide without them, or stop and say so.")
                }.toString()
            )
        }
    }

    private fun rejection(result: WriteResult.Rejected) = ToolOutcome(
        buildJsonObject {
            put("rejected", true)
            put("reason", result.reason)
            put("message", result.detail)
        }.toString(),
        isError = result.reason == "unsafe_path" || result.reason == "not_found",
    )

    // ── input helpers ───────────────────────────────────────────────────────

    private fun requireString(input: String, field: String): String {
        val obj = json.parseToJsonElement(input).jsonObject
        return obj[field]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("missing required field '$field'")
    }

    private fun optInt(input: String, field: String): Int? {
        val obj = json.parseToJsonElement(input).jsonObject
        return obj[field]?.jsonPrimitive?.content?.toIntOrNull()
    }

    /**
     * EC-A5's ~2000-token response cap.
     *
     * Step 2 built the depth limit and the rollup counts but not this. A real
     * `count_tokens` call per tool result would double the number of API requests
     * per turn to measure something we only need approximately, so this estimates
     * at ~4 characters per token and truncates on a line boundary with an explicit
     * marker (H17 / D-054).
     */
    internal fun capTokens(text: String, tokenCap: Int): Capped {
        val charCap = tokenCap * CHARS_PER_TOKEN
        if (text.length <= charCap) return Capped(text, truncated = false)

        val cut = text.take(charCap)
        val lastNewline = cut.lastIndexOf('\n')
        val kept = if (lastNewline > charCap / 2) cut.take(lastNewline) else cut
        return Capped(kept + "\n... (truncated to fit)", truncated = true)
    }

    data class Capped(val text: String, val truncated: Boolean)

    private companion object {
        const val CHARS_PER_TOKEN = 4
        const val TREE_TOKEN_CAP = 2_000

        // Descriptions are what the model actually reads to choose a tool, so they
        // say when to use each one, not just what it does.

        const val TREE_DESC =
            "The vault's folder structure with note counts. Call this before placing a note so you " +
                "can choose a folder that already exists."
        const val TREE_SCHEMA = """
            {"type":"object","properties":{
              "depth":{"type":"integer","description":"How many levels deep to show. Defaults to 3."}
            },"required":[]}
        """

        const val READ_DESC =
            "Read one note in full: its title, summary, tags and body. Use this before appending to " +
                "a note, and to check what an existing note already covers."
        const val READ_SCHEMA = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Vault-relative path, e.g. Projects/Positioning/the-moat.md"}
            },"required":["path"]}
        """

        const val SEARCH_DESC =
            "Full-text search over every note. Use it to find a wikilink target, to check whether a " +
                "thought is already captured, or to answer a question about what the user said before."
        const val SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Words to search for. Punctuation is ignored."},
              "limit":{"type":"integer","description":"Maximum hits to return. Defaults to 8."}
            },"required":["query"]}
        """

        const val CREATE_FOLDER_DESC =
            "Create a folder. This may be rejected as too similar to an existing folder, too deep, or " +
                "because the vault already has enough top-level folders. A rejection names what to use " +
                "instead - follow it rather than trying a different spelling."
        const val CREATE_FOLDER_SCHEMA = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Vault-relative folder path, e.g. Projects/Positioning"}
            },"required":["path"]}
        """

        const val WRITE_NOTE_DESC =
            "Write a new note. May be rejected if it looks like a note that already exists, in which " +
                "case the rejection names it - read that note and append to it, or write again with " +
                "confirm_new if this is genuinely a separate thought."
        const val WRITE_NOTE_SCHEMA = """
            {"type":"object","properties":{
              "folder":{"type":"string","description":"Vault-relative folder. Defaults to Inbox."},
              "title":{"type":"string","description":"A title the user would recognise a month later."},
              "summary":{"type":"string","description":"One sentence. Shown in the note list and used by search."},
              "body":{"type":"string","description":"The thought, in the user's own words. May contain [[wikilinks]]."},
              "tags":{"type":"array","description":"A few lowercase topic tags."},
              "confirm_new":{"type":"boolean","description":"Write even if it looks like a duplicate. Only after checking."}
            },"required":["title","summary","body"]}
        """

        const val APPEND_NOTE_DESC =
            "Add text to an existing note under a heading, creating the heading if it is not there. " +
                "Use this when the user elaborates on something already captured."
        const val APPEND_NOTE_SCHEMA = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Vault-relative path of the note to add to."},
              "heading":{"type":"string","description":"Heading to add under, e.g. 'Later thoughts'."},
              "text":{"type":"string","description":"Markdown to add. May contain [[wikilinks]]."}
            },"required":["path","heading","text"]}
        """

        const val MOVE_NOTE_DESC =
            "Move a note to a different folder, correcting a bad placement. The note records where it " +
                "came from."
        const val MOVE_NOTE_SCHEMA = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Vault-relative path of the note to move."},
              "to_folder":{"type":"string","description":"Destination folder, vault-relative."}
            },"required":["path","to_folder"]}
        """

        const val ASK_USER_DESC =
            "Ask the user one short question out loud and wait for their spoken answer. Use this rather " +
                "than guessing at what they meant. They may not answer, in which case you are told so."
        const val ASK_USER_SCHEMA = """
            {"type":"object","properties":{
              "question":{"type":"string","description":"One short question, phrased to be heard rather than read."}
            },"required":["question"]}
        """
    }
}
