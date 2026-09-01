package com.secondbrain.integrations

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * OAuth 2.1 for an MCP server, as the MCP authorization spec defines it:
 * RFC 9728 protected-resource discovery, RFC 8414 authorization-server
 * metadata, RFC 7591 Dynamic Client Registration, and authorization code with
 * PKCE on a loopback redirect.
 *
 * ### Everything here was measured, not assumed (spike S7.1, D-079)
 *
 * Against `auth.zepto.co.in`:
 *
 * ```
 * registration_endpoint              https://auth.zepto.co.in/register
 * grant_types_supported              authorization_code, refresh_token
 * code_challenge_methods_supported   S256          (only - no "plain")
 * token_endpoint_auth_methods_supported  none      (public client, no secret)
 * ```
 *
 * DCR being live is what makes this workable at all. The published Zepto
 * instructions only cover clients whose redirect URI is already whitelisted
 * (Claude Desktop, VS Code); registering our own client is how a desktop app
 * that is neither of those gets in, and it needs no manual step from the user.
 *
 * ### The one non-obvious constraint
 *
 * **The redirect URI must use `localhost`, not `127.0.0.1`.** Registering
 * `http://127.0.0.1:8765/callback` returns a bare HTML `403` from `awselb/2.0`
 * — the WAF in front of the authorization server, not the OAuth layer, which
 * would have answered JSON. `http://localhost:8765/callback` returns `201`.
 * This is the opposite of Google's own recommendation, which `GoogleAuth`
 * follows, so the two flows in this module genuinely differ on it and
 * [redirectUri] is built to make that impossible to get wrong by accident.
 */
