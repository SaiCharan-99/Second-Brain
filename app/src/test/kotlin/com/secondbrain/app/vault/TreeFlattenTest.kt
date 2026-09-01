package com.secondbrain.app.vault

import com.secondbrain.model.TreeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TreeFlattenTest {

    /** F10's own fixture shape: Projects(23) = Positioning(9) + Demo(7) + Notes(7). */
    private fun fixture(): TreeNode = TreeNode(
        path = "", name = "vault", depth = 0, directNoteCount = 0, rollupNoteCount = 23, danglingCount = 1,
        children = listOf(
            TreeNode(
                path = "Projects", name = "Projects", depth = 1, directNoteCount = 0, rollupNoteCount = 23, danglingCount = 1,
                children = listOf(
                    TreeNode("Projects/Positioning", "Positioning", 2, 9, 9, 1),
                    TreeNode("Projects/Demo", "Demo", 2, 7, 7, 0),
                    TreeNode("Projects/Notes", "Notes", 2, 7, 7, 0),
                ),
            ),
            TreeNode(path = "Inbox", name = "Inbox", depth = 1, directNoteCount = 2, rollupNoteCount = 2, danglingCount = 0),
        ),
    )

    @Test
    fun `nothing expanded shows only top-level folders`() {
        val rows = TreeFlatten.flatten(fixture(), expanded = emptySet())
        assertEquals(listOf("Projects", "Inbox"), rows.map { it.node.name })
        assertTrue(rows.all { it.depth == 0 })
    }

    @Test
    fun `the vault root itself never gets a row`() {
        val rows = TreeFlatten.flatten(fixture(), expanded = setOf("Projects"))
        assertTrue(rows.none { it.node.path == "" })
    }

    @Test
    fun `expanding a folder reveals its children at depth plus one`() {
        val rows = TreeFlatten.flatten(fixture(), expanded = setOf("Projects"))
        assertEquals(
            listOf("Projects" to 0, "Positioning" to 1, "Demo" to 1, "Notes" to 1, "Inbox" to 0),
            rows.map { it.node.name to it.depth },
        )
    }

    @Test
    fun `a leaf folder reports no children rather than a spurious chevron`() {
        val rows = TreeFlatten.flatten(fixture(), expanded = emptySet())
        val inbox = rows.first { it.node.name == "Inbox" }
        assertEquals(false, inbox.hasChildren)
    }

    @Test
    fun `a folder with children not yet expanded is collapsed`() {
        val rows = TreeFlatten.flatten(fixture(), expanded = emptySet())
        val projects = rows.first { it.node.name == "Projects" }
        assertTrue(projects.hasChildren)
        assertEquals(false, projects.isExpanded)
    }

    @Test
    fun `an expanded leaf-only set still leaves collapsed branches unexpanded`() {
        // Expanding a path that names a leaf (no children) must not crash and
        // must not surface anything extra.
        val rows = TreeFlatten.flatten(fixture(), expanded = setOf("Inbox"))
        assertEquals(listOf("Projects", "Inbox"), rows.map { it.node.name })
    }
}
