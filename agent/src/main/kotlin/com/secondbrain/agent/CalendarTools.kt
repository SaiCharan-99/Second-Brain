package com.secondbrain.agent

import com.secondbrain.model.CalendarProposal
import com.secondbrain.model.EmailAddressValidator
import com.secondbrain.model.FieldKind
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.ProposalField
import com.secondbrain.model.TimeExpression
import com.secondbrain.model.TimeResolution
import com.secondbrain.model.TimeResolver
import com.secondbrain.ports.CalendarPort
import com.secondbrain.ports.InsertOutcome
import com.secondbrain.ports.VaultStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * WF-3: "same gate machinery [as email], different payload." Three tools:
 * `calendar_resolve_time` and `calendar_find_conflicts` (AUTONOMOUS, read-only
 * or pure computation), `calendar_propose_event` (GATED).
 *
 * The one new idea beyond `EmailTools`: ambiguity resolution happens BEFORE the
 * gate ever opens. `calendar_resolve_time` either resolves cleanly or hands
 * back a spoken question (EC-C1); the model asks it via `ask_user` and calls
 * `calendar_resolve_time` again. `calendar_propose_event`'s schema only ever
 * accepts an already-resolved absolute start/end + zone — Claude never invents
 * an absolute timestamp itself (D-010).
 */
