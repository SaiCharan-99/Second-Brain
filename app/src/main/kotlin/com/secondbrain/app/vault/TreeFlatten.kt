package com.secondbrain.app.vault

import com.secondbrain.model.TreeNode

/**
 * Turns the recursive [TreeNode] the vault returns into the flat, indexable
 * list a `LazyColumn` actually wants, respecting which folders are expanded.
 *
 * A pure function on purpose — no Compose runtime involved — so it is the one
 * piece of the tree pane worth a plain unit test.
 */
object TreeFlatten {

    data class Row(
        val node: TreeNode,
        val depth: Int,
        val hasChildren: Boolean,
        val isExpanded: Boolean,
    )

    /** [root] is the synthetic vault root ("" / "vault"); it never gets its own row. */
    fun flatten(root: TreeNode, expanded: Set<String>): List<Row> {
        val out = mutableListOf<Row>()
        fun walk(node: TreeNode, depth: Int) {
            node.children.forEach { child ->
                val childExpanded = child.path in expanded
                out += Row(child, depth, child.children.isNotEmpty(), childExpanded)
                if (child.children.isNotEmpty() && childExpanded) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }
}
