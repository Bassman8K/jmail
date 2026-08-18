package com.jmail.shared.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Presentation helpers shared by every platform.
 *
 * These live in `shared` rather than in the UI layer for one reason: they are the parts of
 * presentation that carry real logic — boundary conditions around midnight, plural forms,
 * unit thresholds — and they should be unit-tested once rather than eyeballed on four
 * platforms.
 */
object Formatting {

    /**
     * How a mail client stamps a message in a list: a time today, a weekday this week, and a
     * date beyond that. The shape tells you the age at a glance without reading the value.
     */
    fun listTimestamp(
        isoTimestamp: String,
        now: Instant = Clock.now(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val instant = parseInstant(isoTimestamp) ?: return ""
        val moment = instant.toLocalDateTime(timeZone)
        val today = now.toLocalDateTime(timeZone)

        val daysApart = today.date.toEpochDays() - moment.date.toEpochDays()

        return when {
            daysApart == 0 -> timeOfDay(moment)
            daysApart == 1 -> "Yesterday"
            daysApart in 2..6 -> WEEKDAYS[moment.dayOfWeek.ordinal]
            moment.year == today.year -> "${moment.dayOfMonth} ${MONTHS[moment.monthNumber - 1]}"
            else -> "${moment.dayOfMonth} ${MONTHS[moment.monthNumber - 1]} ${moment.year}"
        }
    }

    /** The full stamp shown in the reader, where precision matters more than brevity. */
    fun detailTimestamp(
        isoTimestamp: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val moment = parseInstant(isoTimestamp)?.toLocalDateTime(timeZone) ?: return ""
        return buildString {
            append(WEEKDAYS[moment.dayOfWeek.ordinal])
            append(", ")
            append(moment.dayOfMonth)
            append(' ')
            append(MONTHS[moment.monthNumber - 1])
            append(' ')
            append(moment.year)
            append(" at ")
            append(timeOfDay(moment))
        }
    }

    /** "3 minutes ago" — used for sync status, where elapsed time is the point. */
    fun relativeTime(isoTimestamp: String?, now: Instant = Clock.now()): String {
        val instant = isoTimestamp?.let(::parseInstant) ?: return "never"
        val seconds = (now - instant).inWholeSeconds

        return when {
            seconds < 0 -> "just now" // clock skew between client and server
            seconds < 45 -> "just now"
            seconds < 90 -> "a minute ago"
            seconds < 3_600 -> "${seconds / 60} minutes ago"
            seconds < 7_200 -> "an hour ago"
            seconds < 86_400 -> "${seconds / 3_600} hours ago"
            seconds < 172_800 -> "yesterday"
            seconds < 2_592_000 -> "${seconds / 86_400} days ago"
            else -> "a while ago"
        }
    }

    /** The heading a message belongs under in a date-grouped list. */
    fun dateGroup(
        isoTimestamp: String,
        now: Instant = Clock.now(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val instant = parseInstant(isoTimestamp) ?: return "Earlier"
        val daysApart = now.toLocalDateTime(timeZone).date.toEpochDays() -
            instant.toLocalDateTime(timeZone).date.toEpochDays()

        return when {
            daysApart <= 0 -> "Today"
            daysApart == 1 -> "Yesterday"
            daysApart in 2..6 -> "This week"
            daysApart in 7..30 -> "This month"
            else -> "Earlier"
        }
    }

    /**
     * File sizes in the units people expect from a mail client: no decimals below a
     * megabyte, one above, because "1.4 MB" is useful and "1434.2 KB" is not.
     */
    fun fileSize(bytes: Long): String = when {
        bytes < 0 -> "0 B"
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "${bytes / 1_024} KB"
        bytes < 1_073_741_824 -> "${oneDecimal(bytes.toDouble() / 1_048_576)} MB"
        else -> "${oneDecimal(bytes.toDouble() / 1_073_741_824)} GB"
    }

    /** Badge counts stop being useful as exact numbers past a point. */
    fun badgeCount(count: Long): String = when {
        count <= 0 -> ""
        count < 1_000 -> count.toString()
        count < 10_000 -> "${count / 1_000}k+"
        else -> "9k+"
    }

    /** "Ada, Tom and 3 others" — the participant line on a thread. */
    fun participantSummary(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        3 -> "${names[0]}, ${names[1]} and ${names[2]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} others"
    }

    private fun timeOfDay(moment: LocalDateTime): String {
        val minute = moment.minute.toString().padStart(2, '0')
        return "${moment.hour.toString().padStart(2, '0')}:$minute"
    }

    /** Rounds rather than truncates: 1.399 MB reads as "1.4 MB", not "1.3 MB". */
    private fun oneDecimal(value: Double): String {
        val scaled = kotlin.math.round(value * 10).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    internal fun parseInstant(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}

/**
 * The clock the app reads.
 *
 * Indirection so tests can freeze time: every timestamp helper above takes `now` as a
 * parameter defaulted to this, which keeps their output deterministic under test without
 * threading a clock through the whole UI.
 */
object Clock {

    private var override: Instant? = null

    fun now(): Instant = override ?: kotlinx.datetime.Clock.System.now()

    /** Test-only: pin the clock, or pass null to release it. */
    fun setForTesting(instant: Instant?) {
        override = instant
    }
}