class CalendarTools(
    private val calendar: CalendarPort,
    private val gate: ConfirmationGate,
    /** EC-C5: looks up a named attendee's address under `People/` before ever asking. */
    private val vault: VaultStore,
    private val turnClock: TurnClock,
    private val askUser: suspend (question: String) -> VaultTools.AskResult,
) {
    private val log = LoggerFactory.getLogger(CalendarTools::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun register(builder: ToolRegistry.Builder): ToolRegistry.Builder = builder
        .autonomous("calendar_resolve_time", RESOLVE_TIME_DESC, RESOLVE_TIME_SCHEMA) { input -> resolveTime(input) }
        .autonomous("calendar_find_conflicts", FIND_CONFLICTS_DESC, FIND_CONFLICTS_SCHEMA) { input -> findConflicts(input) }
        .gated("calendar_propose_event", PROPOSE_EVENT_DESC, PROPOSE_EVENT_SCHEMA) { input -> proposeEvent(input) }

    // ── calendar_resolve_time ───────────────────────────────────────────────

    private fun resolveTime(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val expr = TimeExpression(
            datePhrase = obj["date_phrase"]?.jsonPrimitive?.content,
            startHour = obj["start_hour"]?.jsonPrimitive?.content?.toIntOrNull(),
            startHourAmbiguous12h = obj["start_hour_ambiguous"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            startMinute = obj["start_minute"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            meridiem = obj["meridiem"]?.jsonPrimitive?.content,
            durationMinutes = obj["duration_minutes"]?.jsonPrimitive?.content?.toIntOrNull(),
            allDay = obj["all_day"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        )
        // EC-C2: resolved against the UTTERANCE's instant, set once per turn by
        // AgentLoop.run - never Instant.now() at the moment this tool happens
        // to be called, which after an ask_user round-trip can be minutes later.
        val moment = turnClock.current

        return when (val resolution = TimeResolver.resolve(expr, moment.at, moment.zone)) {
            is TimeResolution.Resolved -> ToolOutcome(
                buildJsonObject {
                    put("resolved", true)
                    put("start", resolution.range.start.toString())
                    put("end", resolution.range.end.toString())
                    put("zone", resolution.range.zoneId)
                    put("all_day", resolution.range.allDay)
                }.toString()
            )
            is TimeResolution.Ambiguous -> ToolOutcome(
                // EC-C1: never resolved by guessing. The model must ask_user
                // with this exact question, then call this tool again.
                buildJsonObject {
                    put("resolved", false)
                    put("ambiguity", resolution.kind.name)
                    put("question", resolution.question)
                    put("next_step", "Ask the user this question with ask_user, then call calendar_resolve_time again with the clarified fields.")
                }.toString()
            )
        }
    }

    // ── calendar_find_conflicts ─────────────────────────────────────────────

    private suspend fun findConflicts(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val start = parseInstant(obj, "start") ?: return badInstant("start")
        val end = parseInstant(obj, "end") ?: return badInstant("end")

        val busy = calendar.findBusy(start, end)
        return ToolOutcome(
            buildJsonObject {
                put("has_conflict", busy.isNotEmpty())
                put("conflicts", buildJsonArray {
                    busy.forEach { b ->
                        add(buildJsonObject {
                            put("start", b.start.toString())
                            put("end", b.end.toString())
                            b.title?.let { put("title", it) }
                        })
                    }
                })
                // EC-C4: explicit, so the model never treats a non-empty list as a block.
                put("note", "A conflict is informational only. Warn the user; do not refuse to propose the event.")
            }.toString()
        )
    }

    // ── calendar_propose_event ──────────────────────────────────────────────

    private suspend fun proposeEvent(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
        val start = parseInstant(obj, "start") ?: return badInstant("start")
        val end = parseInstant(obj, "end") ?: return badInstant("end")
        val zoneId = obj["zone"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: turnClock.current.zone.id
        val allDay = obj["all_day"]?.jsonPrimitive?.content?.toBoolean() ?: false
        val location = obj["location"]?.jsonPrimitive?.content?.ifBlank { null }
        val description = obj["description"]?.jsonPrimitive?.content?.ifBlank { null }
        val rawAttendees = obj["attendees"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        // EC-C7: never open a gate on an inverted range - checked once here up
        // front, and again live by ConfirmationGate's validator on every edit.
        if (!end.isAfter(start)) {
            return ToolOutcome(
                buildJsonObject { put("error", "invalid_range"); put("message", "End must be after start.") }.toString(),
                isError = true,
            )
        }

        // EC-C5: resolve every attendee to a real address before ever opening
        // a gate. Never fabricated - a miss stops here and asks, rather than
        // silently dropping the attendee or guessing charan@gmail.com because
        // it pattern-matches.
        val resolvedAttendees = mutableListOf<String>()
        for (raw in rawAttendees) {
            val address = resolveAttendee(raw)
                ?: return ToolOutcome(
                    buildJsonObject {
                        put("error", "unknown_attendee")
                        put(
                            "message",
                            "No address found for '$raw' and the user didn't give one. Call this again " +
                                "either without that attendee, or with their address.",
                        )
                    }.toString(),
                    isError = false,
                )
            resolvedAttendees += address
        }

        // EC-C4: re-checked here regardless of whether the model already called
        // calendar_find_conflicts, so a conflict is never silently skipped.
        val conflicts = calendar.findBusy(start, end)
        val conflictWarning = if (conflicts.isEmpty()) null else
            "Overlaps " + conflicts.joinToString("; ") { "${it.title ?: "an existing event"} (${fmtRange(it.start, it.end, zoneId)})" }

        val proposal = CalendarProposal(
            title = title,
            start = start,
            end = end,
            zoneId = zoneId,
            allDay = allDay,
            attendees = resolvedAttendees,
            location = location,
            description = description,
            conflictWarning = conflictWarning,
            speechSummary = speechSummaryFor(title, start, end, zoneId, allDay),
        )

        val fields = buildList {
            add(ProposalField("title", "Title", title, FieldKind.CONTENT))
            // EC-C6: time edits never invalidate approval - "time is what the
            // window is for." Value is a local (offset-free) ISO string;
            // ConfirmationGate.applyField parses it back against proposal.zoneId.
            add(ProposalField("start", "Start", localIso(start, zoneId), FieldKind.VERBATIM))
            add(ProposalField("end", "End", localIso(end, zoneId), FieldKind.VERBATIM))
            location?.let { add(ProposalField("location", "Location", it, FieldKind.CONTENT)) }
            description?.let { add(ProposalField("description", "Description", it, FieldKind.CONTENT)) }
            if (resolvedAttendees.isNotEmpty()) {
                add(ProposalField("attendees", "Attendees", resolvedAttendees.joinToString(", "), FieldKind.VERBATIM, requiresVerbatimVerification = true))
            }
        }

        log.info("calendar_propose_event: '{}' {} -> {} ({} attendee(s))", title, start, end, resolvedAttendees.size)

        val outcome = gate.submit(
            kind = LedgerKind.CALENDAR_CREATE,
            proposal = proposal,
            fields = fields,
            validator = { p -> (p as CalendarProposal).let { if (!it.end.isAfter(it.start)) "End must be after start." else null } },
        ) { proposalId, approved ->
            val c = approved as CalendarProposal
            when (val result = calendar.insert(c, idempotencyKey = proposalId)) {
                is InsertOutcome.Created -> ConfirmationGate.ExecutorResult.Success(result.eventId)
                is InsertOutcome.Failed -> ConfirmationGate.ExecutorResult.Failed(result.reason)
                is InsertOutcome.Unknown -> ConfirmationGate.ExecutorResult.Unknown(result.reason)
                is InsertOutcome.NeedsReauth -> ConfirmationGate.ExecutorResult.NeedsReauth(result.reason)
            }
        }
        return outcome.toToolOutcome()
    }

    // ── EC-C5 ───────────────────────────────────────────────────────────────

    private suspend fun resolveAttendee(raw: String): String? {
        val trimmed = raw.trim()
        if (EmailAddressValidator.isValid(trimmed)) return trimmed

        val hits = vault.search(trimmed, limit = 5)
        val personHit = hits.firstOrNull { it.path.startsWith("People/") }
        if (personHit != null) {
            val note = vault.read(personHit.path)
            val found = note?.let {
                EMAIL_IN_TEXT.find(it.bodyMarkdown)?.value ?: EMAIL_IN_TEXT.find(it.summary)?.value
            }
            if (found != null) return found
        }

        return when (val answer = askUser("I don't have an email address for $trimmed. What is it?")) {
            is VaultTools.AskResult.Answered -> answer.text.trim().takeIf { EmailAddressValidator.isValid(it) }
            is VaultTools.AskResult.NoAnswer -> null
        }
    }

    // ── small helpers ───────────────────────────────────────────────────────

    private fun parseInstant(obj: JsonObject, field: String): Instant? =
        obj[field]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun badInstant(field: String) = ToolOutcome(
        buildJsonObject {
            put("error", "invalid_input")
            put("message", "'$field' must be an ISO-8601 instant, e.g. 2026-09-02T06:30:00Z - the value calendar_resolve_time returned.")
        }.toString(),
        isError = true,
    )

    private fun fmtRange(start: Instant, end: Instant, zoneId: String): String {
        val zone = ZoneId.of(zoneId)
        val fmt = DateTimeFormatter.ofPattern("h:mm a")
        return "${fmt.format(start.atZone(zone))}-${fmt.format(end.atZone(zone))}"
    }

    private fun localIso(instant: Instant, zoneId: String): String =
        instant.atZone(ZoneId.of(zoneId)).toLocalDateTime().toString()

    /** EC-C8: all-day vs timed is always stated explicitly, never implied. */
    private fun speechSummaryFor(title: String, start: Instant, end: Instant, zoneId: String, allDay: Boolean): String {
        val zone = ZoneId.of(zoneId)
        val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d")
        val startZdt = start.atZone(zone)
        return if (allDay) {
            "All day, ${dateFmt.format(startZdt)}: $title."
        } else {
            val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
            "${dateFmt.format(startZdt)}, ${timeFmt.format(startZdt)} to ${timeFmt.format(end.atZone(zone))}: $title."
        }
    }

    private companion object {
        /** Loose, extraction-only - not the validation pattern. Used to pull an address out of free-form note text. */
        val EMAIL_IN_TEXT = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

        const val RESOLVE_TIME_DESC =
            "Resolve a spoken time expression to an absolute start/end. Call this BEFORE calendar_propose_event, " +
                "never compute or guess an absolute timestamp yourself. If the result is ambiguous, ask_user the " +
                "returned question and call this again with the clarified fields."
        const val RESOLVE_TIME_SCHEMA = """
            {"type":"object","properties":{
              "date_phrase":{"type":"string","description":"The day, in the user's words or ISO: 'today', 'tomorrow', 'next tuesday', '2026-09-05'. Omit only if the user gave no day at all."},
              "start_hour":{"type":"integer","description":"The hour the user said, 0-23 or 1-12. Omit entirely for an all-day event."},
              "start_hour_ambiguous":{"type":"boolean","description":"True for a bare number with no AM/PM and no 24-hour context, e.g. '12' or '3'."},
              "start_minute":{"type":"integer","description":"Minutes past the hour. Defaults to 0."},
              "meridiem":{"type":"string","description":"'am' or 'pm' - only if the user said it explicitly."},
              "duration_minutes":{"type":"integer","description":"How long the event lasts, in minutes."},
              "all_day":{"type":"boolean","description":"True if the user gave no time at all."}
            },"required":[]}
        """

        const val FIND_CONFLICTS_DESC =
            "Check for existing events overlapping a time range. Informational only - a conflict is warned about, " +
                "never a reason to refuse proposing the event (EC-C4)."
        const val FIND_CONFLICTS_SCHEMA = """
            {"type":"object","properties":{
              "start":{"type":"string","description":"ISO-8601 instant, from calendar_resolve_time."},
              "end":{"type":"string","description":"ISO-8601 instant, from calendar_resolve_time."}
            },"required":["start","end"]}
        """

        const val PROPOSE_EVENT_DESC =
            "Propose a calendar event for the user to review and approve. This does NOT create anything by itself " +
                "- it opens a confirmation window. start/end/zone MUST come from calendar_resolve_time, never " +
                "invented. Attendees may be a name (looked up in the vault, or the user is asked) or an address " +
                "directly - never a guessed address."
        const val PROPOSE_EVENT_SCHEMA = """
            {"type":"object","properties":{
              "title":{"type":"string","description":"Event title."},
              "start":{"type":"string","description":"ISO-8601 instant from calendar_resolve_time."},
              "end":{"type":"string","description":"ISO-8601 instant from calendar_resolve_time."},
              "zone":{"type":"string","description":"IANA zone id from calendar_resolve_time, e.g. Asia/Kolkata."},
              "all_day":{"type":"boolean","description":"Whether this is an all-day event."},
              "attendees":{"type":"array","description":"Attendee names or email addresses, if any."},
              "location":{"type":"string","description":"Optional location."},
              "description":{"type":"string","description":"Optional longer description."}
            },"required":["title","start","end","zone","all_day"]}
        """
    }
}
