package com.secondbrain.integrations

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.google.api.services.calendar.model.EventDateTime
import com.google.api.services.calendar.model.FreeBusyRequest
import com.google.api.services.calendar.model.FreeBusyRequestItem
import com.secondbrain.model.CalendarProposal
import com.secondbrain.ports.BusyBlock
import com.secondbrain.ports.CalendarPort
import com.secondbrain.ports.InsertOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId

/**
 * `CalendarPort`, for real. Scope is `calendar.events` only. Reads/writes the
 * user's primary calendar.
 */
class CalendarAdapter(
    private val auth: GoogleAuth,
    private val applicationName: String = "Second Brain",
    private val calendarId: String = "primary",
) : CalendarPort {

    private val log = LoggerFactory.getLogger(CalendarAdapter::class.java)
    private val jsonFactory = GsonFactory.getDefaultInstance()

    override suspend fun findBusy(start: Instant, end: Instant): List<BusyBlock> = withContext(Dispatchers.IO) {
        try {
            val calendar = buildService(auth.credential())
            val request = FreeBusyRequest()
                .setTimeMin(DateTime(start.toEpochMilli()))
                .setTimeMax(DateTime(end.toEpochMilli()))
                .setItems(listOf(FreeBusyRequestItem().setId(calendarId)))
            val response = calendar.freebusy().query(request).execute()
            val busy = response.calendars[calendarId]?.busy.orEmpty()
            busy.map { BusyBlock(Instant.ofEpochMilli(it.start.value), Instant.ofEpochMilli(it.end.value)) }
        } catch (e: Exception) {
            // EC-C4: this is informational only. A stale token or a transient
            // failure here must never block proposing the event - it just means
            // the conflict warning is silently absent this once, which is
            // strictly safer than refusing to propose at all.
            log.warn("Conflict check failed, proceeding with no known conflicts: {}", e.message)
            emptyList()
        }
    }

    override suspend fun insert(proposal: CalendarProposal, idempotencyKey: String): InsertOutcome = withContext(Dispatchers.IO) {
        val credential = try {
            auth.credential()
        } catch (e: GoogleAuth.ReauthRequiredException) {
            return@withContext InsertOutcome.NeedsReauth(e.message ?: "re-authentication required")
        }

        try {
            val calendar = buildService(credential)
            val event = Event()
                .setSummary(proposal.title)
                .setLocation(proposal.location)
                .setDescription(proposal.description)
                .setStart(eventDateTime(proposal.start, proposal.zoneId, proposal.allDay))
                .setEnd(eventDateTime(proposal.end, proposal.zoneId, proposal.allDay))
                .setAttendees(proposal.attendees.map { EventAttendee().setEmail(it) })
                // Defence in depth only (see CalendarPort's doc on
                // idempotencyKey): the real guarantee against a double-create is
                // our own ledger state machine, not this property. Costs
                // nothing and helps a manual audit trail.
                .setExtendedProperties(
                    Event.ExtendedProperties().setPrivate(mapOf("secondbrain_proposal_id" to idempotencyKey))
                )

            val inserted = calendar.events().insert(calendarId, event).execute()
            log.info("Calendar insert OK (proposal {}): event id {}", idempotencyKey, inserted.id)
            InsertOutcome.Created(inserted.id)
        } catch (e: HttpResponseException) {
            log.warn("Calendar insert failed with HTTP {}: {}", e.statusCode, e.statusMessage)
            InsertOutcome.Failed("Calendar returned ${e.statusCode}: ${e.statusMessage}")
        } catch (e: SocketTimeoutException) {
            log.error("Calendar insert timed out - outcome unknown", e)
            InsertOutcome.Unknown("timed out: ${e.message}")
        } catch (e: IOException) {
            log.error("Calendar insert failed with a network error - outcome unknown", e)
            InsertOutcome.Unknown("${e::class.simpleName}: ${e.message}")
        } catch (e: Exception) {
            log.error("Calendar insert failed unexpectedly - outcome unknown", e)
            InsertOutcome.Unknown("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun buildService(credential: Credential): Calendar =
        Calendar.Builder(GoogleNetHttpTransport.newTrustedTransport(), jsonFactory, credential)
            .setApplicationName(applicationName)
            .build()

    /** EC-C3: zone ID passed through, never a fixed offset - the API resolves DST itself from it. */
    private fun eventDateTime(instant: Instant, zoneId: String, allDay: Boolean): EventDateTime =
        if (allDay) {
            val date = instant.atZone(ZoneId.of(zoneId)).toLocalDate()
            EventDateTime().setDate(DateTime(date.toString())) // yyyy-MM-dd, ISO_LOCAL_DATE
        } else {
            EventDateTime().setDateTime(DateTime(instant.toEpochMilli())).setTimeZone(zoneId)
        }
}
