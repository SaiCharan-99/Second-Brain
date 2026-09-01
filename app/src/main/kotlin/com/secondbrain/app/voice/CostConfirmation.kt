package com.secondbrain.app.voice

/**
 * A narrow, closed yes/no gate for exactly one question: "keep going past the
 * session cost ceiling?" (EC-G2; D-047's "continuing past it is confirmed by
 * SPEECH, not a click").
 *
 * This is not the open intent classification EC-V8 forbids routing on
 * keywords for — that rule exists to tell *different* actions apart ("send" /
 * "spend", "block" / "blog"), and Claude is the classifier for that because
 * the cost of guessing wrong is high and unbounded. There is no Claude turn to
 * ask here on purpose: the whole point of the gate is that the loop must not
 * resume without spending more money, so routing "should I keep spending
 * money?" through another paid model call is circular. A short allow-list for
 * one bounded binary question is the same shape as a "press 1 to confirm"
 * voice menu — not the open-ended routing EC-V8 is about.
 */
object CostConfirmation {

    sealed interface Verdict {
        data object Yes : Verdict
        data object No : Verdict

        /** Ambiguous or silent. [VoiceController] treats this as a stop, not a retry loop. */
        data object Unclear : Verdict
    }

    private val yes = setOf(
        "yes", "yeah", "yea", "yep", "sure", "go ahead", "continue",
        "keep going", "proceed", "do it", "okay", "ok", "affirmative",
    )
    private val no = setOf(
        "no", "nope", "nah", "stop", "cancel", "don't", "dont",
        "never mind", "nevermind", "that's enough", "thats enough", "negative",
    )

    fun parse(spoken: String): Verdict {
        val normalized = spoken.trim().lowercase().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return Verdict.Unclear
        if (normalized in yes || yes.any { normalized.startsWith("$it ") || normalized == it }) return Verdict.Yes
        if (normalized in no || no.any { normalized.startsWith("$it ") || normalized == it }) return Verdict.No
        return Verdict.Unclear
    }
}
