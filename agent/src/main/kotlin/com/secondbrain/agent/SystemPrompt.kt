package com.secondbrain.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * System prompt v1, plus the fixed strings the loop needs.
 *
 * Two rules shape this file.
 *
 * **R7 — no caps or thresholds in prose.** "A prompt asking a model to keep it
 * short is not a cap." So there is no "use at most 12 tools", no "keep folders
 * under 12", no similarity threshold. Those live in `AgentConfig` and
 * `VaultConfig` and are enforced by code that returns structured rejections. What
 * the prompt does instead is explain *how to respond* to those rejections.
 *
 * **Cache stability.** [system] is a pure function of nothing — no date, no note
 * count, no vault tree. Any per-request byte here invalidates the cached prefix
 * on every single call, which on a twelve-iteration turn means paying full price
 * twelve times over for bytes that never change. Volatile context goes in the
 * user turn, behind the last breakpoint (H6).
 */
class SystemPrompt(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm")

    /**
     * The frozen prefix. Byte-identical on every request, forever.
     *
     * If you are about to interpolate something in here, put it in [userTurn].
     */
    fun system(): String = SYSTEM

    /**
     * Wraps the user's utterance with the volatile context.
     *
     * This is where the date goes. It changes every minute, so in the system
     * prompt it would be a guaranteed cache miss; here it sits after the last
     * breakpoint and costs nothing.
     */
    fun userTurn(utterance: String, now: Instant = Instant.now()): String = buildString {
        append("Current date and time: ")
        append(dateFormat.format(now.atZone(zone)))
        append(" (")
        append(zone.id)
        append(")\n\n")
        append("The user said:\n")
        append(utterance)
    }

    /** EC-A1. Delivered as a mid-conversation system message, not a prompt edit. */
    fun iterationCapNotice(iterations: Int): String =
        "You have used $iterations tool calls on this request, which is the limit. " +
            "Do not call any more tools. Reply now with one or two spoken sentences " +
            "describing what you did accomplish, and say plainly what you did not."

    /** EC-A3, past the self-correction cap. */
    fun selfCorrectionCapNotice(): String =
        "Several tool calls in a row were rejected as invalid. Stop calling tools. " +
            "Tell the user in one sentence what you were trying to do and that it did not work."

    /** EC-G2. Also a mid-conversation system message. */
    fun costCeilingWarning(spentUsd: Double, ceilingUsd: Double): String =
        "This session has spent $%.2f of its $%.2f budget. Keep replies and tool use minimal."
            .format(spentUsd, ceilingUsd)

    fun truncatedFallback(): String =
        "I ran out of room mid-thought. Ask me again and I'll be briefer."

    /**
     * Spoken when the model declines.
     *
     * Honest and short. The category is not spoken — it is an API-internal label
     * and reading it aloud would be noise.
     */
    fun refusalFallback(category: String?): String =
        "I can't help with that one."

    fun apiFailureFallback(): String =
        "I couldn't reach Claude. Your recording is saved, so nothing is lost. Try again in a moment."

    fun emptyTranscriptFallback(): String = "I didn't catch that."

    private companion object {
        /**
         * Placement rules, one-note-per-thought, wikilink conventions, and
         * "ask rather than guess" — the four things Step 3's build list names.
         *
         * Written as instructions about *behaviour*, not as limits. The Folder
         * Guard and the duplicate gate enforce the limits and return structured
         * rejections; what the prompt supplies is how to read them.
         */
        val SYSTEM: String = """
            You are Second Brain, a voice-first note-taking assistant. The user speaks to you and
            hears your replies aloud. You file their thoughts as Markdown notes in a personal vault.

            ## How you talk

            Everything you say is spoken by a text-to-speech voice, so write for the ear.
            One or two sentences. No lists, no headings, no markdown, no URLs, no emoji.
            Say what you did and where it went. Do not narrate what you are about to do.

            Good: "Saved to Projects slash Positioning as 'Offline inference is the moat'."
            Bad: "I'll first check the vault structure, then create a note, then confirm..."

            ## Filing a thought

            One thought, one note. If the user says three unrelated things in one breath, that is
            three notes. If they elaborate on something they said a moment ago, that is an append
            to the existing note, not a new one.

            Before you place a note, look at the vault with vault_tree. Choose the folder a person
            would choose. Prefer a folder that already exists — an existing home is almost always
            better than a new one, even if the name is not perfect.

            A note needs a title someone would recognise a month later, a one-sentence summary,
            and the thought itself in the body. Write the body in the user's own words and register.
            Do not add analysis, structure, or headings they did not ask for. Do not pad.

            ## Tools that refuse

            Some tools reject what you ask for and explain why. A rejection is information, not an
            error to retry. Read the reason and act on it:

            - Creating a folder may come back rejected as too similar to one that exists. Use the
              folder it names instead. Do not try a variant spelling.
            - Creating a folder may be rejected for depth or because the vault already has enough
              top-level folders. Nest it inside an existing one.
            - Writing a note may come back rejected because it looks like something already in the
              vault. It will name the existing note. Read that note. If the new thought genuinely
              belongs there, append to it. If it is a distinct thought that happens to be about the
              same subject, write it again with confirm_new set to true and explain nothing.

            ## Wikilinks

            When the thought refers to a person, project, or idea that has its own note, link it
            with double square brackets around the note's title, like [[BluePrint Lens]]. Search
            first if you are unsure the note exists. A link to a note that does not exist yet is
            fine and is recorded — do not invent a different name to make it resolve.

            ## Ask rather than guess

            If you cannot tell what the user meant, use ask_user and ask one short question. It is
            spoken aloud, so ask one thing, not three. Guessing at a folder is cheap to correct;
            guessing at what someone meant is not.

            Never invent a fact, a name, an email address, or a date the user did not say.
        """.trimIndent()
    }
}
