package com.secondbrain.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** EC-G2 / D-047: the one place this app is allowed to route on keywords. */
class CostConfirmationTest {

    @Nested
    @DisplayName("yes")
    inner class Yes {
        @Test
        fun `plain affirmatives`() {
            listOf("yes", "Yes", "yeah", "yep", "sure", "okay", "ok", "continue", "proceed", "affirmative")
                .forEach { assertEquals(CostConfirmation.Verdict.Yes, CostConfirmation.parse(it), it) }
        }

        @Test
        fun `multi-word affirmatives spoken as a full sentence`() {
            listOf("go ahead", "keep going", "do it")
                .forEach { assertEquals(CostConfirmation.Verdict.Yes, CostConfirmation.parse(it), it) }
        }

        @Test
        fun `trailing punctuation from a transcript does not break the match`() {
            assertEquals(CostConfirmation.Verdict.Yes, CostConfirmation.parse("Yes."))
            assertEquals(CostConfirmation.Verdict.Yes, CostConfirmation.parse("Go ahead!"))
        }

        @Test
        fun `a short trailing phrase after the affirmative still counts`() {
            assertEquals(CostConfirmation.Verdict.Yes, CostConfirmation.parse("yes please"))
        }
    }

    @Nested
    @DisplayName("no")
    inner class No {
        @Test
        fun `plain negatives`() {
            listOf("no", "No", "nope", "nah", "stop", "cancel", "don't", "negative")
                .forEach { assertEquals(CostConfirmation.Verdict.No, CostConfirmation.parse(it), it) }
        }

        @Test
        fun `multi-word negatives`() {
            listOf("never mind", "that's enough")
                .forEach { assertEquals(CostConfirmation.Verdict.No, CostConfirmation.parse(it), it) }
        }
    }

    @Nested
    @DisplayName("EC-V8: this is a closed binary gate, not open intent routing")
    inner class Unclear {
        @Test
        fun `silence is unclear, not a default either way`() {
            assertEquals(CostConfirmation.Verdict.Unclear, CostConfirmation.parse(""))
            assertEquals(CostConfirmation.Verdict.Unclear, CostConfirmation.parse("   "))
        }

        @Test
        fun `an unrelated answer is unclear rather than guessed at`() {
            assertEquals(CostConfirmation.Verdict.Unclear, CostConfirmation.parse("what does that mean"))
            assertEquals(CostConfirmation.Verdict.Unclear, CostConfirmation.parse("blue"))
        }
    }
}
