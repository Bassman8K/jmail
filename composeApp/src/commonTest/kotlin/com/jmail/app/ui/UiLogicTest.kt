package com.jmail.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.WindowSizeClass
import com.jmail.app.ui.components.colorForAddress
import com.jmail.app.ui.components.contrastingForeground
import com.jmail.app.ui.mailbox.describeMessage
import com.jmail.app.ui.reader.BodyBlock
import com.jmail.app.ui.reader.annotateLinks
import com.jmail.app.ui.reader.splitIntoBlocks
import com.jmail.app.ui.theme.AccountAccentColors
import com.jmail.app.ui.theme.Spacing
import com.jmail.app.ui.theme.parseHexColor
import com.jmail.shared.model.Category
import com.jmail.shared.model.EmailAddress
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.model.UiDensity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of the UI layer that are logic rather than layout.
 *
 * Whole screens are excluded from coverage on purpose — their behaviour lives in the shared
 * stores, which are tested directly. What is tested here is everything a screen *calls*: the
 * body parser, the responsive breakpoints, the colour derivations and the accessibility
 * description, all of which can be wrong in ways no compiler will catch.
 */
class UiLogicTest {

    // ---- layout ----------------------------------------------------------

    @Test
    fun window_size_classes_follow_the_breakpoints() {
        assertEquals(WindowSizeClass.COMPACT, WindowSizeClass.forWidth(360.dp))
        assertEquals(WindowSizeClass.COMPACT, WindowSizeClass.forWidth(639.dp))
        assertEquals(WindowSizeClass.MEDIUM, WindowSizeClass.forWidth(640.dp))
        assertEquals(WindowSizeClass.MEDIUM, WindowSizeClass.forWidth(1_039.dp))
        assertEquals(WindowSizeClass.EXPANDED, WindowSizeClass.forWidth(1_040.dp))
        assertEquals(WindowSizeClass.EXPANDED, WindowSizeClass.forWidth(1_920.dp))
    }

    @Test
    fun a_phone_shows_one_pane_and_a_desktop_shows_three() {
        assertFalse(WindowSizeClass.COMPACT.showsListAndReaderTogether)
        assertFalse(WindowSizeClass.COMPACT.showsPersistentSidebar)

        assertTrue(WindowSizeClass.MEDIUM.showsListAndReaderTogether)
        assertFalse(WindowSizeClass.MEDIUM.showsPersistentSidebar)

        assertTrue(WindowSizeClass.EXPANDED.showsListAndReaderTogether)
        assertTrue(WindowSizeClass.EXPANDED.showsPersistentSidebar)
    }

    @Test
    fun density_changes_row_height_but_never_type_size() {
        val compact = Spacing.forDensity(UiDensity.COMPACT)
        val comfortable = Spacing.forDensity(UiDensity.COMFORTABLE)
        val spacious = Spacing.forDensity(UiDensity.SPACIOUS)

        assertTrue(compact.rowVertical < comfortable.rowVertical)
        assertTrue(comfortable.rowVertical < spacious.rowVertical)

        // The touch target floor is an accessibility guarantee and must not vary.
        assertEquals(comfortable.minimumTouchTarget, compact.minimumTouchTarget)
        assertEquals(comfortable.minimumTouchTarget, spacious.minimumTouchTarget)
    }

    // ---- colour ----------------------------------------------------------

    @Test
    fun the_same_sender_always_gets_the_same_avatar_colour() {
        val first = colorForAddress("ada@example.com")
        val second = colorForAddress("ada@example.com")

        assertEquals(first, second)
        assertContains(AccountAccentColors, first)
    }

    @Test
    fun different_senders_generally_get_different_colours() {
        val addresses = listOf(
            "ada@example.com",
            "tom@example.com",
            "priya@example.com",
            "hana@example.com",
            "daniel@example.com",
        )

        val distinct = addresses.map(::colorForAddress).distinct()

        assertTrue(distinct.size > 1, "a constant colour would make avatars useless for recognition")
    }

    @Test
    fun an_empty_address_still_produces_a_colour() {
        assertContains(AccountAccentColors, colorForAddress(""))
    }

    @Test
    fun avatar_text_contrasts_with_whatever_colour_was_picked() {
        assertEquals(Color.White, contrastingForeground(Color(0xFF15171C)))
        assertEquals(Color(0xFF15171C), contrastingForeground(Color(0xFFFDE68A)))

        // Whatever the palette holds, the derived foreground is one of the two safe choices.
        AccountAccentColors.forEach { background ->
            val foreground = contrastingForeground(background)
            assertTrue(foreground == Color.White || foreground == Color(0xFF15171C))
        }
    }

