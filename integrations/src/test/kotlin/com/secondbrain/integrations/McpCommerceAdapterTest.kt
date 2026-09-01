package com.secondbrain.integrations

import com.secondbrain.model.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * D-089's parsing, against the real `structuredContent` bodies captured live
 * from Zepto's MCP server via `:app:zeptoDiscover` — not invented fixtures.
 * `search_products("stainless steel bottle")` and `list_saved_addresses()`
 * are the two calls that actually returned data; the JSON below is copied
 * verbatim (URLs and one product name shortened for line length only, no
 * field ever renamed or restructured).
 *
 * The instance under test needs no real [McpClient]/[McpOAuth] — every
 * function exercised here is a pure `JsonElement -> T` reader.
 */
class McpCommerceAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }
    // Never used for a live call in this file - only compositeId/splitComposite/
    // parseProducts/parseCart/moneyOf/firstAddressId are exercised, all pure.
    private val adapter = McpCommerceAdapter(
        client = McpClient(endpoint = "https://example.invalid/mcp", tokenProvider = { null }),
        oauth = McpOAuth(
            resourceUrl = "https://example.invalid/mcp",
            tokenStore = TokenStore(java.nio.file.Files.createTempFile("mcp-adapter-test", ".db")),
        ),
        deviceId = "test-device",
    )

    // ── composite ids ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a product/line id round-trips through pvid|spid unchanged")
    fun `composite id round trip`() {
        val id = adapter.compositeId("3d7a3a74-7f0b-4bdb-b455-39a671e34fad", "4516e033-d0c6-500d-90a2-25da89d6b1e9")
        assertEquals("3d7a3a74-7f0b-4bdb-b455-39a671e34fad|4516e033-d0c6-500d-90a2-25da89d6b1e9", id)

        val (pvid, spid) = adapter.splitComposite(id)!!
        assertEquals("3d7a3a74-7f0b-4bdb-b455-39a671e34fad", pvid)
        assertEquals("4516e033-d0c6-500d-90a2-25da89d6b1e9", spid)
    }

    @Test
    fun `a malformed composite id is rejected rather than silently split wrong`() {
        assertNull(adapter.splitComposite("not-composite"))
        assertNull(adapter.splitComposite("only-one|"))
        assertNull(adapter.splitComposite("|only-two"))
    }

    // ── moneyOf: the D-089 fix itself ───────────────────────────────────────

    @Test
    @DisplayName("D-089: price is a raw integer already in paise, never string-parsed")
    fun `money reads a raw paise integer`() {
        val obj = json.parseToJsonElement("""{"price": 29900, "mrp": 64900}""") as JsonObject
        assertEquals(Money.ofRupees(299), adapter.moneyOf(obj, "price"))
        assertEquals(Money.ofRupees(649), adapter.moneyOf(obj, "mrp"))
    }

    @Test
    fun `a null or missing price field returns null, never zero`() {
        val obj = json.parseToJsonElement("""{"price": null}""") as JsonObject
        assertNull(adapter.moneyOf(obj, "price"))
        assertNull(adapter.moneyOf(JsonObject(emptyMap()), "price"))
    }

    // ── search_products: the exact captured response ────────────────────────

    private val realSearchResponse = json.parseToJsonElement(
        """
        {
          "products": [
            {
              "id": "3d7a3a74-7f0b-4bdb-b455-39a671e34fad",
              "productVariantId": "3d7a3a74-7f0b-4bdb-b455-39a671e34fad",
              "storeProductId": "4516e033-d0c6-500d-90a2-25da89d6b1e9",
              "cartProductId": "3d7a3a74-7f0b-4bdb-b455-39a671e34fad",
              "name": "Go24 Pexpo Craft Pro Stainless Steel Sports Bottle",
              "price": 29900,
              "mrp": 64900,
              "imageUrl": "https://cdn.zeptonow.com/x.jpg",
              "packSize": "1 pc",
              "availableQuantity": 3,
              "isAd": false,
              "variantId": "3d7a3a74-7f0b-4bdb-b455-39a671e34fad"
            },
            {
              "id": "355ae811-712c-43b2-a862-4c524d610683",
              "productVariantId": "355ae811-712c-43b2-a862-4c524d610683",
              "storeProductId": "e9243fa0-fcdd-4a64-9782-45a373479481",
              "cartProductId": "355ae811-712c-43b2-a862-4c524d610683",
              "name": "Steelo Plastic Samicas Bottle",
              "price": 9100,
              "mrp": 13100,
              "packSize": "1 L",
              "availableQuantity": 1,
              "isAd": false
            }
          ],
          "query": "stainless steel bottle",
          "totalCount": 10
        }
        """.trimIndent()
    )

    @Test
    @DisplayName("D-089: search_products' real structuredContent parses to Product list")
    fun `parses the real search response`() {
        val products = adapter.parseProducts(realSearchResponse)

        assertEquals(2, products.size)
        val first = products[0]
        assertEquals("Go24 Pexpo Craft Pro Stainless Steel Sports Bottle", first.name)
        assertEquals("1 pc", first.size)
        assertEquals(Money.ofRupees(299), first.price)
        assertTrue(first.available, "availableQuantity=3 must read as available")
        assertEquals(
            "3d7a3a74-7f0b-4bdb-b455-39a671e34fad|4516e033-d0c6-500d-90a2-25da89d6b1e9",
            first.id,
            "id must be the pvid|spid composite, not either alone",
        )
    }

    @Test
    @DisplayName("availableQuantity 0 (or absent) does not silently read as in stock")
    fun `out of stock is read correctly`() {
        val payload = json.parseToJsonElement(
            """{"products":[{"productVariantId":"a","storeProductId":"b","name":"X","price":100,"availableQuantity":0}]}"""
        )
        assertFalse(adapter.parseProducts(payload).single().available)
    }

    @Test
    fun `a product missing a price is dropped, never shown as free`() {
        val payload = json.parseToJsonElement(
            """{"products":[{"productVariantId":"a","storeProductId":"b","name":"X"}]}"""
        )
        assertTrue(adapter.parseProducts(payload).isEmpty())
    }

    // ── view_cart: the exact captured empty-cart response ───────────────────

    @Test
    @DisplayName("D-089: view_cart's real empty-cart structuredContent parses cleanly")
    fun `parses the real empty cart response`() {
        val payload = json.parseToJsonElement("""{"items": [], "isEmpty": true, "totalItems": 0}""")
        val cart = adapter.parseCart(payload)
        assertTrue(cart.isEmpty)
        assertEquals(0, cart.itemCount)
    }

    @Test
    @DisplayName("a populated cart line (inferred shape) still parses with the confirmed field names")
    fun `parses an inferred populated cart line`() {
        val payload = json.parseToJsonElement(
            """
            {"items": [{
                "productVariantId": "3d7a3a74-7f0b-4bdb-b455-39a671e34fad",
                "storeProductId": "4516e033-d0c6-500d-90a2-25da89d6b1e9",
                "name": "Go24 Bottle",
                "packSize": "1 pc",
                "price": 29900,
                "quantity": 2
            }]}
            """.trimIndent()
        )
        val cart = adapter.parseCart(payload)
        val line = cart.lines.single()
        assertEquals("Go24 Bottle", line.name)
        assertEquals(2, line.quantity)
        assertEquals(Money.ofRupees(299), line.unitPrice)
        assertEquals(Money.ofRupees(598), line.lineTotal)
        assertEquals(
            "3d7a3a74-7f0b-4bdb-b455-39a671e34fad|4516e033-d0c6-500d-90a2-25da89d6b1e9",
            line.lineId,
        )
    }

    // ── list_saved_addresses: the exact captured response ───────────────────

    @Test
    @DisplayName("D-089: list_saved_addresses' real structuredContent yields the first address id")
    fun `parses the real address list response`() {
        val payload = json.parseToJsonElement(
            """
            {
              "summary": "Found 2 saved address(es)",
              "addresses": [
                {"id": "1a997a6b-2064-4f10-a26b-511013dd3953", "label": "Home",
                 "addressLine": "501, Domlur, Bengaluru", "latitude": 12.9578, "longitude": 77.6410},
                {"id": "5a26d13f-e9db-404b-904f-a99f90ba60ef", "label": "Other",
                 "addressLine": "107, Brookefield, Bengaluru", "latitude": 12.9696, "longitude": 77.7123}
              ],
              "count": 2
            }
            """.trimIndent()
        )
        assertEquals("1a997a6b-2064-4f10-a26b-511013dd3953", adapter.firstAddressId(payload))
    }

    @Test
    fun `no saved addresses returns null rather than a fabricated id`() {
        val payload = json.parseToJsonElement("""{"addresses": [], "count": 0}""")
        assertNull(adapter.firstAddressId(payload))
    }

    // ── D-090: bestMatch, against the real 23-tool name set ─────────────────

    /**
     * The real tool names Zepto's `tools/list` returned (D-089's discovery
     * run), name only — [bestMatch] matches on name and description, and the
     * names alone are what D-090's bug actually turned on. Descriptions
     * omitted for brevity; every test below that needs one passes it inline.
     */
    private fun tool(name: String, description: String? = null) =
        McpClient.McpTool(name, description, null)

    private val realToolNames = listOf(
        "zepto_shop", "search_products", "search_multiple_products", "get_product_details",
        "get_location_serviceability", "select_store", "list_saved_addresses",
        "select_saved_address", "add_saved_address", "update_drop_zone", "get_user_details",
        "update_user_name", "view_cart", "update_cart", "get_payment_methods", "create_order",
        "create_online_payment_order", "create_wallet_order", "create_upi_reserve_pay_order",
        "check_payment_status", "get_order_detail", "list_order_history", "get_past_order_items",
    ).map { tool(it) }

    @Test
    @DisplayName("D-090: list_saved_addresses binds to ADDRESS_LIST, not left unbound by 'add' inside 'address'")
    fun `address list binds correctly`() {
        assertEquals("list_saved_addresses", adapter.bestMatch(McpCommerceAdapter.Role.ADDRESS_LIST, realToolNames))
    }

    @Test
    @DisplayName("D-090: select_saved_address binds to ADDRESS_SELECT, not left unbound by 'add' inside 'address'")
    fun `address select binds correctly`() {
        assertEquals("select_saved_address", adapter.bestMatch(McpCommerceAdapter.Role.ADDRESS_SELECT, realToolNames))
    }

    @Test
    fun `add_saved_address itself never wins either address role`() {
        assertTrue("add_saved_address" != adapter.bestMatch(McpCommerceAdapter.Role.ADDRESS_LIST, realToolNames))
        assertTrue("add_saved_address" != adapter.bestMatch(McpCommerceAdapter.Role.ADDRESS_SELECT, realToolNames))
    }

    @Test
    @DisplayName("every other D-089 binding still holds against the real name set")
    fun `the rest of the real bindings are unchanged`() {
        assertEquals("search_products", adapter.bestMatch(McpCommerceAdapter.Role.SEARCH, realToolNames))
        assertEquals("view_cart", adapter.bestMatch(McpCommerceAdapter.Role.CART_VIEW, realToolNames))
        assertEquals("update_cart", adapter.bestMatch(McpCommerceAdapter.Role.CART_WRITE, realToolNames))
        assertEquals("create_order", adapter.bestMatch(McpCommerceAdapter.Role.ORDER_PLACE, realToolNames))
    }

    @Test
    @DisplayName("every role in Role.entries binds to something against the real 23 tools")
    fun `no role is silently unbound against the real tool set`() {
        McpCommerceAdapter.Role.entries.forEach { role ->
            assertTrue(
                adapter.bestMatch(role, realToolNames) != null,
                "$role bound to nothing - this is exactly D-090's failure mode",
            )
        }
    }
}
