package com.secondbrain.integrations

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import com.secondbrain.model.EmailProposal
import com.secondbrain.ports.MailPort
import com.secondbrain.ports.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * `MailPort`, for real. Scope is `gmail.send` only — this class cannot read or
 * search Gmail even if it wanted to.
 *
 * The raw RFC 2822 message is hand-built rather than pulled in via a JavaMail
 * dependency: Gmail's `messages.send` wants nothing more than a base64url
 * `To`/`Cc`/`Subject`/body block, and a real mail library's MIME tree,
 * attachment and multipart handling is machinery this app has no use for — the
 * same call `ConfigLoader`'s hand-rolled TOML reader (D-012) and the MCP
 * client (D-009/D-023's "removes a dependency risk") already made for
 * themselves.
 */
class GmailAdapter(
    private val auth: GoogleAuth,
    private val applicationName: String = "Second Brain",
) : MailPort {

    private val log = LoggerFactory.getLogger(GmailAdapter::class.java)
    private val jsonFactory = GsonFactory.getDefaultInstance()

    override suspend fun send(proposal: EmailProposal, idempotencyKey: String): SendOutcome = withContext(Dispatchers.IO) {
        val credential = try {
            auth.credential()
        } catch (e: GoogleAuth.ReauthRequiredException) {
            // EC-E3: the draft is not lost. ConfirmationGate keeps the ledger
            // row APPROVED and the gate open on this outcome.
            return@withContext SendOutcome.NeedsReauth(e.message ?: "re-authentication required")
        }

        try {
            val gmail = Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), jsonFactory, credential)
                .setApplicationName(applicationName)
                .build()
            val message = Message().setRaw(buildRawMessage(proposal))
            val sent = gmail.users().messages().send("me", message).execute()
            log.info("Gmail send OK (proposal {}): message id {}", idempotencyKey, sent.id)
            SendOutcome.Sent(sent.id)
        } catch (e: HttpResponseException) {
            // A definite response from Google, just not a good one - 4xx means
            // we KNOW it did not send (bad address, invalid grant, quota).
            log.warn("Gmail send failed with HTTP {}: {}", e.statusCode, e.statusMessage)
            SendOutcome.Failed("Gmail returned ${e.statusCode}: ${e.statusMessage}")
        } catch (e: SocketTimeoutException) {
            // EC-E4: the network dropped mid-call. Genuinely unknown whether it
            // sent. R5: never auto-retried, here or anywhere else.
            log.error("Gmail send timed out - outcome unknown", e)
            SendOutcome.Unknown("timed out: ${e.message}")
        } catch (e: IOException) {
            log.error("Gmail send failed with a network error - outcome unknown", e)
            SendOutcome.Unknown("${e::class.simpleName}: ${e.message}")
        } catch (e: Exception) {
            log.error("Gmail send failed unexpectedly - outcome unknown", e)
            SendOutcome.Unknown("${e::class.simpleName}: ${e.message}")
        }
    }

    /** Minimal RFC 2822, base64url-encoded exactly as `messages.send`'s `raw` field wants. */
    private fun buildRawMessage(proposal: EmailProposal): String {
        val headers = buildString {
            append("To: ").append(proposal.to).append("\r\n")
            if (proposal.cc.isNotEmpty()) append("Cc: ").append(proposal.cc.joinToString(", ")).append("\r\n")
            append("Subject: ").append(encodeHeader(proposal.subject)).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
        }
        val bodyBase64 = Base64.getMimeEncoder(76, "\r\n".toByteArray(Charsets.US_ASCII))
            .encodeToString(proposal.body.toByteArray(Charsets.UTF_8))
        val full = headers + bodyBase64
        return Base64.getUrlEncoder().withoutPadding().encodeToString(full.toByteArray(Charsets.UTF_8))
    }

    /**
     * RFC 2047 encoded-word for a subject containing non-ASCII text — EC-V5:
     * transcripts may be mixed-script, and a raw UTF-8 byte in an email header
     * is invalid where an encoded word is universally understood.
     */
    private fun encodeHeader(text: String): String {
        if (text.all { it.code < 128 }) return text
        val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$b64?="
    }
}
