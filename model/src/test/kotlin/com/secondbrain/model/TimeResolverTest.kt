package com.secondbrain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * D-010: "needs thorough tests - 30+ cases including 'tomorrow' at 23:58,
 * spring-forward, fall-back, 'next Tuesday' on a Tuesday." This is that suite,
 * for the Step 5/6 [TimeResolver].
 *
 * Anchor: 2026-09-01T10:15:00Z is a Tuesday (verified against 2026-01-01 being
 * a Thursday). Most tests run in Asia/Kolkata (+05:30, no DST) so local date
 * matches UTC date; DST-specific tests use America/New_York explicitly.
 */
class TimeResolverTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val tuesdayNoon = Instant.parse("2026-09-01T10:15:00Z") // 2026-09-01 is a Tuesday

    private fun expr(
        datePhrase: String? = "tomorrow",
        startHour: Int? = 12,
        ambiguous: Boolean = false,
        startMinute: Int = 0,
        meridiem: String? = "pm",
        durationMinutes: Int? = 60,
        allDay: Boolean = false,
    ) = TimeExpression(datePhrase, startHour, ambiguous, startMinute, meridiem, durationMinutes, allDay)

    private fun resolved(e: TimeExpression, at: Instant = tuesdayNoon, zone: ZoneId = kolkata): ResolvedTimeRange =
        (TimeResolver.resolve(e, at, zone) as TimeResolution.Resolved).range

    private fun ambiguous(e: TimeExpression, at: Instant = tuesdayNoon, zone: ZoneId = kolkata): TimeResolution.Ambiguous =
        TimeResolver.resolve(e, at, zone) as TimeResolution.Ambiguous

    @Nested
    @DisplayName("date phrases")
    inner class DatePhrases {

        @Test
        fun `today resolves to the utterance's own local date`() {
            val r = resolved(expr(datePhrase = "today"))
            assertEquals(LocalDate.of(2026, 9, 1), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        fun `tomorrow resolves to the next local date`() {
            val r = resolved(expr(datePhrase = "tomorrow"))
            assertEquals(LocalDate.of(2026, 9, 2), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        fun `yesterday resolves to the previous local date`() {
            val r = resolved(expr(datePhrase = "yesterday"))
            assertEquals(LocalDate.of(2026, 8, 31), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        fun `an explicit ISO date is used regardless of today`() {
            val r = resolved(expr(datePhrase = "2026-12-25"))
            assertEquals(LocalDate.of(2026, 12, 25), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        @DisplayName("D-010: 'next Tuesday' said ON a Tuesday means next week's, not today")
        fun `next tuesday on a tuesday means next week`() {
            val r = resolved(expr(datePhrase = "next tuesday"))
            // Anchor itself is a Tuesday; the answer must be 7 days out, not today.
            assertEquals(LocalDate.of(2026, 9, 8), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        @DisplayName("bare 'Tuesday' on a Tuesday behaves the same as 'next Tuesday' - never today")
        fun `bare weekday on that weekday also means next week`() {
            val r = resolved(expr(datePhrase = "tuesday"))
            assertEquals(LocalDate.of(2026, 9, 8), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        fun `a different weekday resolves to this week's upcoming occurrence`() {
            // Anchor is Tuesday 2026-09-01; Friday is 3 days out.
            val r = resolved(expr(datePhrase = "friday"))
            assertEquals(LocalDate.of(2026, 9, 4), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        fun `next friday matches bare friday`() {
            val bare = resolved(expr(datePhrase = "friday")).start
            val next = resolved(expr(datePhrase = "next friday")).start
            assertEquals(bare, next)
        }

        @Test
        fun `abbreviated weekday names resolve the same as the full name`() {
            val full = resolved(expr(datePhrase = "wednesday")).start
            val abbrev = resolved(expr(datePhrase = "wed")).start
            assertEquals(full, abbrev)
        }

        @Test
        fun `weekday matching is case-insensitive and trims whitespace`() {
            val a = resolved(expr(datePhrase = "  TUESDAY  ")).start
            val b = resolved(expr(datePhrase = "tuesday")).start
            assertEquals(a, b)
        }

        @Test
        @DisplayName("EC-C1 for dates: an unrecognisable phrase is ambiguous, never guessed")
        fun `unrecognisable date phrase is ambiguous`() {
            val a = ambiguous(expr(datePhrase = "next fortnight"))
            assertEquals(Ambiguity.MISSING_DATE, a.kind)
        }

        @Test
        fun `a null date phrase is ambiguous`() {
            assertEquals(Ambiguity.MISSING_DATE, ambiguous(expr(datePhrase = null)).kind)
        }

        @Test
        fun `a blank date phrase is ambiguous`() {
            assertEquals(Ambiguity.MISSING_DATE, ambiguous(expr(datePhrase = "   ")).kind)
        }
    }

    @Nested
    @DisplayName("EC-C2: resolved against the utterance instant, not call time")
    inner class UtteranceTime {

        @Test
        fun `tomorrow spoken at 23-58 rolls to the correct next local day`() {
            // 2026-09-01T23:58 in Kolkata (+05:30) is 2026-09-01T18:28:00Z.
            val lateNight = Instant.parse("2026-09-01T18:28:00Z")
            val r = resolved(expr(datePhrase = "tomorrow"), at = lateNight)
            assertEquals(LocalDate.of(2026, 9, 2), r.start.atZone(kolkata).toLocalDate())
        }

        @Test
        fun `the same instant resolves a different local date in a different zone`() {
            // 2026-09-01T23:58 Kolkata is still 2026-09-01T18:28Z, which is
            // 2026-09-01T11:28 in America/Los_Angeles - "today" differs.
            val instant = Instant.parse("2026-09-01T18:28:00Z")
            val la = ZoneId.of("America/Los_Angeles")
            val kolkataToday = resolved(expr(datePhrase = "today"), at = instant, zone = kolkata).start.atZone(kolkata).toLocalDate()
            val laToday = resolved(expr(datePhrase = "today"), at = instant, zone = la).start.atZone(la).toLocalDate()
            assertEquals(LocalDate.of(2026, 9, 1), kolkataToday)
            assertEquals(LocalDate.of(2026, 9, 1), laToday)
            // Push further so the zones actually disagree on the calendar date:
            val nearMidnightKolkata = Instant.parse("2026-09-01T19:00:00Z") // 2026-09-02T00:30 Kolkata
            val kolkataTomorrowSide = resolved(expr(datePhrase = "today"), at = nearMidnightKolkata, zone = kolkata)
                .start.atZone(kolkata).toLocalDate()
            val laStillYesterday = resolved(expr(datePhrase = "today"), at = nearMidnightKolkata, zone = la)
                .start.atZone(la).toLocalDate()
            assertEquals(LocalDate.of(2026, 9, 2), kolkataTomorrowSide)
            assertEquals(LocalDate.of(2026, 9, 1), laStillYesterday)
        }
    }

    @Nested
    @DisplayName("EC-C1: bare-hour ambiguity, never defaulted")
    inner class HourAmbiguity {

        @Test
        fun `bare 12 with no meridiem is ambiguous and asks noon or midnight`() {
            val a = ambiguous(expr(startHour = 12, ambiguous = true, meridiem = null))
            assertEquals(Ambiguity.HOUR_12_OR_24, a.kind)
            assertTrue(a.question.contains("noon", ignoreCase = true))
            assertTrue(a.question.contains("midnight", ignoreCase = true))
        }

        @Test
        fun `bare 3 with no meridiem is ambiguous and asks morning or night`() {
            val a = ambiguous(expr(startHour = 3, ambiguous = true, meridiem = null))
            assertEquals(Ambiguity.HOUR_12_OR_24, a.kind)
            assertTrue(a.question.contains("morning", ignoreCase = true))
        }

        @Test
        fun `12 pm resolves to noon`() {
            val r = resolved(expr(startHour = 12, ambiguous = true, meridiem = "pm"))
            assertEquals(12, r.start.atZone(kolkata).hour)
        }

        @Test
        fun `12 am resolves to midnight`() {
            val r = resolved(expr(startHour = 12, ambiguous = true, meridiem = "am"))
            assertEquals(0, r.start.atZone(kolkata).hour)
        }

        @Test
        fun `9 am resolves to 09-00`() {
            val r = resolved(expr(startHour = 9, ambiguous = true, meridiem = "am"))
            assertEquals(9, r.start.atZone(kolkata).hour)
        }

        @Test
        fun `9 pm resolves to 21-00`() {
            val r = resolved(expr(startHour = 9, ambiguous = true, meridiem = "pm"))
            assertEquals(21, r.start.atZone(kolkata).hour)
        }

        @Test
        fun `an explicit 24-hour value with no ambiguity flag is used directly`() {
            val r = resolved(expr(startHour = 14, ambiguous = false, meridiem = null))
            assertEquals(14, r.start.atZone(kolkata).hour)
        }

        @Test
        fun `minutes are honoured`() {
            val r = resolved(expr(startHour = 14, startMinute = 30, ambiguous = false, meridiem = null))
            assertEquals(30, r.start.atZone(kolkata).minute)
        }

        @Test
        fun `an out-of-range minute is coerced rather than thrown`() {
            val r = resolved(expr(startHour = 14, startMinute = 90, ambiguous = false, meridiem = null))
            assertEquals(59, r.start.atZone(kolkata).minute)
        }
    }

    @Nested
    @DisplayName("EC-C8: all-day vs timed, always explicit")
    inner class AllDay {

        @Test
        fun `explicit all_day gives a midnight-to-midnight range`() {
            val r = resolved(expr(datePhrase = "tomorrow", allDay = true, startHour = null, meridiem = null, durationMinutes = null))
            assertTrue(r.allDay)
            val start = r.start.atZone(kolkata)
            val end = r.end.atZone(kolkata)
            assertEquals(0, start.hour)
            assertEquals(start.toLocalDate().plusDays(1), end.toLocalDate())
        }

        @Test
        fun `no stated hour at all is treated as all-day, not an ambiguity`() {
            val r = resolved(expr(startHour = null, meridiem = null, durationMinutes = null, allDay = false))
            assertTrue(r.allDay)
        }
    }

    @Nested
    @DisplayName("missing or invalid duration")
    inner class Duration {

        @Test
        fun `a timed event with no duration is ambiguous`() {
            val a = ambiguous(expr(durationMinutes = null))
            assertEquals(Ambiguity.MISSING_DURATION, a.kind)
        }

        @Test
        fun `zero duration is ambiguous`() {
            assertEquals(Ambiguity.MISSING_DURATION, ambiguous(expr(durationMinutes = 0)).kind)
        }

        @Test
        fun `negative duration is ambiguous`() {
            assertEquals(Ambiguity.MISSING_DURATION, ambiguous(expr(durationMinutes = -15)).kind)
        }

        @Test
        fun `end is exactly start plus duration`() {
            val r = resolved(expr(durationMinutes = 45))
            assertEquals(r.start.plusSeconds(45 * 60L), r.end)
        }
    }

    @Nested
    @DisplayName("EC-C3: DST, zone ID stored not offset")
    inner class DstTests {

        private val newYork = ZoneId.of("America/New_York")

        @Test
        @DisplayName("spring-forward: a local time inside the gap is shifted, not thrown")
        fun `spring forward gap is resolved without throwing`() {
            // 2026-03-08 is the US spring-forward date; 02:00-03:00 local does not exist.
            val r = resolved(
                expr(datePhrase = "2026-03-08", startHour = 2, ambiguous = false, meridiem = null, durationMinutes = 60),
                zone = newYork,
            )
            val localHour = r.start.atZone(newYork).hour
            // java.time's default gap resolution shifts forward by the gap length (1h) - never 2.
            assertTrue(localHour == 3, "expected the gap to shift 02:00 to 03:00, got $localHour:00")
        }

        @Test
        @DisplayName("fall-back: the repeated local hour resolves without throwing")
        fun `fall back overlap resolves without throwing`() {
            // 2026-11-01 is the US fall-back date; 01:00-02:00 local occurs twice.
            val r = resolved(
                expr(datePhrase = "2026-11-01", startHour = 1, ambiguous = false, meridiem = null, durationMinutes = 60),
                zone = newYork,
            )
            assertTrue(r.end.isAfter(r.start))
        }

        @Test
        fun `zone id is stored verbatim, never converted to a fixed offset`() {
            val r = resolved(expr(), zone = newYork)
            assertEquals("America/New_York", r.zoneId)
        }

        @Test
        fun `kolkata has no DST and never shifts`() {
            val r = resolved(expr(startHour = 2, ambiguous = false, meridiem = null))
            assertEquals(2, r.start.atZone(kolkata).hour)
        }
    }
}
