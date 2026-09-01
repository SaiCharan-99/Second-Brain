package com.secondbrain.integrations

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * A minimal MCP client: JSON-RPC 2.0 over the Streamable HTTP transport.
 *
 * ARCHITECTURE §7 Step 7 sanctions exactly this: *"Prefer the official Kotlin
 * MCP SDK if it fits; otherwise a hand-rolled JSON-RPC 2.0 client over the
 * server's transport. MCP is JSON-RPC; a minimal client is roughly 300 lines
 * and removes a dependency risk on a deadline."* Hand-rolled won here for a
 * reason the doc anticipates and D-044 states in the opposite direction for
 * Anthropic: there is no typed surface to drift. Every payload on this wire is
 * `JsonObject` either way, so an SDK would buy marshalling we do not need while
 * adding a version to track.
 *
 * ### What "streamable HTTP" actually requires, measured
 *
 * Spike S7.1 against `https://mcp.zepto.co.in/mcp` (D-079):
 *
 * - One `POST` per request. `Accept` must list **both** `application/json` and
 *   `text/event-stream` — a server may answer either, and the same server may
 *   answer differently per method.
 * - `initialize` first. The server returns an `Mcp-Session-Id` header which
 *   every later request must echo, and a `notifications/initialized` must be
 *   sent before any real call.
 * - Auth is a bearer token, and a `401` carries `WWW-Authenticate` with the
 *   RFC 9728 resource-metadata URL. That is not a failure to retry, it is the
 *   discovery entry point — see [McpOAuth].
 *
 * ### What this class does NOT do
 *
 * No server-initiated requests, no sampling, no resource subscriptions. This is
 * a tool-calling client; those capabilities are not advertised in [initialize],
 * so a compliant server will not use them.
 */
