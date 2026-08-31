package com.secondbrain.vault

import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.VaultConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * EC-N6 / D-007. The four cases named in the Step 2 exit criteria are marked.
 */
class FolderGuardTest {

    private val config = VaultConfig()
    private val guard = FolderGuard(config)

    private fun reject(proposed: String, existing: Set<String>): FolderVerdict.Rejected =
        assertInstanceOf(FolderVerdict.Rejected::class.java, guard.evaluate(proposed, existing))

    private fun accept(proposed: String, existing: Set<String>): FolderVerdict.Accepted =
        assertInstanceOf(FolderVerdict.Accepted::class.java, guard.evaluate(proposed, existing))

    @Nested
    @DisplayName("Step 2 exit criteria")
    inner class ExitCriteria {

        @Test
        @DisplayName("'Project' vs 'Projects' rejects")
        fun `project vs projects`() {
            val r = reject("Project", setOf("Projects", "Inbox"))
            assertEquals(FolderVerdict.RejectReason.SIMILAR, r.reason)
            assertEquals("Projects", r.useInstead)
            assertTrue(r.score!! >= config.folderSimilarityThreshold, "score was ${r.score}")
        }

        @Test
        @DisplayName("'Recipes' vs 'Architecture' accepts")
        fun `recipes vs architecture`() {
            accept("Recipes", setOf("Architecture", "Inbox"))
        }

        @Test
        @DisplayName("depth 4 rejects")
        fun `depth four`() {
            val existing = setOf("Projects", "Projects/Second Brain", "Projects/Second Brain/Voice")
            val r = reject("Projects/Second Brain/Voice/Capture", existing)
            assertEquals(FolderVerdict.RejectReason.DEPTH, r.reason)
            assertTrue(r.detail.contains("depth 4"), r.detail)
        }

        @Test
        @DisplayName("the 13th top-level folder rejects")
        fun `thirteenth top level`() {
            val twelve = (1..12).map { "Topic$it" }.toSet()
            val r = reject("Something Else", twelve)
            assertEquals(FolderVerdict.RejectReason.CAP, r.reason)
            assertTrue(r.detail.contains("12"), r.detail)
        }
    }

    @Nested
    @DisplayName("similarity scoring")
    inner class Scoring {

        @Test
        fun `identical names score 1`() {
            assertEquals(1.0, guard.score("Reading", "Reading"), 1e-9)
        }

        @Test
        fun `singular and plural of the same word score above the threshold`() {
            listOf(
                "Recipe" to "Recipes",
                "Note" to "Notes",
                "Idea" to "Ideas",
                "Person" to "People",
            ).forEach { (a, b) ->
                val s = guard.score(a, b)
                assertTrue(s >= config.folderSimilarityThreshold, "$a vs $b scored $s")
            }
        }

        @Test
        fun `unrelated names score well below the threshold`() {
            listOf(
                "Recipes" to "Architecture",
                "Groceries" to "Positioning",
                "People" to "Reading",
                "Inbox" to "Competition",
            ).forEach { (a, b) ->
                val s = guard.score(a, b)
                assertTrue(s < config.folderSimilarityThreshold, "$a vs $b scored $s")
            }
        }

        @Test
        @DisplayName("F23: single-word comparisons cluster at the extremes, not near 0.72")
        fun `threshold gap is real`() {
            val same = guard.score("Project", "Projects")
            val different = guard.score("Recipes", "Architecture")
            assertTrue(same > 0.9, "same-concept scored $same, expected > 0.9")
            assertTrue(different < 0.45, "different-concept scored $different, expected < 0.45")
        }

        @Test
        @DisplayName("multi-word names are where the threshold actually does work")
        fun `multi word`() {
            val s = guard.score("Second Brain UI", "Second Brain")
            assertTrue(s > 0.5, "expected a middling score, got $s")
        }

        @Test
        fun `scoring compares the leaf, so a nested duplicate is caught`() {
            // Projects/Reading vs a top-level Reading must be caught: diluting the
            // score with the differing prefix is how sprawl gets through.
            val r = reject("Projects/Reading", setOf("Projects", "Reading"))
            assertEquals(FolderVerdict.RejectReason.SIMILAR, r.reason)
            assertEquals("Reading", r.useInstead)
        }
    }

    @Nested
    @DisplayName("D-031 conservative singulariser")
    inner class Singulariser {

        @Test
        fun `strips a safe trailing s`() {
            assertEquals("recipe", guard.singularise("recipes"))
            assertEquals("note", guard.singularise("notes"))
            assertEquals("project", guard.singularise("projects"))
        }

        @Test
        @DisplayName("does not mangle words a Porter stemmer would")
        fun `no over-stemming`() {
            // These are the cases that make a real stemmer the wrong tool here.
            assertEquals("person", guard.singularise("people"))
            assertEquals("analysis", guard.singularise("analysis"))
            assertEquals("thesis", guard.singularise("thesis"))
            assertEquals("status", guard.singularise("status"))
            assertEquals("business", guard.singularise("business"))
            assertEquals("positioning", guard.singularise("positioning"))
            assertEquals("idea", guard.singularise("idea"))  // unchanged, no trailing s
        }

        @Test
        fun `handles -ies and -shes`() {
            assertEquals("story", guard.singularise("stories"))
            assertEquals("dish", guard.singularise("dishes"))
            assertEquals("box", guard.singularise("boxes"))
        }

        @Test
        fun `leaves short words alone`() {
            assertEquals("os", guard.singularise("os"))
            assertEquals("gas", guard.singularise("gas"))
        }
    }

