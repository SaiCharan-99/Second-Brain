package com.secondbrain.agent

import com.secondbrain.model.Money
import com.secondbrain.model.SavedItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/** Stage 4 (D-098): `AgentDb` migration 3, exercised through [SavedCartStore]'s CRUD. */
class SavedCartStoreTest {

    private val openDatabases = mutableListOf<AgentDb>()

    /** Same reasoning as `CommerceToolsTest`'s own doc: an open SQLite connection keeps app.db locked on Windows past a passing assertion. */
    @AfterEach
    fun closeDatabases() {
        openDatabases.forEach { runCatching { it.close() } }
        openDatabases.clear()
    }

    private fun newStore(dir: Path): SavedCartStore {
        val db = AgentDb(dir.resolve("app.db"))
        openDatabases += db
        return SavedCartStore(db)
    }

    private fun item(name: String = "Brown Bread", qty: Int = 1, query: String = "bread") = SavedItem(
        productId = "pv1|sp1",
        name = name,
        size = "400 g",
        unitPrice = Money.ofRupees(45),
        quantity = qty,
        imageUrl = "https://cdn.zeptonow.com/x.jpg",
        sourceQuery = query,
        savedAt = Instant.now(),
    )

    @Test
    @DisplayName("a saved line survives a fresh AgentDb connection against the same file - R10, app.db is precious")
    fun `saved lines persist across connections`(@TempDir dir: Path) {
        val file = dir.resolve("app.db")
        val db1 = AgentDb(file)
        SavedCartStore(db1).add(item())
        db1.close() // closed explicitly (not left for @AfterEach) - the second connection below must open cleanly, not just eventually.

        // A second, independent connection - not the same store instance - is
        // the actual thing R10 promises: what was written survives a restart.
        val db2 = AgentDb(file)
        openDatabases += db2
        val reopened = SavedCartStore(db2).all()

        assertEquals(1, reopened.size)
        assertEquals("Brown Bread", reopened.single().name)
        assertEquals(Money.ofRupees(45), reopened.single().unitPrice)
    }

    @Test
    fun `add assigns a generated id`(@TempDir dir: Path) {
        val store = newStore(dir)
        val saved = store.add(item())
        assertTrue(saved.id > 0)
    }

    @Test
    fun `all orders newest first`(@TempDir dir: Path) {
        val store = newStore(dir)
        store.add(item("First", query = "a").copy(savedAt = Instant.parse("2026-01-01T00:00:00Z")))
        store.add(item("Second", query = "b").copy(savedAt = Instant.parse("2026-01-02T00:00:00Z")))

        assertEquals(listOf("Second", "First"), store.all().map { it.name })
    }

    @Test
    fun `updateQuantity changes the line`(@TempDir dir: Path) {
        val store = newStore(dir)
        val saved = store.add(item(qty = 1))

        store.updateQuantity(saved.id, 4)

        assertEquals(4, store.all().single().quantity)
    }

    @Test
    @DisplayName("mirrors CartMutation's own rule: a quantity of 0 removes the line entirely")
    fun `updateQuantity to zero removes the line`(@TempDir dir: Path) {
        val store = newStore(dir)
        val saved = store.add(item())

        store.updateQuantity(saved.id, 0)

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `remove deletes exactly one line`(@TempDir dir: Path) {
        val store = newStore(dir)
        val keep = store.add(item("Keep"))
        val drop = store.add(item("Drop", query = "x"))

        store.remove(drop.id)

        assertEquals(listOf("Keep"), store.all().map { it.name })
        assertNotNull(keep)
    }

    @Test
    @DisplayName("Stage 5's checkout bridge clears exactly the applied lines, nothing else")
    fun `removeAll clears only the given ids`(@TempDir dir: Path) {
        val store = newStore(dir)
        val a = store.add(item("A", query = "a"))
        val b = store.add(item("B", query = "b"))
        val c = store.add(item("C", query = "c"))

        store.removeAll(listOf(a.id, c.id))

        assertEquals(listOf("B"), store.all().map { it.name })
    }

    @Test
    fun `removeAll with an empty collection is a no-op`(@TempDir dir: Path) {
        val store = newStore(dir)
        store.add(item())

        store.removeAll(emptyList())

        assertEquals(1, store.all().size)
    }
}
