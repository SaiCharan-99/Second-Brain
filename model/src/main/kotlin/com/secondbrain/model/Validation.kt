package com.secondbrain.model

/**
 * EC-E1/D-008: shape validation for a typed or spoken-then-corrected email
 * address. "Validate RFC 5322 shape" per WF-2's stage 2 — deliberately a shape
 * check, not a deliverability check (EC-E7 is explicit that "valid in shape but
 * wrong person" is out of scope; that's why we spell it back).
 *
 * A real RFC 5322 grammar is far larger than this — comments, quoted local
 * parts, folding whitespace. Nothing in this system ever needs to accept those:
 * every address here was either typed by the user just now or read back to
 * them for confirmation, so this narrower, common-case pattern is the right
 * size for the job, same call `SpeechNormalizer` and `NoteMarkdown` already
 * made for themselves against a similarly bounded input.
 */
object EmailAddressValidator {
    private val PATTERN = Regex(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
            "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
    )

    fun isValid(address: String): Boolean {
        val trimmed = address.trim()
        return trimmed.isNotEmpty() && trimmed.length <= 254 && PATTERN.matches(trimmed)
    }
}
