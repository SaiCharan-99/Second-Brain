package com.secondbrain.app

import com.secondbrain.integrations.DeviceId
import com.secondbrain.integrations.McpClient
import com.secondbrain.integrations.McpCommerceAdapter
import com.secondbrain.integrations.McpOAuth
import com.secondbrain.integrations.MutationClassifier
import com.secondbrain.integrations.TokenStore
import com.secondbrain.model.ConfigLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * A one-shot dump of the real Zepto MCP server's `tools/list` — every tool's
 * exact name, description and full JSON Schema, plus the [MutationClassifier]
 * verdict for each.
 *
 * Not part of the app proper. `McpCommerceAdapter`'s role bindings and response
 * parsers (D-079) were written from Zepto's own documentation because no
 * authenticated session had ever been reached — this is the tool that closes
 * that gap (D-082's second and third named gaps) the moment a real sign-in
 * exists. Run it, read the output, then fix whatever `McpCommerceAdapter`
 * guessed wrong against what is actually printed here.
 *
 * Requires `commerce.enabled = true`, `commerce.use_fake = false`, and a
 * completed sign-in (`zepto_tokens.db` holding a valid token) — run the app
 * itself first and use the "Sign in to Zepto" chip. This reads that same
 * token store; it does not open a browser itself.
 *
 * `./gradlew.bat :app:zeptoDiscover`
 */
fun main() = runBlocking {
    val log = LoggerFactory.getLogger("ZeptoDiscovery")
    val appConfig = ConfigLoader.load()

    if (!appConfig.commerce.enabled) {
        log.error("commerce.enabled = false in config.toml. Set it true first.")
        return@runBlocking
    }

    val root = ConfigLoader.expandHome(appConfig.paths.root)
    val tokenStore = TokenStore(root.resolve(appConfig.commerce.tokenStorePath))

    val oauth = McpOAuth(
        resourceUrl = appConfig.commerce.mcpUrl,
        tokenStore = tokenStore,
        redirectPort = appConfig.commerce.redirectPort,
        clientId = appConfig.commerce.oauthClientId,
    )

    if (!oauth.isSignedIn()) {
        log.error(
            "Not signed in to Zepto yet. Run the app (:app:run), click \"Sign in to Zepto\" " +
                "in the status bar, complete the phone + OTP flow, then run this again.",
        )
        tokenStore.close()
        return@runBlocking
    }

    val client = McpClient(
        endpoint = appConfig.commerce.mcpUrl,
        tokenProvider = { oauth.accessToken() },
        requestTimeoutMs = appConfig.commerce.requestTimeoutMs,
    )

    val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    when (val result = client.listTools()) {
        is McpClient.McpResult.Err -> log.error("tools/list failed: {}", result.error)
        is McpClient.McpResult.Ok -> {
            val tools = result.value
            log.info("=".repeat(78))
            log.info("Zepto MCP: {} tool(s)", tools.size)
            log.info("=".repeat(78))

            tools.forEach { tool ->
                val verdict = MutationClassifier.classify(tool.name, tool.description)
                log.info("")
                log.info("── {} ── [{}, {}]", tool.name, verdict.toolClass, verdict.reason)
                log.info("description: {}", tool.description ?: "(none)")
                val schemaText = tool.inputSchema?.let {
                    runCatching { prettyJson.encodeToString(JsonObject.serializer(), it) }.getOrElse { it.toString() }
                } ?: "(no schema)"
                log.info("inputSchema:\n{}", schemaText)
            }

            log.info("")
            log.info("=".repeat(78))
            log.info("McpCommerceAdapter's role bindings against the above (its own logger):")
            log.info("=".repeat(78))
            // Reuses the exact same discover() the running app calls on first
            // use - same bindings, same startup table - rather than a second,
            // possibly-diverging summary written just for this tool.
            val deviceId = DeviceId.stable(root.resolve("zepto_device_id.txt"))
            McpCommerceAdapter(client, oauth, deviceId).discover()

            // tools/list only advertises INPUT schemas. The only way to see
            // real RESPONSE shapes - what field the price actually comes back
            // under, whether the cart has a line id at all - is to call the
            // tools and look. Best-effort and read-only except select_store /
            // select_saved_address, which only set session context and cost
            // nothing.
            log.info("")
            log.info("=".repeat(78))
            log.info("Live call trace (response bodies, for fixing response parsing):")
            log.info("=".repeat(78))

            suspend fun dump(label: String, args: JsonObject = JsonObject(emptyMap())) {
                log.info("")
                log.info("── {}({}) ──", label, args)
                when (val r = client.callTool(label, args)) {
                    is McpClient.McpResult.Err -> log.warn("  error: {}", r.error)
                    is McpClient.McpResult.Ok -> {
                        // The RAW result object first - this is what tells us
                        // whether structuredContent exists at all, which the
                        // flattened text alone cannot.
                        log.info("  RAW keys: {}", r.value.keys)
                        log.info("  RAW: {}", runCatching {
                            prettyJson.encodeToString(JsonObject.serializer(), r.value)
                        }.getOrElse { r.value.toString() })
                        log.info("  isError={}", McpClient.isToolError(r.value))
                    }
                }
            }

            dump("get_user_details")
            dump("list_saved_addresses")
            dump("view_cart")

            // search_products needs a store selected first (D-089's actual
            // root cause) - select_saved_address sets that automatically.
            // The first saved address is arbitrary but harmless: it only sets
            // which store's catalogue subsequent calls see.
            val addresses = client.callTool("list_saved_addresses", JsonObject(emptyMap()))
            val firstAddressId = (addresses as? McpClient.McpResult.Ok)?.value
                ?.get("structuredContent")?.let { it as? JsonObject }
                ?.get("addresses")?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull()?.let { it as? JsonObject }
                ?.get("id")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content

            if (firstAddressId != null) {
                dump("select_saved_address", buildJsonObject { put("addressId", firstAddressId) })
                dump("search_products", buildJsonObject { put("query", "stainless steel bottle") })
            } else {
                log.warn("Could not read an address id from list_saved_addresses' structuredContent - skipping store selection.")
            }
        }
    }

    client.close()
    oauth.close()
    tokenStore.close()
}
