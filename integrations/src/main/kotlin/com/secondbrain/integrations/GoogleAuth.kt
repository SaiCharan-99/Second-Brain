package com.secondbrain.integrations

import com.google.api.client.auth.oauth2.BearerToken
import com.google.api.client.auth.oauth2.ClientParametersAuthentication
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.MemoryDataStoreFactory
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * One Google OAuth client, two least-privilege scopes, a single consent
 * screen and a single token pair (`provider = "google"`) covering both Gmail
 * send and Calendar events — see the Step 5/6 DECISIONS entry.
 *
 * ARCHITECTURE §7 Step 5: "OAuth 2.0 loopback flow (opens a browser once)."
 * [LocalServerReceiver] is exactly that — the "loopback IP address" flow
 * Google's current docs describe, which needs no fixed redirect URI
 * registered on a Desktop-app OAuth client.
 *
 * Persistence is entirely [TokenStore]. The flow's own on-disk credential
 * store (a `FileDataStoreFactory`, by default) is deliberately not used —
 * [MemoryDataStoreFactory] backs the flow's own bookkeeping during the
 * interactive handshake only, and every token this class actually needs to
 * survive a restart is extracted afterward and written to [tokenStore], which
 * matches `GoogleConfig.tokenStorePath` rather than wherever the library
 * would otherwise default to.
 */
class GoogleAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val redirectPort: Int,
    private val tokenStore: TokenStore,
) {
    private val log = LoggerFactory.getLogger(GoogleAuth::class.java)

    private val httpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
    private val jsonFactory = GsonFactory.getDefaultInstance()

    companion object {
        const val PROVIDER = "google"

        /** Least privilege: never read/search Gmail, never touch calendars other than events. */
        val SCOPES: List<String> = listOf(
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/calendar.events",
        )
    }

    /** EC-E3: a refresh failure surfaces as this, never as a silently dead credential. */
    class ReauthRequiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Returns a live [Credential]: from [tokenStore] if present (refreshing
     * first if it's stale), or by running the interactive consent flow on
     * first use. Synchronized because this can be reached from `GmailAdapter`
     * and `CalendarAdapter` concurrently, and the interactive flow must never
     * run twice at once.
     */
    @Synchronized
    fun credential(): Credential {
        val stored = tokenStore.load(PROVIDER)
        if (stored == null) {
            log.info("No Google tokens on file; starting the interactive consent flow (opens a browser once).")
            val authorized = runInteractiveFlow()
            persist(authorized)
            return authorized
        }

        val credential = fromStoredTokens(stored)
        if (needsRefresh(credential)) {
            val refreshed = try {
                credential.refreshToken()
            } catch (e: Exception) {
                throw ReauthRequiredException("Google sign-in expired and refresh failed: ${e.message}", e)
            }
            if (!refreshed) throw ReauthRequiredException("Google sign-in expired and refresh failed.")
            persist(credential)
        }
        return credential
    }

    private fun runInteractiveFlow(): Credential {
        val flow = GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets(), SCOPES)
            .setDataStoreFactory(MemoryDataStoreFactory.getDefaultInstance())
            .setAccessType("offline") // required to get a refresh token back at all
            .build()
        val receiver = LocalServerReceiver.Builder().setPort(redirectPort).build()
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    }

    private fun clientSecrets(): GoogleClientSecrets {
        val details = GoogleClientSecrets.Details()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setAuthUri("https://accounts.google.com/o/oauth2/auth")
            .setTokenUri("https://oauth2.googleapis.com/token")
        return GoogleClientSecrets().setInstalled(details)
    }

    /** Reconstructs a [Credential] directly from persisted tokens, bypassing the flow's own DataStore entirely. */
    private fun fromStoredTokens(tokens: TokenStore.Tokens): Credential {
        val credential = Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
            .setTransport(httpTransport)
            .setJsonFactory(jsonFactory)
            .setTokenServerUrl(GenericUrl("https://oauth2.googleapis.com/token"))
            .setClientAuthentication(ClientParametersAuthentication(clientId, clientSecret))
            .build()
        credential.accessToken = tokens.access
        credential.refreshToken = tokens.refresh
        credential.expirationTimeMilliseconds = tokens.expiresAt.toEpochMilli()
        return credential
    }

    /** Refresh a minute early rather than racing an in-flight API call against the exact expiry instant. */
    private fun needsRefresh(credential: Credential): Boolean {
        val expiresIn = credential.expiresInSeconds ?: return true
        return expiresIn < 60
    }

    private fun persist(credential: Credential) {
        val access = credential.accessToken
        val refresh = credential.refreshToken
        if (access == null || refresh == null) {
            log.warn("Google credential is missing an access or refresh token; not persisting.")
            return
        }
        val expiresAt = Instant.now().plusSeconds(credential.expiresInSeconds ?: 3600)
        tokenStore.save(PROVIDER, TokenStore.Tokens(access, refresh, expiresAt))
    }
}