class McpClient(
    private val endpoint: String,
    /**
     * Supplies a valid bearer token, refreshing it if needed. Returning null
     * means "not signed in" and produces [McpError.Unauthorized] rather than an
     * unauthenticated request the server would reject anyway.
     */
    private val tokenProvider: suspend () -> String?,
    private val requestTimeoutMs: Long = 30_000,
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    },
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(McpClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val nextId = AtomicLong(1)

    /**
     * Guards the initialize handshake only.
     *
     * Concurrent tool calls are fine and are not serialised — but two of them
     * racing to initialize would produce two sessions and silently strand one
     * of them, so the handshake is once-only.
     */
    private val initMutex = Mutex()

    @Volatile private var sessionId: String? = null
    @Volatile private var initialized = false
    @Volatile private var negotiatedProtocol: String = PROTOCOL_VERSION

    /** Everything that can go wrong on this wire, as values rather than exceptions. */
    sealed interface McpError {
        /** 401/403. The caller's move is re-auth, never a retry. */
        data class Unauthorized(val message: String, val resourceMetadataUrl: String?) : McpError

        /** A JSON-RPC `error` object. The server understood us and said no. */
        data class Rpc(val code: Int, val message: String) : McpError

        /** Transport: connection refused, timeout, 5xx, unparseable body. */
        data class Transport(val message: String) : McpError

        /**
         * The session was rejected as unknown — the server restarted, or it
         * expired. EC-Z22: recoverable by re-initialising, but the caller must
         * know the cart it was mid-way through building may not be there.
         */
        data class SessionLost(val message: String) : McpError
    }

    sealed interface McpResult<out T> {
        data class Ok<T>(val value: T) : McpResult<T>
        data class Err(val error: McpError) : McpResult<Nothing>
    }

    data class McpTool(
        val name: String,
        val description: String?,
        /** The raw JSON Schema, verbatim. Bridged into an Anthropic tool spec unchanged. */
        val inputSchema: JsonObject?,
    )

    // ── handshake ───────────────────────────────────────────────────────────

    /** Idempotent. Safe to call before every operation; does real work once. */
    suspend fun ensureInitialized(): McpResult<Unit> {
        if (initialized) return McpResult.Ok(Unit)
        return initMutex.withLock {
            if (initialized) return@withLock McpResult.Ok(Unit)

            val params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                })
            }

            when (val result = rpc("initialize", params, allowUninitialized = true)) {
                is McpResult.Err -> result
                is McpResult.Ok -> {
                    negotiatedProtocol = result.value["protocolVersion"]?.jsonPrimitive?.content ?: PROTOCOL_VERSION
                    val server = result.value["serverInfo"]?.jsonObject
                    log.info(
                        "MCP connected: {} {} (protocol {})",
                        server?.get("name")?.jsonPrimitive?.content ?: "unknown server",
                        server?.get("version")?.jsonPrimitive?.content.orEmpty(),
                        negotiatedProtocol,
                    )
                    initialized = true
                    // Required by the spec before any other request. It is a
                    // notification, so there is no response to wait on and a
                    // failure here is not worth failing the handshake over.
                    runCatching { notify("notifications/initialized") }
                        .onFailure { log.debug("initialized notification failed (continuing): {}", it.message) }
                    McpResult.Ok(Unit)
                }
            }
        }
    }

    /** Drops the session so the next call re-handshakes. EC-Z22's recovery path. */
    fun resetSession() {
        initialized = false
        sessionId = null
    }

    // ── the two methods this system actually uses ───────────────────────────

    suspend fun listTools(): McpResult<List<McpTool>> {
        ensureInitialized().let { if (it is McpResult.Err) return it }

        return when (val result = rpc("tools/list", buildJsonObject { })) {
            is McpResult.Err -> result
            is McpResult.Ok -> McpResult.Ok(
                result.value["tools"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    McpTool(
                        name = name,
                        description = obj["description"]?.jsonPrimitive?.content,
                        inputSchema = obj["inputSchema"]?.jsonObject,
                    )
                }
            )
        }
    }

    /**
     * Calls one tool.
     *
     * Returns the raw `result` object. MCP puts tool output in `content` as
     * typed blocks and flags failures with `isError: true` *inside* a
     * successful JSON-RPC response — a tool that failed is not an RPC error,
     * and conflating the two would turn "out of stock" into a transport
     * problem. [flattenContent] does the unwrapping; callers that need the
     * structure keep the object.
     */
    suspend fun callTool(name: String, arguments: JsonObject): McpResult<JsonObject> {
        ensureInitialized().let { if (it is McpResult.Err) return it }

        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        return rpc("tools/call", params)
    }

    // ── JSON-RPC plumbing ───────────────────────────────────────────────────

    private suspend fun rpc(
        method: String,
        params: JsonObject,
        allowUninitialized: Boolean = false,
    ): McpResult<JsonObject> {
        if (!allowUninitialized && !initialized) {
            return McpResult.Err(McpError.Transport("MCP client not initialized"))
        }

        val token = tokenProvider()
            ?: return McpResult.Err(McpError.Unauthorized("Not signed in to the commerce provider.", null))

        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

        val response: HttpResponse = try {
            http.post(endpoint) {
                contentType(ContentType.Application.Json)
                // Both, always. A server may answer either way per method.
                header("Accept", "application/json, text/event-stream")
                header("MCP-Protocol-Version", negotiatedProtocol)
                header("Authorization", "Bearer $token")
                sessionId?.let { header("Mcp-Session-Id", it) }
                setBody(body)
            }
        } catch (e: Exception) {
            return McpResult.Err(McpError.Transport("${e::class.simpleName}: ${e.message}"))
        }

        // The server assigns the session on initialize; capture it before
        // anything else can fail, or every later request is sessionless.
        response.headers["Mcp-Session-Id"]?.let { sessionId = it }

        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            return McpResult.Err(
                McpError.Unauthorized(
                    "Commerce provider rejected the credentials (${response.status.value}).",
                    parseResourceMetadataUrl(response.headers["WWW-Authenticate"]),
                )
            )
        }

        // 404 on a request that carried a session id means that session is
        // gone, not that the endpoint is wrong - the same URL worked a moment
        // ago. EC-Z22.
        if (response.status == HttpStatusCode.NotFound && sessionId != null) {
            resetSession()
            return McpResult.Err(McpError.SessionLost("The commerce session expired."))
        }

        if (!response.status.isSuccess()) {
            return McpResult.Err(McpError.Transport("HTTP ${response.status.value} from the commerce server."))
        }

        val text = try {
            response.bodyAsText()
        } catch (e: Exception) {
            return McpResult.Err(McpError.Transport("Could not read the response: ${e.message}"))
        }

        val payload = extractJsonRpcMessage(text)
            ?: return McpResult.Err(McpError.Transport("Malformed response from the commerce server."))

        payload["error"]?.jsonObject?.let { err ->
            return McpResult.Err(
                McpError.Rpc(
                    code = err["code"]?.jsonPrimitive?.int ?: -1,
                    message = err["message"]?.jsonPrimitive?.content ?: "unknown error",
                )
            )
        }

        val result = payload["result"]?.jsonObject
            ?: return McpResult.Err(McpError.Transport("Response had neither a result nor an error."))

        return McpResult.Ok(result)
    }

    /** Fire-and-forget. Notifications have no id and no response. */
    private suspend fun notify(method: String) {
        val token = tokenProvider() ?: return
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", buildJsonObject { })
        }.toString()

        http.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("MCP-Protocol-Version", negotiatedProtocol)
            header("Authorization", "Bearer $token")
            sessionId?.let { header("Mcp-Session-Id", it) }
            setBody(body)
        }
    }

    /**
     * Pulls the JSON-RPC message out of either response shape.
     *
     * Plain `application/json` is the whole body. `text/event-stream` is SSE
     * framing, where what we want sits in `data:` lines — possibly several,
     * which the spec says to join with newlines, and possibly preceded by
     * comments, `event:` and `id:` lines that must be skipped. The last
     * complete message wins: a server may emit progress notifications before
     * the response, and those have no `id`.
     */
    internal fun extractJsonRpcMessage(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{")) {
            return runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        }

        // SSE. Accumulate data lines per event, and keep the last event that
        // parses as a JSON-RPC response rather than a notification.
        var best: JsonObject? = null
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            val candidate = runCatching {
                json.parseToJsonElement(buffer.toString()).jsonObject
            }.getOrNull()
            buffer.setLength(0)
            if (candidate != null && (candidate.containsKey("result") || candidate.containsKey("error"))) {
                best = candidate
            }
        }

        trimmed.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit // comment / keep-alive
                line.startsWith("data:") -> {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line.removePrefix("data:").removePrefix(" "))
                }
                else -> Unit // event:, id:, retry:
            }
        }
        flush()
        return best
    }

    /** `Bearer resource_metadata="https://.../.well-known/oauth-protected-resource", scope="..."` */
    private fun parseResourceMetadataUrl(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return Regex("""resource_metadata="([^"]+)"""").find(header)?.groupValues?.getOrNull(1)
    }

    override fun close() {
        runCatching { http.close() }
    }

    companion object {
        /**
         * Sent as the requested version; the server's answer is what we then
         * use. Pinned rather than "latest" so an unannounced server-side bump
         * cannot silently change the wire under us.
         */
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "second-brain"
        const val CLIENT_VERSION = "0.1.0"

        /**
         * MCP tool results are `content: [{type, text}, ...]`. Flattens the
         * text blocks into one string, which is what both the dynamic bridge
         * (as a `tool_result`) and the typed adapter (as JSON to re-parse) want.
         */
        fun flattenContent(result: JsonObject): String {
            val content = result["content"]?.jsonArray ?: return result.toString()
            val text = content.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                when (obj["type"]?.jsonPrimitive?.content) {
                    "text" -> obj["text"]?.jsonPrimitive?.content
                    else -> null
                }
            }
            return if (text.isEmpty()) result.toString() else text.joinToString("\n")
        }

        /** MCP flags a *tool* failure inside a successful RPC response. */
        fun isToolError(result: JsonObject): Boolean =
            result["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false

        /** Some servers return a parsed object alongside the text blocks. Prefer it when present. */
        fun structuredContent(result: JsonObject): JsonElement? = result["structuredContent"]
    }
}
