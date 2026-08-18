package com.jmail.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The small conveniences the UI leans on. Each one has a fallback path that only shows up
 * with real mail — an address with no display name, a message with no subject, a provider
 * that has gone quiet.
 */
class ModelsTest {

    @Test
    fun an_address_prefers_its_display_name_but_never_renders_blank() {
        assertEquals("Ada Lovelace", EmailAddress("ada@example.com", "Ada Lovelace").displayLabel)
        assertEquals("ada@example.com", EmailAddress("ada@example.com").displayLabel)
        assertEquals("ada@example.com", EmailAddress("ada@example.com", "  ").displayLabel)
    }

    @Test
    fun initials_come_from_the_name_where_there_is_one() {
        assertEquals("AL", EmailAddress("ada@example.com", "Ada Lovelace").initials)
        assertEquals("A", EmailAddress("ada@example.com", "Ada").initials)
        // Only the first two words, so the avatar never overflows.
        assertEquals("AL", EmailAddress("x@example.com", "Ada Lovelace Byron").initials)
    }

    @Test
    fun initials_fall_back_to_the_local_part_and_split_on_its_separators() {
        assertEquals("AL", EmailAddress("ada.lovelace@example.com").initials)
        assertEquals("AL", EmailAddress("ada_lovelace@example.com").initials)
        assertEquals("AL", EmailAddress("ada-lovelace@example.com").initials)
        assertEquals("A", EmailAddress("ada@example.com").initials)
    }

    @Test
    fun an_unusable_address_still_produces_something_to_draw() {
        assertEquals("?", EmailAddress("").initials)
        assertEquals("?", EmailAddress("@").initials)
    }

    private fun summary(
        subject: String = "Quarterly review",
        categoryId: String? = null,
        confidence: Float = 0f,
    ) = MessageSummary(
        id = "m1",
        accountId = "a1",
        folderId = "f1",
        threadId = "t1",
        subject = subject,
        snippet = "…",
        from = EmailAddress("priya@example.com"),
        receivedAt = "2024-04-25T10:00:00Z",
        categoryId = categoryId,
        categoryConfidence = confidence,
    )

    @Test
    fun a_missing_subject_is_labelled_rather_than_left_blank() {
        assertEquals("(no subject)", summary(subject = "").displaySubject)
        assertEquals("Quarterly review", summary().displaySubject)
    }

    @Test
    fun low_confidence_is_only_flagged_when_a_category_was_actually_assigned() {
        assertTrue(summary(categoryId = "c1", confidence = 0.2f).isLowConfidence)
        assertFalse(summary(categoryId = "c1", confidence = 0.9f).isLowConfidence)
        // Uncategorised is not the same as "we guessed badly".
        assertFalse(summary(categoryId = null, confidence = 0f).isLowConfidence)
    }

    @Test
    fun a_message_detail_also_labels_a_missing_subject() {
        val detail = MessageDetail(
            id = "m1",
            accountId = "a1",
            folderId = "f1",
            threadId = "t1",
            subject = "",
            from = EmailAddress("priya@example.com"),
            sentAt = "2024-04-25T10:00:00Z",
            receivedAt = "2024-04-25T10:00:00Z",
        )

        assertEquals("(no subject)", detail.displaySubject)
    }

    @Test
    fun an_account_reports_when_it_needs_the_user_to_do_something() {
        fun account(status: AccountStatus) = MailAccount(
            id = "a1",
            provider = AccountProvider.GOOGLE,
            providerName = "Google",
            email = "ada@example.com",
            displayName = "Ada",
            status = status,
        )

        assertTrue(account(AccountStatus.REAUTH_REQUIRED).needsAttention)
        assertTrue(account(AccountStatus.ERROR).needsAttention)
        assertFalse(account(AccountStatus.CONNECTED).needsAttention)
        assertFalse(account(AccountStatus.SYNCING).needsAttention)
        assertFalse(account(AccountStatus.DISABLED).needsAttention)
    }

    @Test
    fun every_provider_has_a_label_a_person_would_recognise() {
        assertEquals("Google", AccountProvider.GOOGLE.label)
        assertEquals("Microsoft", AccountProvider.MICROSOFT.label)
        assertEquals("Apple", AccountProvider.APPLE.label)
        assertEquals("Microsoft Exchange", AccountProvider.EXCHANGE.label)
        assertEquals("IMAP", AccountProvider.IMAP.label)
        assertEquals("Demo", AccountProvider.DEMO.label)
    }
}
