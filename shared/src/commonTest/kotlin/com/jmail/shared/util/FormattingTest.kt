package com.jmail.shared.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Timestamp formatting is the sort of code that looks obviously correct and is wrong at
 * midnight, at year boundaries, and when the server clock is slightly ahead. Every case here
 * is pinned to a fixed "now" so it means the same thing on every machine and in every month.
 */
class FormattingTest {

    private val utc = TimeZone.UTC

    // Thursday, 25 April 2024, 14:30 UTC.
    private val now = Instant.parse("2024-04-25T14:30:00Z")

    @Test
    fun today_shows_the_time_of_day() {
        assertEquals("09:15", Formatting.listTimestamp("2024-04-25T09:15:00Z", now, utc))
        assertEquals("00:05", Formatting.listTimestamp("2024-04-25T00:05:00Z", now, utc))
    }

    @Test
    fun yesterday_is_named_rather_than_dated() {
        assertEquals("Yesterday", Formatting.listTimestamp("2024-04-24T23:59:00Z", now, utc))
    }

    @Test
    fun earlier_this_week_shows_the_weekday() {
        // Monday 22 April, three days before the fixed "now".
        assertEquals("Monday", Formatting.listTimestamp("2024-04-22T10:00:00Z", now, utc))
    }

    @Test
    fun earlier_this_year_shows_day_and_month_without_the_year() {
        assertEquals("2 Feb", Formatting.listTimestamp("2024-02-02T10:00:00Z", now, utc))
    }

    @Test
    fun a_previous_year_includes_the_year() {
        assertEquals("2 Feb 2023", Formatting.listTimestamp("2023-02-02T10:00:00Z", now, utc))
    }

    @Test
    fun a_message_from_one_minute_after_midnight_is_today_not_yesterday() {
        // The boundary that catches naive "less than 24 hours" implementations.
        assertEquals("00:01", Formatting.listTimestamp("2024-04-25T00:01:00Z", now, utc))
    }

    @Test
    fun an_unparseable_timestamp_renders_as_nothing_rather_than_crashing() {
        assertEquals("", Formatting.listTimestamp("not a timestamp", now, utc))
        assertEquals("", Formatting.detailTimestamp("nonsense"))
    }

    @Test
    fun detail_timestamp_is_unambiguous() {
        val formatted = Formatting.detailTimestamp("2024-04-25T09:15:00Z", utc)

        assertTrue(formatted.contains("Thursday"), formatted)
        assertTrue(formatted.contains("25"), formatted)
        assertTrue(formatted.contains("Apr"), formatted)
        assertTrue(formatted.contains("2024"), formatted)
        assertTrue(formatted.contains("09:15"), formatted)
    }

    @Test
    fun relative_time_reads_as_prose() {
        assertEquals("just now", Formatting.relativeTime("2024-04-25T14:29:30Z", now))
        assertEquals("a minute ago", Formatting.relativeTime("2024-04-25T14:28:40Z", now))
        assertEquals("5 minutes ago", Formatting.relativeTime("2024-04-25T14:25:00Z", now))
        assertEquals("an hour ago", Formatting.relativeTime("2024-04-25T13:00:00Z", now))
        assertEquals("3 hours ago", Formatting.relativeTime("2024-04-25T11:00:00Z", now))
        assertEquals("yesterday", Formatting.relativeTime("2024-04-24T10:00:00Z", now))
        assertEquals("3 days ago", Formatting.relativeTime("2024-04-22T10:00:00Z", now))
        assertEquals("a while ago", Formatting.relativeTime("2023-04-22T10:00:00Z", now))
    }

    @Test
    fun a_never_synced_account_says_never() {
        assertEquals("never", Formatting.relativeTime(null, now))
    }

    @Test
    fun a_server_clock_slightly_ahead_reads_as_just_now_rather_than_the_future() {
        assertEquals("just now", Formatting.relativeTime("2024-04-25T14:30:30Z", now))
    }

    @Test
    fun date_groups_match_the_list_headers() {
        assertEquals("Today", Formatting.dateGroup("2024-04-25T01:00:00Z", now, utc))
        assertEquals("Yesterday", Formatting.dateGroup("2024-04-24T23:00:00Z", now, utc))
        assertEquals("This week", Formatting.dateGroup("2024-04-21T10:00:00Z", now, utc))
        assertEquals("This month", Formatting.dateGroup("2024-04-05T10:00:00Z", now, utc))
        assertEquals("Earlier", Formatting.dateGroup("2024-01-05T10:00:00Z", now, utc))
    }

    @Test
    fun file_sizes_use_the_precision_that_is_actually_useful() {
        assertEquals("0 B", Formatting.fileSize(0))
        assertEquals("512 B", Formatting.fileSize(512))
        assertEquals("1 KB", Formatting.fileSize(1_024))
        assertEquals("84 KB", Formatting.fileSize(86_016))
        assertEquals("1.4 MB", Formatting.fileSize(1_468_006))
        assertEquals("2.0 GB", Formatting.fileSize(2_147_483_648))
    }

    @Test
    fun a_negative_size_is_clamped_rather_than_rendered() {
        assertEquals("0 B", Formatting.fileSize(-1))
    }

    @Test
    fun badge_counts_stop_being_exact_when_exactness_stops_helping() {
        assertEquals("", Formatting.badgeCount(0))
        assertEquals("", Formatting.badgeCount(-3))
        assertEquals("7", Formatting.badgeCount(7))
        assertEquals("999", Formatting.badgeCount(999))
        assertEquals("1k+", Formatting.badgeCount(1_400))
        assertEquals("9k+", Formatting.badgeCount(50_000))
    }

    @Test
    fun participant_summaries_read_naturally_at_every_length() {
        assertEquals("", Formatting.participantSummary(emptyList()))
        assertEquals("Ada", Formatting.participantSummary(listOf("Ada")))
        assertEquals("Ada and Tom", Formatting.participantSummary(listOf("Ada", "Tom")))
        assertEquals("Ada, Tom and Priya", Formatting.participantSummary(listOf("Ada", "Tom", "Priya")))
        assertEquals(
            "Ada, Tom and 3 others",
            Formatting.participantSummary(listOf("Ada", "Tom", "Priya", "Hana", "Daniel")),
        )
    }

    @Test
    fun the_clock_can_be_frozen_for_deterministic_tests() {
        val pinned = Instant.parse("2020-01-01T00:00:00Z")
        try {
            Clock.setForTesting(pinned)
            assertEquals(pinned, Clock.now())
        } finally {
            Clock.setForTesting(null)
        }

        assertTrue(Clock.now() > pinned)
    }
}
