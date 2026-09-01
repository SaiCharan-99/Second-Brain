package com.secondbrain.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parsing half of [McpClient], which is the half that can be tested without
 * a live authenticated session.
 *
 * Streamable HTTP lets a server answer the same request as either plain JSON or
 * an SSE stream, and a client that handles only one of them works right up
 * until the day the server changes its mind. Both shapes are pinned here.
 */
class McpClientTest {

    private val client = McpClient(endpoint = "https://example.invalid/mcp", tokenProvider = { null })

    // ── plain JSON ──────────────────────────────────────────────────────────

    @Test
    fun `a plain JSON body is parsed directly`() {
        val message = checkNotNull(client.extractJsonRpcMessage("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""))
        assertTrue(message.containsKey("result"))
    }

    @Test
    fun `whitespace around a JSON body does not defeat it`() {
        assertNotNull(client.extractJsonRpcMessage("\n  {\"jsonrpc\":\"2.0\",\"result\":{}}  \n"))
    }

    // ── SSE ─────────────────────────────────────────────────────────────────

    @Test
    fun `an SSE framed response is unwrapped from its data line`() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"ok":true}}

        """.trimIndent()

        val message = checkNotNull(client.extractJsonRpcMessage(body))
        assertEquals("true", (message["result"] as JsonObject)["ok"]?.jsonPrimitive?.content)
    }

    /** The SSE spec joins consecutive data lines with newlines before parsing. */
    @Test
    fun `data split across several lines is rejoined`() {
        val body = """
            data: {"jsonrpc":"2.0",
            data: "id":1,
            data: "result":{"ok":true}}

        """.trimIndent()

        assertNotNull(client.extractJsonRpcMessage(body))
    }

    @Test
    fun `comments and non-data fields are skipped`() {
        val body = """
            : keep-alive ping
            id: 42
            retry: 3000
            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"ok":true}}

        """.trimIndent()

        assertNotNull(client.extractJsonRpcMessage(body))
    }

    /**
     * A server may stream progress notifications before the real answer. Those
     * have no `result`/`error`, and picking the first event rather than the
     * response would silently return the wrong thing.
     */
    @Test
    fun `a progress notification before the response is ignored in favour of the response`() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":0.5}}

            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"final":true}}

        """.trimIndent()

        val message = checkNotNull(client.extractJsonRpcMessage(body))
        assertEquals("true", (message["result"] as JsonObject)["final"]?.jsonPrimitive?.content)
    }

    @Test
    fun `carriage returns from a CRLF stream are stripped`() {
        val body = "event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\r\n\r\n"
        assertNotNull(client.extractJsonRpcMessage(body))
    }

    @Test
    fun `an error response is returned rather than treated as unparseable`() {
        val body = """
            data: {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params"}}

        """.trimIndent()

        val message = checkNotNull(client.extractJsonRpcMessage(body))
        assertTrue(message.containsKey("error"))
    }

    @Test
    fun `garbage and empty bodies return null rather than throwing`() {
        assertNull(client.extractJsonRpcMessage(""))
        assertNull(client.extractJsonRpcMessage("   "))
        assertNull(client.extractJsonRpcMessage("<html>503 Service Unavailable</html>"))
        assertNull(client.extractJsonRpcMessage("data: not json at all\n\n"))
    }

    // ── tool result helpers ─────────────────────────────────────────────────

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `flattenContent joins the text blocks`() {
        val result = obj("""{"content":[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]}""")
        assertEquals("line one\nline two", McpClient.flattenContent(result))
    }

    @Test
    fun `flattenContent ignores non-text blocks`() {
        val result = obj("""{"content":[{"type":"image","data":"..."},{"type":"text","text":"only this"}]}""")
        assertEquals("only this", McpClient.flattenContent(result))
    }

    @Test
    fun `flattenContent falls back to the whole object when there is no content array`() {
        val result = obj("""{"orderId":"AB-1"}""")
        assertTrue(McpClient.flattenContent(result).contains("AB-1"))
    }

    /**
     * MCP reports a *tool* failure inside a successful JSON-RPC response.
     * Conflating that with an RPC error would turn "out of stock" into a
     * transport problem, and the adapter's retry semantics differ sharply
     * between the two (EC-Z18).
     */
    @Test
    fun `isToolError reads the flag rather than assuming success`() {
        assertTrue(McpClient.isToolError(obj("""{"isError":true,"content":[]}""")))
        assertFalse(McpClient.isToolError(obj("""{"isError":false,"content":[]}""")))
        assertFalse(McpClient.isToolError(obj("""{"content":[]}""")))
    }

    @Test
    fun `structuredContent is surfaced when the server provides it`() {
        assertNotNull(McpClient.structuredContent(obj("""{"structuredContent":{"cart":{}}}""")))
        assertNull(McpClient.structuredContent(obj("""{"content":[]}""")))
    }
}