    @Nested
    @DisplayName("structural rules")
    inner class Structure {

        @Test
        fun `an existing folder is accepted rather than rejected`() {
            val v = accept("Projects", setOf("Projects", "Inbox"))
            assertEquals("Projects", v.path)
        }

        @Test
        fun `case-insensitive match to an existing folder is accepted as that folder`() {
            // On this filesystem "projects" and "Projects" are one directory, so
            // returning the existing casing keeps index and disk in agreement (F7).
            val v = accept("projects", setOf("Projects"))
            assertEquals("Projects", v.path)
        }

        @Test
        fun `a missing parent is rejected as INVALID, not silently created`() {
            val r = reject("Projects/Nested/Deep", setOf("Projects"))
            assertEquals(FolderVerdict.RejectReason.INVALID, r.reason)
            assertTrue(r.detail.contains("Projects/Nested"), r.detail)
        }

        @Test
        fun `a valid nested folder under an existing parent is accepted`() {
            accept("Projects/Positioning", setOf("Projects", "Inbox"))
        }

        @Test
        fun `depth 3 is allowed, depth 4 is not`() {
            val existing = setOf("A", "A/B", "A/B/C")
            accept("A/B/Cee", existing)
            assertEquals(FolderVerdict.RejectReason.DEPTH, reject("A/B/C/D", existing).reason)
        }

        @Test
        fun `the cap applies only to new top-level folders`() {
            val twelve = (1..12).map { "Topic$it" }.toSet() + setOf("Topic1/Nested")
            // A 13th top-level is refused...
            assertEquals(FolderVerdict.RejectReason.CAP, reject("Brand New", twelve).reason)
            // ...but nesting is still fine, which is what the CAP message tells the model to do.
            accept("Topic1/Another", twelve)
        }

        @Test
        fun `garbage input is rejected as INVALID`() {
            listOf("", "   ", "///", "...", "???").forEach { input ->
                val verdict = guard.evaluate(input, setOf("Inbox"))
                assertInstanceOf(
                    FolderVerdict.Rejected::class.java, verdict,
                    "expected rejection for '$input'",
                )
            }
        }

        @Test
        fun `an empty vault accepts the first folder`() {
            accept("Projects", emptySet())
        }
    }

    @Nested
    @DisplayName("R7 thresholds are config, not code")
    inner class Configurable {

        @Test
        @DisplayName("the threshold is honoured, in the multi-word range where it can move")
        fun `threshold is tunable where it matters`() {
            // "Second Brain UI" vs "Second Brain" scores ~0.74, one of the few
            // comparisons that lands near 0.72 at all. At the default it is
            // rejected as sprawl; raise the bar and it is allowed through.
            val existing = setOf("Projects", "Projects/Second Brain")
            val proposed = "Projects/Second Brain UI"

            assertInstanceOf(
                FolderVerdict.Rejected::class.java,
                FolderGuard(config).evaluate(proposed, existing),
                "at the default 0.72 this should be redirected to the existing folder",
            )
            assertInstanceOf(
                FolderVerdict.Accepted::class.java,
                FolderGuard(config.copy(folderSimilarityThreshold = 0.80)).evaluate(proposed, existing),
                "at 0.80 it should be allowed as its own folder",
            )
        }

        @Test
        @DisplayName("F23: single-word pairs cannot be separated by ANY threshold in 0.45..0.90")
        fun `threshold cannot move single word cases`() {
            // Evidence for the note in VaultConfig: there is no threshold in this
            // range that changes either verdict, so tuning it in Step 3 will not
            // affect single-word folder names. Do not waste time trying.
            listOf(0.45, 0.60, 0.72, 0.85, 0.90).forEach { threshold ->
                val g = FolderGuard(config.copy(folderSimilarityThreshold = threshold))
                assertInstanceOf(
                    FolderVerdict.Rejected::class.java, g.evaluate("Project", setOf("Projects")),
                    "Project/Projects should reject at threshold $threshold",
                )
                assertInstanceOf(
                    FolderVerdict.Accepted::class.java, g.evaluate("Recipes", setOf("Architecture")),
                    "Recipes/Architecture should accept at threshold $threshold",
                )
            }
        }

        @Test
        fun `raising the cap allows more top-level folders`() {
            val roomy = FolderGuard(config.copy(maxTopLevelFolders = 20))
            val twelve = (1..12).map { "Topic$it" }.toSet()
            assertInstanceOf(
                FolderVerdict.Accepted::class.java,
                roomy.evaluate("Thirteenth", twelve),
            )
        }

        @Test
        fun `raising max depth allows deeper nesting`() {
            val deep = FolderGuard(config.copy(maxFolderDepth = 4))
            val existing = setOf("A", "A/B", "A/B/C")
            assertInstanceOf(
                FolderVerdict.Accepted::class.java,
                deep.evaluate("A/B/C/D", existing),
            )
        }
    }
}