class McpOAuth(
    private val resourceUrl: String,
    private val tokenStore: TokenStore,
    private val redirectPort: Int = 8765,
    /** From config, if a previous run already registered. Blank triggers DCR. */
    private var clientId: String = "",
    /** Called when DCR mints a new client id, so `Main` can persist it to config.toml. */
    private val onClientRegistered: (String) -> Unit = {},
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        expectSuccess = false
    },
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(McpOAuth::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    /**
     * `localhost`, deliberately and with a comment, because the obvious
     * "improvement" to `127.0.0.1` breaks registration at the WAF (class doc).
     */
    private val redirectUri = "http://localhost:$redirectPort/callback"

    private data class AuthServerMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val scopesSupported: List<String>,
    )

    @Volatile private var metadata: AuthServerMetadata? = null
    @Volatile private var scopes: List<String> = emptyList()

    // ── the only method the rest of the system calls ────────────────────────

    /**
     * A valid access token, or null if the user has never signed in.
     *
     * Refreshes transparently when expired (EC-E3's rule, applied to commerce).
     * Never launches a browser on its own — an interactive login is
     * [signIn]'s job and must be user-initiated, not a surprise mid-sentence
     * while they are dictating a grocery list.
     */
    suspend fun accessToken(): String? {
        val stored = tokenStore.load(PROVIDER) ?: return null
        if (stored.expiresAt.isAfter(Instant.now().plusSeconds(60))) return stored.access

        return refreshMutex.withLock {
            // Re-read under the lock: a concurrent caller may have refreshed
            // while we waited, and burning a single-use refresh token twice
            // logs the user out.
            val current = tokenStore.load(PROVIDER) ?: return@withLock null
            if (current.expiresAt.isAfter(Instant.now().plusSeconds(60))) return@withLock current.access
            refresh(current.refresh)
        }
    }

    fun isSignedIn(): Boolean = tokenStore.load(PROVIDER) != null

    /**
     * The full interactive flow: discover, register if needed, open a browser,
     * catch the redirect on loopback, exchange the code.
     *
     * Blocks until the user finishes in the browser or [timeoutSeconds] passes.
     */
    suspend fun signIn(timeoutSeconds: Long = 300): Result<Unit> = runCatching {
        val meta = discover()
        ensureRegistered(meta)

        val verifier = pkceVerifier()
        val challenge = pkceChallenge(verifier)
        val state = randomUrlSafe(24)

        val authUrl = buildString {
            append(meta.authorizationEndpoint)
            append(if (meta.authorizationEndpoint.contains('?')) "&" else "?")
            append("response_type=code")
            append("&client_id=").append(enc(clientId))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            // RFC 8707. The MCP spec requires it: it binds the token to this
            // resource server so a token minted here cannot be replayed at
            // another one sharing the same authorization server.
            append("&resource=").append(enc(resourceUrl))
            if (scopes.isNotEmpty()) append("&scope=").append(enc(scopes.joinToString(" ")))
        }

        val code = awaitRedirect(state, timeoutSeconds, authUrl)
        exchange(meta, code, verifier)
        log.info("Signed in to the commerce provider.")
    }

    fun signOut() {
        runCatching { tokenStore.save(PROVIDER, TokenStore.Tokens("", "", Instant.EPOCH)) }
    }

    // ── discovery ───────────────────────────────────────────────────────────

    private suspend fun discover(): AuthServerMetadata {
        metadata?.let { return it }

        // RFC 9728: ask the resource server who authorizes for it.
        val prm = getJson(wellKnown(resourceUrl, "oauth-protected-resource"))
            ?: error("The commerce server did not publish protected-resource metadata.")

        val issuer = prm["authorization_servers"]?.jsonArray
            ?.firstOrNull()?.jsonPrimitive?.content
            ?: error("The commerce server named no authorization server.")

        scopes = prm["scopes_supported"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        // RFC 8414: the AS's own metadata lives on the AS host.
        val asm = getJson(wellKnown(issuer, "oauth-authorization-server"))
            ?: getJson(wellKnown(issuer, "openid-configuration"))
            ?: error("The authorization server published no metadata.")

        val challengeMethods = asm["code_challenge_methods_supported"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        require(challengeMethods.isEmpty() || "S256" in challengeMethods) {
            "The authorization server does not support PKCE S256, which this client requires."
        }

        val parsed = AuthServerMetadata(
            authorizationEndpoint = asm["authorization_endpoint"]?.jsonPrimitive?.content
                ?: error("No authorization_endpoint."),
            tokenEndpoint = asm["token_endpoint"]?.jsonPrimitive?.content
                ?: error("No token_endpoint."),
            registrationEndpoint = asm["registration_endpoint"]?.jsonPrimitive?.content,
            scopesSupported = scopes,
        )
        metadata = parsed
        return parsed
    }

    /** `https://host/path` -> `https://host/.well-known/<name>`. */
    private fun wellKnown(base: String, name: String): String {
        val uri = URI(base)
        return "${uri.scheme}://${uri.authority}/.well-known/$name"
    }

    private suspend fun getJson(url: String): JsonObject? = runCatching {
        val response = http.get(url) { header("Accept", "application/json") }
        val text = response.bodyAsText()
        if (!text.trimStart().startsWith("{")) null
        else json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()

    // ── RFC 7591 dynamic client registration ────────────────────────────────

    private suspend fun ensureRegistered(meta: AuthServerMetadata) {
        if (clientId.isNotBlank()) return
        val endpoint = meta.registrationEndpoint
            ?: error(
                "This authorization server has no dynamic registration endpoint, and no " +
                    "commerce.oauth_client_id is configured. Register a client manually and set that key."
            )

        val body = buildJsonObject {
            put("client_name", "Second Brain")
            put("redirect_uris", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(redirectUri))
            })
            put("grant_types", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("authorization_code"))
                add(kotlinx.serialization.json.JsonPrimitive("refresh_token"))
            })
            put("response_types", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("code"))
            })
            put("token_endpoint_auth_method", "none")
            if (scopes.isNotEmpty()) put("scope", scopes.joinToString(" "))
        }.toString()

        val response = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(body)
        }
        val text = response.bodyAsText()

        // A non-JSON body here is the WAF, not the OAuth layer - the case the
        // class doc describes. Say which, because "403" alone sends you
        // debugging your client credentials for an hour.
        if (!text.trimStart().startsWith("{")) {
            error(
                "Client registration was blocked by the provider's edge (HTTP ${response.status.value}). " +
                    "If the redirect URI was changed from 'localhost' to '127.0.0.1', change it back - see McpOAuth's docs."
            )
        }

        val obj = json.parseToJsonElement(text) as? JsonObject
            ?: error("Registration returned an unreadable response.")
        obj["error"]?.jsonPrimitive?.content?.let {
            error("Client registration failed: $it ${obj["error_description"]?.jsonPrimitive?.content.orEmpty()}")
        }

        clientId = obj["client_id"]?.jsonPrimitive?.content
            ?: error("Registration returned no client_id.")
        log.info("Registered a new OAuth client with the commerce provider.")
        onClientRegistered(clientId)
    }

    // ── loopback redirect ───────────────────────────────────────────────────

    /**
     * Runs a one-request HTTP server on the loopback port, opens the browser,
     * and waits for the authorization code.
     *
     * The port is fixed rather than ephemeral because it is baked into the
     * registered redirect URI — an OS-assigned port would not match what was
     * registered and the AS would reject the callback.
     */
    private suspend fun awaitRedirect(expectedState: String, timeoutSeconds: Long, authUrl: String): String {
        val received = CompletableDeferred<Result<String>>()
        val server = HttpServer.create(InetSocketAddress("localhost", redirectPort), 0)

        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            val params = query.split("&").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()

            val page: String
            when {
                params["error"] != null -> {
                    page = htmlPage("Sign-in failed", params["error_description"] ?: params["error"]!!)
                    received.complete(Result.failure(IllegalStateException("Authorization denied: ${params["error"]}")))
                }
                // CSRF: an unsolicited callback with someone else's code must
                // not be exchanged.
                params["state"] != expectedState -> {
                    page = htmlPage("Sign-in failed", "The security check did not match. Please try again.")
                    received.complete(Result.failure(IllegalStateException("OAuth state mismatch.")))
                }
                params["code"] != null -> {
                    page = htmlPage("You're signed in", "You can close this tab and go back to Second Brain.")
                    received.complete(Result.success(params["code"]!!))
                }
                else -> {
                    page = htmlPage("Sign-in failed", "No authorization code was returned.")
                    received.complete(Result.failure(IllegalStateException("No code in the callback.")))
                }
            }

            val bytes = page.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.start()
        try {
            openBrowser(authUrl)
            return withTimeout(timeoutSeconds * 1000) { received.await() }.getOrThrow()
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Sign-in timed out after $timeoutSeconds seconds.")
        } finally {
            server.stop(0)
        }
    }

    private fun openBrowser(url: String) {
        val opened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url)); true
            } else false
        }.getOrDefault(false)

        if (!opened) {
            // Headless, or a desktop with no registered browser. The flow still
            // works if the user opens it themselves, so say so rather than fail.
            log.warn("Could not open a browser automatically. Open this URL to sign in:\n{}", url)
        }
    }

    // ── token exchange and refresh ──────────────────────────────────────────

    private suspend fun exchange(meta: AuthServerMetadata, code: String, verifier: String) {
        val response = http.submitForm(
            url = meta.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", clientId)
                append("code_verifier", verifier)
                append("resource", resourceUrl)
            },
        )
        storeTokens(response.bodyAsText())
    }

    private suspend fun refresh(refreshToken: String): String? {
        val meta = runCatching { discover() }.getOrNull() ?: return null
        val response = runCatching {
            http.submitForm(
                url = meta.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", clientId)
                    append("resource", resourceUrl)
                },
            )
        }.getOrNull() ?: return null

        return runCatching { storeTokens(response.bodyAsText(), fallbackRefresh = refreshToken) }
            .onFailure { log.warn("Token refresh failed; the user will need to sign in again. ({})", it.message) }
            .getOrNull()
    }

    /** @return the new access token. */
    private fun storeTokens(body: String, fallbackRefresh: String? = null): String {
        val obj = json.parseToJsonElement(body) as? JsonObject
            ?: error("The token endpoint returned an unreadable response.")
        obj["error"]?.jsonPrimitive?.content?.let {
            error("Token exchange failed: $it ${obj["error_description"]?.jsonPrimitive?.content.orEmpty()}")
        }

        val access = obj["access_token"]?.jsonPrimitive?.content ?: error("No access_token in the response.")
        // Rotating servers issue a new refresh token; non-rotating ones omit
        // it and expect the old one to keep working.
        val refresh = obj["refresh_token"]?.jsonPrimitive?.content ?: fallbackRefresh.orEmpty()
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

        tokenStore.save(
            PROVIDER,
            TokenStore.Tokens(access, refresh, Instant.now().plusSeconds(expiresIn)),
        )
        return access
    }

    // ── PKCE ────────────────────────────────────────────────────────────────

    private fun pkceVerifier(): String = randomUrlSafe(64)

    private fun pkceChallenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun htmlPage(title: String, message: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><title>$title</title>
        <style>body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;display:flex;align-items:center;
        justify-content:center;height:100vh;margin:0;background:#faf9f7;color:#2b2a28}
        div{text-align:center;max-width:26rem}h1{font-size:1.25rem;margin:0 0 .5rem}
        p{margin:0;color:#6b6963;line-height:1.5}</style></head>
        <body><div><h1>$title</h1><p>$message</p></div></body></html>
    """.trimIndent()

    override fun close() {
        runCatching { http.close() }
    }

    companion object {
        const val PROVIDER = "zepto"
    }
}
