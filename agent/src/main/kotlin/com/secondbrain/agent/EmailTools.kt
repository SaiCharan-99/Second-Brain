package com.secondbrain.agent

import com.secondbrain.model.EmailAddressValidator
import com.secondbrain.model.EmailProposal
import com.secondbrain.model.FieldKind
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.ProposalField
import com.secondbrain.ports.MailPort
import com.secondbrain.ports.SendOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * WF-2: `email_draft` (GATED) plus `request_typed_input` (AUTONOMOUS) — D-008's
 * "sanctioned typing escape hatch," available to any tool that needs a
 * verbatim value, not only email. Parallel in shape to `VaultTools`: one class
 * per related tool group, schemas as string literals fixed at construction
 * (same cache-stability reasoning — see `VaultTools`'s own doc).
 *
 * R2's "no email_send tool" holds structurally, not by dispatcher
 * interception (see `ConfirmationGate`'s doc): [draftEmail] never calls [mail]
 * itself. It builds an [EmailProposal] and hands it to
 * [ConfirmationGate.submit], whose executor lambda — the only thing that ever
 * calls `mail.send` — does not run until a human has clicked through content
 * review and per-address verbatim verification.
 */
class EmailTools(
    private val mail: MailPort,
    private val gate: ConfirmationGate,
    /**
     * Backs `request_typed_input`. Reuses `VaultTools.AskResult` rather than a
     * parallel sealed type — "answered" vs "no answer" (silence, cancelled,
     * failed) is exactly the same distinction `ask_user` already needed (D-055),
     * just keyboard-driven instead of voice-driven.
     */
    private val typedInput: suspend (prompt: String, kind: String) -> VaultTools.AskResult,
) {
    private val log = LoggerFactory.getLogger(EmailTools::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun register(builder: ToolRegistry.Builder): ToolRegistry.Builder = builder
        .gated("email_draft", EMAIL_DRAFT_DESC, EMAIL_DRAFT_SCHEMA) { input -> draftEmail(input) }
        .autonomous("request_typed_input", TYPED_INPUT_DESC, TYPED_INPUT_SCHEMA) { input -> requestTypedInput(input) }

    // ── handlers ────────────────────────────────────────────────────────────

    private suspend fun draftEmail(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val to = obj["to"]?.jsonPrimitive?.content?.trim().orEmpty()
        val subject = obj["subject"]?.jsonPrimitive?.content.orEmpty()
        val body = obj["body"]?.jsonPrimitive?.content.orEmpty()
        val speechSummary = obj["speech_summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "An email to $to."
        val cc = obj["cc"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        // Fail closed on shape before ever opening a gate (EC-E1's validation,
        // applied up front rather than only on a later correction).
        if (!EmailAddressValidator.isValid(to)) {
            return ToolOutcome(
                buildJsonObject {
                    put("error", "invalid_address")
                    put("message", "'$to' isn't shaped like an email address. Ask the user for it, or use request_typed_input.")
                }.toString(),
                isError = true,
            )
        }
        val badCc = cc.filterNot { EmailAddressValidator.isValid(it) }
        if (badCc.isNotEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("error", "invalid_address")
                    put("message", "These cc addresses aren't valid: ${badCc.joinToString(", ")}")
                }.toString(),
                isError = true,
            )
        }

        val proposal = EmailProposal(to = to, cc = cc, subject = subject, body = body, speechSummary = speechSummary)
        val fields = buildList {
            add(ProposalField("subject", "Subject", subject, FieldKind.CONTENT))
            add(ProposalField("body", "Body", body, FieldKind.CONTENT))
            // EC-E1/E5: verified independently, one field per address.
            add(ProposalField("to", "To", to, FieldKind.VERBATIM, requiresVerbatimVerification = true))
            if (cc.isNotEmpty()) {
                add(ProposalField("cc", "Cc", cc.joinToString(", "), FieldKind.VERBATIM, requiresVerbatimVerification = true))
            }
        }

        log.info("email_draft: proposing to {} ({} cc)", to, cc.size)

        val outcome = gate.submit(kind = LedgerKind.EMAIL_SEND, proposal = proposal, fields = fields) { proposalId, approved ->
            // R6/EC-E2: `approved` is the gate's current (possibly edited)
            // snapshot, never the original `proposal` captured above.
            val e = approved as EmailProposal
            when (val result = mail.send(e, idempotencyKey = proposalId)) {
                is SendOutcome.Sent -> ConfirmationGate.ExecutorResult.Success(result.messageId)
                is SendOutcome.Failed -> ConfirmationGate.ExecutorResult.Failed(result.reason)
                is SendOutcome.Unknown -> ConfirmationGate.ExecutorResult.Unknown(result.reason)
                is SendOutcome.NeedsReauth -> ConfirmationGate.ExecutorResult.NeedsReauth(result.reason)
            }
        }
        return outcome.toToolOutcome()
    }

    private suspend fun requestTypedInput(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("missing required field 'prompt'")
        val kind = obj["kind"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "text"

        return when (val answer = typedInput(prompt, kind)) {
            is VaultTools.AskResult.Answered -> {
                if (kind == "email" && !EmailAddressValidator.isValid(answer.text)) {
                    // Shape-validated here too, not only inside the proposal
                    // window's own retype flow - this tool can be called with
                    // no gate open at all (e.g. resolving a calendar attendee).
                    return ToolOutcome(
                        buildJsonObject {
                            put("error", "invalid_address")
                            put("message", "'${answer.text}' isn't shaped like an email address. Ask again.")
                        }.toString(),
                        isError = true,
                    )
                }
                ToolOutcome(buildJsonObject { put("value", answer.text) }.toString())
            }
            is VaultTools.AskResult.NoAnswer -> ToolOutcome(
                buildJsonObject {
                    put("value", JsonPrimitive(null as String?))
                    put("no_answer", true)
                    put("reason", answer.reason)
                    put("next_step", "The user did not provide a value. Decide without it, or stop and say so.")
                }.toString(),
            )
        }
    }

    private companion object {
        const val EMAIL_DRAFT_DESC =
            "Draft an email for the user to review and approve. This does NOT send anything by itself - it opens " +
                "a confirmation window the user must click through, including spelling the recipient back for " +
                "confirmation. Only call this once you already know the recipient's address; ask_user first if " +
                "you don't, and never guess one from a first name."
        const val EMAIL_DRAFT_SCHEMA = """
            {"type":"object","properties":{
              "to":{"type":"string","description":"Recipient email address. Must already be known."},
              "cc":{"type":"array","description":"Additional recipient email addresses, if any."},
              "subject":{"type":"string","description":"Email subject line."},
              "body":{"type":"string","description":"The full email body, in the user's own words."},
              "speech_summary":{"type":"string","description":"One or two spoken sentences summarising this email for the user to hear - never the body verbatim, e.g. 'Three sentences to Udit, asking for the current project status.'"}
            },"required":["to","subject","body","speech_summary"]}
        """

        const val TYPED_INPUT_DESC =
            "Ask the user to TYPE a short value on the keyboard instead of speaking it. Use this only for a value " +
                "speech recognition is unreliable for - an email address, a phone number, an exact quantity - and " +
                "only when you cannot get it any other way. This is the one sanctioned typing exception beyond a " +
                "confirmation click (R9); do not reach for it when the value could just as well be spoken."
        const val TYPED_INPUT_SCHEMA = """
            {"type":"object","properties":{
              "prompt":{"type":"string","description":"What to say out loud before the text field appears."},
              "kind":{"type":"string","description":"email | phone | text. Shapes validation. Defaults to text."}
            },"required":["prompt"]}
        """
    }
}