    @Test
    fun hex_colours_from_the_api_are_parsed_and_bad_ones_fall_back() {
        assertEquals(Color(0xFF4F46E5), parseHexColor("#4F46E5"))
        assertEquals(Color(0xFF4F46E5), parseHexColor("4F46E5"))
        assertEquals(Color(0x804F46E5), parseHexColor("#804F46E5"))

        val fallback = Color(0xFF123456)
        assertEquals(fallback, parseHexColor(null, fallback))
        assertEquals(fallback, parseHexColor("", fallback))
        assertEquals(fallback, parseHexColor("not-a-colour", fallback))
        assertEquals(fallback, parseHexColor("#FFF", fallback)) // three-digit hex is not supported
    }

    // ---- message body parsing --------------------------------------------

    @Test
    fun a_plain_body_is_one_paragraph_per_block() {
        val blocks = splitIntoBlocks("First paragraph\n\nSecond paragraph")

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is BodyBlock.Paragraph })
        assertEquals("First paragraph", (blocks[0] as BodyBlock.Paragraph).text)
    }

    @Test
    fun consecutive_quoted_lines_are_grouped_into_one_block() {
        val body = """
            Thanks, that works.

            > Are you free Thursday?
            > We could do 10am.

            See you then.
        """.trimIndent()

        val blocks = splitIntoBlocks(body)

        val quotes = blocks.filterIsInstance<BodyBlock.Quote>()
        assertEquals(1, quotes.size, "the quoted section should be a single block, not one per line")
        assertEquals(2, quotes.single().lines.size)
        assertEquals(2, blocks.filterIsInstance<BodyBlock.Paragraph>().size)
    }

    @Test
    fun an_empty_body_produces_no_blocks() {
        assertTrue(splitIntoBlocks("").isEmpty())
        assertTrue(splitIntoBlocks("   \n\n  ").isEmpty())
    }

    @Test
    fun a_body_that_is_entirely_quoted_still_parses() {
        val blocks = splitIntoBlocks("> only quoted content")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is BodyBlock.Quote)
    }

    @Test
    fun links_are_detected_without_swallowing_the_sentence_punctuation() {
        val annotated = annotateLinks("See https://example.com/order?id=1. Thanks!")

        val annotations = annotated.getStringAnnotations("url", 0, annotated.length)
        assertEquals(1, annotations.size)
        // The trailing full stop belongs to the sentence, not the URL.
        assertEquals("https://example.com/order?id=1", annotations.single().item)
        assertTrue(annotated.text.endsWith("Thanks!"))
    }

    @Test
    fun email_addresses_become_mailto_links() {
        val annotated = annotateLinks("Write to ada@example.com for details")

        val annotation = annotated.getStringAnnotations("url", 0, annotated.length).single()
        assertEquals("mailto:ada@example.com", annotation.item)
    }

    @Test
    fun several_links_in_one_paragraph_are_all_found() {
        val annotated = annotateLinks("http://a.example and https://b.example and c@d.example")

        assertEquals(3, annotated.getStringAnnotations("url", 0, annotated.length).size)
    }

    @Test
    fun text_with_no_links_is_left_exactly_as_it_was() {
        val text = "Nothing to link here."
        val annotated = annotateLinks(text)

        assertEquals(text, annotated.text)
        assertTrue(annotated.getStringAnnotations("url", 0, annotated.length).isEmpty())
    }

    // ---- accessibility ---------------------------------------------------

    private fun message(
        isRead: Boolean = false,
        isStarred: Boolean = false,
        hasAttachments: Boolean = false,
        subject: String = "Quarterly review",
    ) = MessageSummary(
        id = "m1",
        accountId = "acc-1",
        folderId = "fol-1",
        threadId = "t1",
        subject = subject,
        snippet = "Agenda attached",
        from = EmailAddress("priya@example.com", "Priya Raman"),
        receivedAt = "2024-04-25T10:00:00Z",
        isRead = isRead,
        isStarred = isStarred,
        hasAttachments = hasAttachments,
    )

    @Test
    fun a_row_is_announced_read_state_first() {
        val unread = describeMessage(message(isRead = false), category = null)
        val read = describeMessage(message(isRead = true), category = null)

        assertTrue(unread.startsWith("Unread"), unread)
        assertTrue(read.startsWith("Read"), read)
    }

    @Test
    fun the_row_description_carries_everything_shown_visually() {
        val category = Category(id = "c1", key = "receipts", name = "Receipts")
        val description = describeMessage(
            message(isStarred = true, hasAttachments = true),
            category = category,
        )

        assertContains(description, "Priya Raman")
        assertContains(description, "Quarterly review")
        assertContains(description, "Has attachments")
        assertContains(description, "Starred")
        assertContains(description, "Receipts")
        assertContains(description, "Agenda attached")
    }

    @Test
    fun a_message_with_no_subject_is_still_announced_meaningfully() {
        val description = describeMessage(message(subject = ""), category = null)

        assertContains(description, "(no subject)")
    }

    @Test
    fun message_summaries_expose_the_display_fallbacks_the_ui_relies_on() {
        assertEquals("(no subject)", message(subject = "").displaySubject)
        assertEquals("Quarterly review", message().displaySubject)
    }
}
