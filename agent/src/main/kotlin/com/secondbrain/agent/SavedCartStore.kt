package com.secondbrain.agent

import com.secondbrain.model.Money
import com.secondbrain.model.SavedItem
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.Instant

/**
 * Stage 4 (D-098): CRUD over `saved_cart_lines` (`AgentDb` migration 3) — the
 * persistent list Stage 4's comparison-table UI writes to when the user picks
 * a candidate, and Stage 5's checkout bridge reads from.
 *
 * Deliberately the plainest possible store: no session/request/candidate
 * tables, because nothing downstream needs *why* a line was saved to survive
 * a restart, only *what was decided* — see [SavedItem]'s own doc for the
 * full reasoning on why this is one table instead of the pasted plan's five.
 */
class SavedCartStore(private val db: AgentDb) {

    private val log = LoggerFactory.getLogger(SavedCartStore::class.java)

    fun add(item: SavedItem): SavedItem {
        val id = db.connection.prepareStatement(
            """
            INSERT INTO saved_cart_lines(product_id, name, size, unit_price_paise, quantity, image_url, source_query, saved_at)
            VALUES (?,?,?,?,?,?,?,?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, item.productId)
            ps.setString(2, item.name)
            if (item.size == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, item.size)
            ps.setLong(4, item.unitPrice.paise)
            ps.setInt(5, item.quantity)
            if (item.imageUrl == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, item.imageUrl)
            ps.setString(7, item.sourceQuery)
            ps.setString(8, item.savedAt.toString())
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
        log.info("Saved cart: +{} x{} ({})", item.name, item.quantity, item.sourceQuery)
        return item.copy(id = id)
    }

    fun updateQuantity(id: Long, quantity: Int) {
        if (quantity <= 0) { remove(id); return }
        db.connection.prepareStatement("UPDATE saved_cart_lines SET quantity = ? WHERE id = ?").use { ps ->
            ps.setInt(1, quantity)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    fun remove(id: Long) {
        db.connection.prepareStatement("DELETE FROM saved_cart_lines WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    /** Removes several at once — the "checkout selected" action clears exactly what it just added to the live cart, nothing else. */
    fun removeAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.connection.prepareStatement("DELETE FROM saved_cart_lines WHERE id = ?").use { ps ->
            for (id in ids) {
                ps.setLong(1, id)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun all(): List<SavedItem> {
        val out = mutableListOf<SavedItem>()
        db.connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT id, product_id, name, size, unit_price_paise, quantity, image_url, source_query, saved_at " +
                    "FROM saved_cart_lines ORDER BY saved_at DESC"
            ).use { rs -> while (rs.next()) out += rowOf(rs) }
        }
        return out
    }

    private fun rowOf(rs: ResultSet) = SavedItem(
        id = rs.getLong("id"),
        productId = rs.getString("product_id"),
        name = rs.getString("name"),
        size = rs.getString("size"),
        unitPrice = Money.ofPaise(rs.getLong("unit_price_paise")),
        quantity = rs.getInt("quantity"),
        imageUrl = rs.getString("image_url"),
        sourceQuery = rs.getString("source_query"),
        savedAt = Instant.parse(rs.getString("saved_at")),
    )
}
