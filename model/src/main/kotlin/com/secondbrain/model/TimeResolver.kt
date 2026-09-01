package com.secondbrain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * D-010: "Claude extracts temporal *intent* ... `TimeResolver` converts it to an
 * absolute `ZonedDateTime` using `java.time`." Deterministic Kotlin, never the
 * model — models emit wrong absolute timestamps, particularly around relative
 * dates, DST, and year rollover.
 *
 * Lives in `:model`, not `:vault` as CLAUDE.md's module map has it — `:agent`'s
 * calendar tools need this directly and `:agent` may not depend on `:vault`
 * (§1). `:model` costs nothing extra: `java.time` is JDK-standard. See the
 * Step 5/6 plan; this supersedes CLAUDE.md's module map for this one type.
 *
 * [resolve] never guesses. Any ambiguity — a bare hour with no AM/PM, a date
 * phrase it cannot place, a missing duration on a timed event — comes back as
 * [TimeResolution.Ambiguous] with a spoken question, per EC-C1: "never default."
 */
object TimeResolver {

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun resolve(expr: TimeExpression, utteranceAt: Instant, zone: ZoneId): TimeResolution {
        val date = parseDate(expr.datePhrase, utteranceAt, zone)
            ?: return TimeResolution.Ambiguous(
                Ambiguity.MISSING_DATE,
                "What day did you mean?",
            )

        // EC-C8: no stated time means all-day. Not an ambiguity - a positive,
        // deterministic default, stated explicitly in the caller's read-back.
        if (expr.allDay || expr.startHour == null) {
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            return TimeResolution.Resolved(ResolvedTimeRange(start, end, zone.id, allDay = true))
        }

        val hour = expr.startHour
        require(hour in 0..23 || (hour in 1..12)) { "startHour out of range: $hour" }

        // EC-C1: "12" (or any bare hour) with no AM/PM and no 24h context is
        // ambiguous. Never default to one or the other.
        if (expr.startHourAmbiguous12h && expr.meridiem == null) {
            val question = if (hour == 12) "Did you mean noon or midnight?"
                else "Did you mean $hour in the morning, or $hour at night?"
            return TimeResolution.Ambiguous(Ambiguity.HOUR_12_OR_24, question)
        }

        val duration = expr.durationMinutes
            ?: return TimeResolution.Ambiguous(
                Ambiguity.MISSING_DURATION,
                "How long should I block? An hour?",
            )
        if (duration <= 0) {
            return TimeResolution.Ambiguous(Ambiguity.MISSING_DURATION, "How long should this run?")
        }

        val hour24 = if (expr.meridiem != null) to24Hour(hour, expr.meridiem) else hour
        if (hour24 !in 0..23) {
            return TimeResolution.Ambiguous(Ambiguity.HOUR_12_OR_24, "What time did you mean?")
        }

        val minute = expr.startMinute.coerceIn(0, 59)
        // ZonedDateTime.of silently shifts a local time that does not exist
        // (spring-forward) or is ambiguous (fall-back) using its own gap/overlap
        // rules rather than throwing - which is the correct, non-crashing
        // behaviour EC-C3 wants. Zone ID is stored, never a fixed offset.
        val startZdt = LocalDateTime.of(date, LocalTime.of(hour24, minute)).atZone(zone)
        val endZdt = startZdt.plusMinutes(duration.toLong())

        return TimeResolution.Resolved(
            ResolvedTimeRange(startZdt.toInstant(), endZdt.toInstant(), zone.id, allDay = false)
        )
    }

    private fun to24Hour(hour12: Int, meridiem: String): Int = when (meridiem.lowercase()) {
        "am" -> if (hour12 == 12) 0 else hour12
        "pm" -> if (hour12 == 12) 12 else hour12 + 12
        else -> hour12
    }

    /**
     * EC-C2: resolved against [utteranceAt] — the recording's *start* instant,
     * per [Utterance.startedAt]'s own doc comment — never the wall-clock time at
     * which this tool happens to be called, which after a clarifying question or
     * two can genuinely be minutes later, and matters exactly at a midnight
     * boundary.
     */
    private fun parseDate(phrase: String?, utteranceAt: Instant, zone: ZoneId): LocalDate? {
        if (phrase.isNullOrBlank()) return null
        val today = utteranceAt.atZone(zone).toLocalDate()
        val p = phrase.trim().lowercase()

        return when {
            p == "today" || p == "tonight" -> today
            p == "tomorrow" -> today.plusDays(1)
            p == "yesterday" -> today.minusDays(1)
            ISO_DATE.matches(p) -> runCatching { LocalDate.parse(p) }.getOrNull()
            else -> {
                val bareName = p.removePrefix("next ").removePrefix("this ").trim()
                val dow = WEEKDAYS[bareName] ?: return null
                // "next Tuesday" and bare "Tuesday" resolve the same way here:
                // the closest occurrence strictly AFTER today. Said on a Tuesday,
                // both mean next week's Tuesday, not today - if today were meant,
                // the user would say "today". D-010's own test list names this
                // exact case ("next Tuesday on a Tuesday").
                var d = today.plusDays(1)
                while (d.dayOfWeek != dow) d = d.plusDays(1)
                d
            }
        }
    }
}

/** One of the three ambiguity kinds ARCHITECTURE §5 WF-3 names. Never resolved by guessing. */
enum class Ambiguity { HOUR_12_OR_24, MISSING_DATE, MISSING_DURATION }

/** The intent Claude extracts from an utterance — never an absolute timestamp (D-010). */
data class TimeExpression(
    /** "tomorrow", "next tuesday", "2026-09-05", "today". Null if the user gave no day at all. */
    val datePhrase: String?,
    /** 0-23, or 1-12 when [startHourAmbiguous12h] and/or [meridiem] apply. Null means no time given (EC-C8). */
    val startHour: Int? = null,
    /** True for a bare number ("12", "3") spoken with no AM/PM and no 24h context (EC-C1). */
    val startHourAmbiguous12h: Boolean = false,
    val startMinute: Int = 0,
    /** "am" | "pm", present only when the user said it explicitly. */
    val meridiem: String? = null,
    val durationMinutes: Int? = null,
    /** True when the user gave no time at all - a whole-day event (EC-C8). */
    val allDay: Boolean = false,
)

/** An absolute, resolved span. Zone ID stored, never a fixed offset (EC-C3). */
data class ResolvedTimeRange(
    val start: Instant,
    val end: Instant,
    val zoneId: String,
    val allDay: Boolean,
)

sealed interface TimeResolution {
    data class Resolved(val range: ResolvedTimeRange) : TimeResolution

    /** Forces `ask_user` before a proposal is ever built (EC-C1). */
    data class Ambiguous(val kind: Ambiguity, val question: String) : TimeResolution
}
