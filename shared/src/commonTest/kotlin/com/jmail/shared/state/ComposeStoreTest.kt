package com.jmail.shared.state

import com.jmail.shared.awaitUntil
import com.jmail.shared.fakeApiClient
import com.jmail.shared.messageDetailJson
import com.jmail.shared.model.EmailAddress
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.repository.MailRepository
import com.jmail.shared.settle
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reply pre-fill is the part of a composer people notice when it is wrong: the wrong people
 * on the To line, "Re: Re: Re:" in the subject, or your own address among the recipients.
 */
class ComposeStoreTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun store(
        routes: Map<String, Pair<String, HttpStatusCode>> = mapOf(
            "/messages" to (messageDetailJson() to HttpStatusCode.OK),
        ),
    ) = ComposeStore(MailRepository(fakeApiClient(routes)), scope)

    private val original = MessageDetail(
        id = "msg-1",
        accountId = "acc-1",
        folderId = "fol-1",
        threadId = "thread-1",
        subject = "Design review",
        from = EmailAddress("priya@example.com", "Priya Raman"),
        to = listOf(EmailAddress("ada@example.com", "Ada"), EmailAddress("tom@example.com", "Tom")),
        cc = listOf(EmailAddress("hana@example.com", "Hana")),
        sentAt = "2024-04-25T10:00:00Z",
        receivedAt = "2024-04-25T10:00:00Z",
        bodyText = "Can we move this to Thursday?",
    )

    @Test
    fun a_new_message_starts_empty_and_open() {
        val store = store()

        store.newMessage("acc-1")

        val state = store.state.value
        assertTrue(state.isOpen)
        assertEquals(ComposeMode.NEW, state.mode)
        assertEquals("New message", state.title)
        assertTrue(state.to.isEmpty())
        assertFalse(state.canSend)
    }

    @Test
    fun reply_addresses_only_the_sender() {
        val store = store()

        store.reply(original, replyAll = false, selfAddress = "ada@example.com")

        val state = store.state.value
        assertEquals(listOf("priya@example.com"), state.to.map(EmailAddress::address))
        assertTrue(state.cc.isEmpty())
        assertEquals("Re: Design review", state.subject)
        assertEquals("thread-1", state.threadId)
        assertEquals("msg-1", state.inReplyToMessageId)
    }

    @Test
    fun reply_all_keeps_the_others_but_never_you() {
        val store = store()

        store.reply(original, replyAll = true, selfAddress = "ada@example.com")

        val state = store.state.value
        assertEquals(listOf("priya@example.com"), state.to.map(EmailAddress::address))
        // Tom and Hana carry over; Ada (the user) does not.
        assertEquals(setOf("tom@example.com", "hana@example.com"), state.cc.map(EmailAddress::address).toSet())
        assertTrue(state.showCcBcc)
    }

    @Test
    fun reply_all_does_not_duplicate_the_sender_into_cc() {
        val store = store()
        val selfInCc = original.copy(cc = original.cc + EmailAddress("priya@example.com", "Priya"))

        store.reply(selfInCc, replyAll = true, selfAddress = "ada@example.com")

        assertFalse(store.state.value.cc.any { it.address == "priya@example.com" })
    }

    @Test
    fun the_subject_prefix_is_added_once_however_deep_the_thread() {
        assertEquals("Re: Lunch", ComposeStore.prefixSubject("Lunch", "Re:"))
        assertEquals("Re: Lunch", ComposeStore.prefixSubject("Re: Lunch", "Re:"))
        // An existing prefix is recognised whatever its casing, and left exactly as the
        // sender wrote it — some clients thread on the subject line verbatim.
        assertEquals("re: Lunch", ComposeStore.prefixSubject("re: Lunch", "Re:"))
        assertEquals("Fwd: Lunch", ComposeStore.prefixSubject("Lunch", "Fwd:"))
        assertEquals("Re:", ComposeStore.prefixSubject("", "Re:"))
    }

    @Test
    fun forwarding_carries_the_original_but_no_recipients() {
        val store = store()

        store.forward(original)

        val state = store.state.value
        assertEquals(ComposeMode.FORWARD, state.mode)
        assertEquals("Fwd: Design review", state.subject)
        assertTrue(state.to.isEmpty())
        assertContains(state.body, "Forwarded message")
        assertContains(state.body, "Priya Raman")
    }

    @Test
    fun quoted_text_is_prefixed_line_by_line() {
        val quoted = ComposeStore.quote(original.copy(bodyText = "Line one\nLine two"))

        assertContains(quoted, "> Line one")
        assertContains(quoted, "> Line two")
    }

    @Test
    fun typing_a_separator_commits_the_address() {
        val store = store()
        store.newMessage()

        store.updateToInput("tom@example.com,")

        assertEquals(listOf("tom@example.com"), store.state.value.to.map(EmailAddress::address))
        assertEquals("", store.state.value.toInput)
    }

    @Test
    fun an_implausible_address_is_reported_rather_than_accepted() {
        val store = store()
        store.newMessage()

        store.updateToInput("not-an-address")
        store.commitRecipient(ComposeStore.RecipientField.TO)

        assertTrue(store.state.value.to.isEmpty())
        assertNotNull(store.state.value.fieldErrors["to"])
    }

    @Test
    fun duplicate_recipients_are_collapsed() {
        val store = store()
        store.newMessage()

        store.updateToInput("tom@example.com,")
        store.updateToInput("TOM@example.com,")

        assertEquals(1, store.state.value.to.size)
    }

    @Test
    fun a_recipient_can_be_removed() {
        val store = store()
        store.newMessage()
        store.updateToInput("tom@example.com,")

        store.removeRecipient(ComposeStore.RecipientField.TO, "tom@example.com")

        assertTrue(store.state.value.to.isEmpty())
    }

    @Test
    fun address_plausibility_covers_the_shapes_people_actually_type() {
        assertTrue(ComposeStore.isPlausibleAddress("ada@example.com"))
        assertTrue(ComposeStore.isPlausibleAddress("ada.lovelace+jmail@sub.example.co.uk"))
        assertFalse(ComposeStore.isPlausibleAddress("ada"))
        assertFalse(ComposeStore.isPlausibleAddress("ada@"))
        assertFalse(ComposeStore.isPlausibleAddress("ada@example"))
        assertFalse(ComposeStore.isPlausibleAddress("ada@.com"))
        assertFalse(ComposeStore.isPlausibleAddress("ada@example."))
        assertFalse(ComposeStore.isPlausibleAddress("ada lovelace@example.com"))
        assertFalse(ComposeStore.isPlausibleAddress("a@b@c.com"))
    }

    @Test
    fun sending_requires_a_recipient_and_a_body() {
        val store = store()
        store.newMessage()

        store.send()
        assertNotNull(store.state.value.fieldErrors["to"])

        store.updateToInput("tom@example.com,")
        store.send()
        assertNotNull(store.state.value.fieldErrors["body"])
    }

    @Test
    fun an_uncommitted_recipient_is_still_sent_to() = runTest {
        val store = store()
        store.newMessage()
        store.updateBody("Hello")
        // Typed but never confirmed with Enter — the most common way to lose a recipient.
        store.updateToInput("tom@example.com")

        store.send()
        awaitUntil(describe = { "the composer to close after sending" }) { !store.state.value.isOpen }

        assertNotNull(store.state.value.sentMessage)
    }

    @Test
    fun a_failed_send_keeps_the_draft_on_screen() = runTest {
        val store = store(
            mapOf("/messages" to ("""{"code":"provider_error","message":"Gmail is down"}""" to HttpStatusCode.BadGateway)),
        )
        store.newMessage()
        store.updateToInput("tom@example.com,")
        store.updateBody("Hello")

        store.send()
        awaitUntil(describe = { "the send failure" }) { store.state.value.error != null }

        // Nothing is lost: the composer stays open with the content intact.
        assertTrue(store.state.value.isOpen)
        assertEquals("Hello", store.state.value.body)
        assertEquals("Gmail is down", store.state.value.error?.userMessage)
        assertFalse(store.state.value.isSending)
    }

    @Test
    fun unsaved_content_is_detected_so_closing_can_prompt() {
        val store = store()
        store.newMessage()
        assertFalse(store.state.value.hasUnsavedContent)

        store.updateBody("Half a thought")
        assertTrue(store.state.value.hasUnsavedContent)
    }

    @Test
    fun saving_a_draft_closes_the_composer() = runTest {
        val store = store()
        store.newMessage()
        store.updateToInput("tom@example.com,")
        store.updateBody("Draft body")

        store.saveDraft()
        awaitUntil(describe = { "the draft to save" }) { !store.state.value.isOpen }

        assertNull(store.state.value.sentMessage) // a draft is not a sent message
    }

    @Test
    fun saving_an_empty_draft_does_nothing() = runTest {
        val store = store()
        store.newMessage()

        store.saveDraft()
        settle(50)

        assertTrue(store.state.value.isOpen)
        assertFalse(store.state.value.isSavingDraft)
    }

    @Test
    fun closing_resets_everything() {
        val store = store()
        store.reply(original, replyAll = true, selfAddress = "ada@example.com")

        store.close()

        assertFalse(store.state.value.isOpen)
        assertTrue(store.state.value.to.isEmpty())
        assertEquals("", store.state.value.subject)
    }

    @Test
    fun the_title_reflects_why_the_composer_is_open() {
        val store = store()

        store.newMessage()
        assertEquals("New message", store.state.value.title)

        store.reply(original, replyAll = false, selfAddress = "ada@example.com")
        assertEquals("Reply", store.state.value.title)

        store.reply(original, replyAll = true, selfAddress = "ada@example.com")
        assertEquals("Reply all", store.state.value.title)

        store.forward(original)
        assertEquals("Forward", store.state.value.title)
    }

    // ---- the recipient chips, field by field -------------------------------
    //
    // Cc and Bcc go through the same code as To but different state, and a copy-paste slip
    // between them puts a Bcc recipient in the Cc line — a privacy failure, not a typo.

    @Test
    fun cc_and_bcc_commit_into_their_own_fields() {
        val store = store()

        store.updateCcInput("carol@example.com")
        store.commitRecipient(ComposeStore.RecipientField.CC)
        store.updateBccInput("dan@example.com")
        store.commitRecipient(ComposeStore.RecipientField.BCC)

        assertEquals(listOf("carol@example.com"), store.state.value.cc.map { it.address })
        assertEquals(listOf("dan@example.com"), store.state.value.bcc.map { it.address })
        assertTrue(store.state.value.to.isEmpty())
    }

    @Test
    fun a_separator_commits_in_cc_and_bcc_too() {
        val store = store()

        store.updateCcInput("carol@example.com,")
        store.updateBccInput("dan@example.com;")

        assertEquals(listOf("carol@example.com"), store.state.value.cc.map { it.address })
        assertEquals(listOf("dan@example.com"), store.state.value.bcc.map { it.address })
        // The input clears once the chip is made, or the address is sent twice.
        assertEquals("", store.state.value.ccInput)
        assertEquals("", store.state.value.bccInput)
    }

    @Test
    fun a_recipient_can_be_removed_from_cc_and_bcc() {
        val store = store()
        store.updateCcInput("carol@example.com,")
        store.updateBccInput("dan@example.com,")

        store.removeRecipient(ComposeStore.RecipientField.CC, "carol@example.com")
        store.removeRecipient(ComposeStore.RecipientField.BCC, "dan@example.com")

        assertTrue(store.state.value.cc.isEmpty())
        assertTrue(store.state.value.bcc.isEmpty())
    }

    @Test
    fun addresses_are_lowercased_so_the_same_person_is_one_chip() {
        val store = store()

        store.updateToInput("Ada@Example.COM,")
        store.updateToInput("ada@example.com,")

        assertEquals(listOf("ada@example.com"), store.state.value.to.map { it.address })
    }

    @Test
    fun a_trailing_space_commits_the_address_as_well() {
        val store = store()

        store.updateToInput("ada@example.com ")

        assertEquals(listOf("ada@example.com"), store.state.value.to.map { it.address })
    }

    @Test
    fun a_lone_separator_does_not_create_an_empty_chip() {
        val store = store()

        store.updateToInput(",")
        store.updateToInput("  ")

        assertTrue(store.state.value.to.isEmpty())
        assertTrue(store.state.value.fieldErrors.isEmpty())
    }

    @Test
    fun committing_an_empty_field_is_a_no_op_rather_than_an_error() {
        val store = store()

        store.commitRecipient(ComposeStore.RecipientField.TO)
        store.commitRecipient(ComposeStore.RecipientField.CC)
        store.commitRecipient(ComposeStore.RecipientField.BCC)

        assertTrue(store.state.value.to.isEmpty())
        assertTrue(store.state.value.fieldErrors.isEmpty())
    }

    @Test
    fun an_implausible_cc_address_is_reported_against_the_cc_field() {
        val store = store()

        store.updateCcInput("not-an-address")
        store.commitRecipient(ComposeStore.RecipientField.CC)

        // Reported against cc, not to, or the error appears under the wrong box.
        assertTrue(store.state.value.fieldErrors.containsKey("cc"), store.state.value.fieldErrors.toString())
        assertTrue(store.state.value.cc.isEmpty())
    }

    @Test
    fun uncommitted_cc_and_bcc_are_still_committed_when_sending() = runTest {
        // Typed but never confirmed with Enter is still meant to go, the same reason the To
        // field commits pending input. The send is made to fail so the draft stays on screen
        // and the committed chips can be inspected — a successful send clears the composer.
        val store = store(
            mapOf("/messages" to ("""{"code":"provider_error","message":"nope"}""" to HttpStatusCode.BadGateway)),
        )
        store.updateToInput("ada@example.com,")
        store.updateCcInput("carol@example.com")
        store.updateBccInput("dan@example.com")
        store.updateBody("Hello")

        store.send()
        settle()

        assertEquals(listOf("carol@example.com"), store.state.value.cc.map { it.address })
        assertEquals(listOf("dan@example.com"), store.state.value.bcc.map { it.address })
    }

    // ---- small toggles -----------------------------------------------------

    @Test
    fun the_cc_and_bcc_row_can_be_shown_and_hidden() {
        val store = store()
        assertFalse(store.state.value.showCcBcc)

        store.toggleCcBcc()
        assertTrue(store.state.value.showCcBcc)

        store.toggleCcBcc()
        assertFalse(store.state.value.showCcBcc)
    }

    @Test
    fun the_sending_account_can_be_chosen() {
        val store = store()

        store.setAccount("acc-2")

        assertEquals("acc-2", store.state.value.accountId)
    }

    @Test
    fun the_subject_can_be_edited() {
        val store = store()

        store.updateSubject("A new subject")

        assertEquals("A new subject", store.state.value.subject)
    }

    @Test
    fun an_error_can_be_dismissed_without_losing_the_draft() {
        val store = store()
        store.updateBody("Half-written thought")
        store.updateToInput("not-an-address")
        store.commitRecipient(ComposeStore.RecipientField.TO)

        store.dismissError()

        assertNull(store.state.value.error)
        // The draft itself must survive; losing it is what people never forgive.
        assertEquals("Half-written thought", store.state.value.body)
    }
}
