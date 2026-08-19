package com.jmail.backend.mail.provider

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.ProviderException
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.mail.FolderType
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.time.Duration
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The IMAP provider against a real IMAP server.
 *
 * This is the path every on-premises Exchange mailbox takes, and the one most likely to
 * meet messages that break parsers. Running it against an actual server — rather than a
 * mocked `Store` — is what makes these assertions mean anything.
 */
class ImapMailProviderTest {

    // Both protocols on dynamic ports: SMTP to deliver the fixtures, IMAP to read them
    // back, and neither clashing with the local docker stack's mail server.
    @JvmField
    @RegisterExtension
    val greenMail: GreenMailExtension = GreenMailExtension(
        arrayOf(ServerSetupTest.SMTP.dynamicPort(), ServerSetupTest.IMAP.dynamicPort()),
    )
        .withConfiguration(
            com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig()
                .withUser("ada@example.com", "ada@example.com", "correct-password"),
        )

    private val tokenService: ProviderTokenService = mockk()
    private lateinit var provider: ImapMailProvider

    private val account: MailAccount
        get() = MailAccount(
            provider = AccountProvider.EXCHANGE,
            email = "ada@example.com",
            username = "ada@example.com",
            imapHost = "127.0.0.1",
            imapPort = greenMail.imap.port,
            smtpHost = "127.0.0.1",
            smtpPort = greenMail.smtp.port,
            useTls = false,
        )

    @BeforeEach
    fun setUp() {
        provider = ImapMailProvider(
            tokenService,
            JmailProperties(
                exchange = JmailProperties.ExchangeProperties(
                    connectTimeout = Duration.ofSeconds(5),
                    readTimeout = Duration.ofSeconds(5),
                ),
            ),
        )
        every { tokenService.passwordFor(any()) } returns "correct-password"
    }

    private var delivered = 0

    private fun deliver(subject: String, body: String = "Hello from the test suite") {
        GreenMailUtil.sendTextEmail(
            "ada@example.com",
            "sender@example.com",
            subject,
            body,
            greenMail.smtp.serverSetup,
        )
        delivered++
        greenMail.waitForIncomingEmail(delivered)
    }

    @Test
    fun folders_are_listed_from_the_server() {
        val folders = provider.listFolders(account)

        assertTrue(folders.any { it.type == FolderType.INBOX }, folders.map(RemoteFolder::name).toString())
    }

    @Test
    fun messages_are_fetched_newest_first_with_their_content() {
        deliver("First message", "The body of the first message")
        deliver("Second message", "The body of the second message")

        val page = provider.fetchMessages(
            account,
            RemoteFolder(remoteId = "INBOX", name = "INBOX", type = FolderType.INBOX),
            limit = 10,
        )

        assertEquals(2, page.messages.size)
        // The list is read newest first, matching how the UI renders it.
        assertEquals("Second message", page.messages.first().subject)

        val message = page.messages.first()
        assertEquals(EmailAddress("sender@example.com"), message.from.copy(name = null))
        assertTrue(message.to.any { it.address == "ada@example.com" })
        assertNotNull(message.bodyText)
        assertTrue(message.bodyText!!.contains("second message"))
        assertFalse(message.isRead, "a freshly delivered message is unread")
    }

    @Test
    fun paging_walks_backwards_through_the_mailbox() {
        repeat(5) { index -> deliver("Message $index") }

        val firstPage = provider.fetchMessages(
            account,
            RemoteFolder(remoteId = "INBOX", name = "INBOX"),
            limit = 2,
        )

        assertEquals(2, firstPage.messages.size)
        assertNotNull(firstPage.nextCursor)

        val secondPage = provider.fetchMessages(
            account,
            RemoteFolder(remoteId = "INBOX", name = "INBOX"),
            cursor = firstPage.nextCursor,
            limit = 2,
        )

        assertEquals(2, secondPage.messages.size)
        // Distinct pages, no overlap.
        val firstIds = firstPage.messages.map(RemoteMessage::remoteId).toSet()
        assertTrue(secondPage.messages.none { it.remoteId in firstIds })
    }

    @Test
    fun an_empty_mailbox_returns_nothing_rather_than_failing() {
        val page = provider.fetchMessages(account, RemoteFolder(remoteId = "INBOX", name = "INBOX"))

        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun a_folder_that_does_not_exist_returns_nothing() {
        val page = provider.fetchMessages(
            account,
            RemoteFolder(remoteId = "NoSuchFolder", name = "NoSuchFolder"),
        )

        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun the_since_filter_excludes_older_messages() {
        deliver("Old message")

        val page = provider.fetchMessages(
            account,
            RemoteFolder(remoteId = "INBOX", name = "INBOX"),
            since = java.time.Instant.now().plusSeconds(60),
        )

        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun marking_a_message_read_is_pushed_to_the_server() {
        deliver("Mark me read")
        val message = provider
            .fetchMessages(account, RemoteFolder(remoteId = "INBOX", name = "INBOX"))
            .messages
            .single()

        provider.applyFlags(account, message.remoteId, FlagUpdate(isRead = true, isStarred = true))

        val reloaded = provider
            .fetchMessages(account, RemoteFolder(remoteId = "INBOX", name = "INBOX"))
            .messages
            .single()

        assertTrue(reloaded.isRead)
        assertTrue(reloaded.isStarred)
    }

    @Test
    fun a_flag_change_for_an_unparseable_id_is_ignored_rather_than_thrown() {
        provider.applyFlags(account, "INBOX::not-a-number", FlagUpdate(isRead = true))
    }

    @Test
    fun wrong_credentials_ask_the_user_to_reconnect() {
        every { tokenService.passwordFor(any()) } returns "wrong-password"

        assertThrows<ReauthenticationRequiredException> { provider.listFolders(account) }
    }

    @Test
    fun an_account_with_no_imap_server_configured_is_reported_clearly() {
        val misconfigured = MailAccount(provider = AccountProvider.EXCHANGE, email = "ada@example.com")

        assertThrows<ProviderException> { provider.listFolders(misconfigured) }
    }

    @Test
    fun an_unreachable_server_is_reported_as_a_provider_failure() {
        val unreachable = MailAccount(
            provider = AccountProvider.EXCHANGE,
            email = "ada@example.com",
            username = "ada@example.com",
            imapHost = "127.0.0.1",
            imapPort = 1, // nothing listens here
            useTls = false,
        )

        assertThrows<ProviderException> { provider.listFolders(unreachable) }
    }

    @Test
    fun sending_without_an_smtp_server_is_reported_clearly() {
        val noSmtp = MailAccount(
            provider = AccountProvider.EXCHANGE,
            email = "ada@example.com",
            imapHost = "127.0.0.1",
            imapPort = greenMail.imap.port,
        )

        assertThrows<ProviderException> {
            provider.sendMessage(
                noSmtp,
                OutgoingMessage(to = listOf(EmailAddress("tom@example.com")), subject = "Hi", bodyText = "Hello"),
            )
        }
    }

    @Test
    fun downloading_an_attachment_from_a_plain_message_returns_nothing() {
        deliver("No attachments here")
        val message = provider
            .fetchMessages(account, RemoteFolder(remoteId = "INBOX", name = "INBOX"))
            .messages
            .single()

        assertEquals(null, provider.downloadAttachment(account, message.remoteId, "missing.pdf"))
        assertEquals(null, provider.downloadAttachment(account, "INBOX::nonsense", "missing.pdf"))
    }

    // ---- MIME shapes -------------------------------------------------------
    //
    // Real mail is not one text/plain part. These build each shape directly and run it
    // through `toRemoteMessage`, which is where the parsing lives: delivering every variant
    // through SMTP would be slower and would test GreenMail's encoder more than JMail's.

    private val mimeSession: Session = Session.getInstance(Properties())

    private val inbox = RemoteFolder(remoteId = "INBOX", name = "INBOX", type = FolderType.INBOX)

    private fun mime(build: MimeMessage.() -> Unit): MimeMessage =
        MimeMessage(mimeSession).apply {
            setFrom(InternetAddress("sender@example.com", "Grace Hopper"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, "ada@example.com")
            subject = "A subject"
            build()
            saveChanges()
        }

    @Test
    fun a_plain_text_message_keeps_its_text_and_has_no_html() {
        val parsed = provider.toRemoteMessage(mime { setText("Just words") }, inbox)

        assertEquals("Just words", parsed.bodyText?.trim())
        assertNull(parsed.bodyHtml)
        assertTrue(parsed.attachments.isEmpty())
        assertEquals("Grace Hopper", parsed.from.name)
        assertEquals("sender@example.com", parsed.from.address)
    }

    @Test
    fun an_alternative_message_keeps_both_representations() {
        // text/plain and text/html for the same content: the reader prefers the HTML and
        // falls back to the text, so losing either one degrades the message.
        val message = mime {
            val alternative = MimeMultipart("alternative")
            alternative.addBodyPart(MimeBodyPart().apply { setText("Plain version") })
            alternative.addBodyPart(
                MimeBodyPart().apply { setContent("<p>HTML version</p>", "text/html; charset=utf-8") },
            )
            setContent(alternative)
        }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertEquals("Plain version", parsed.bodyText?.trim())
        assertTrue(parsed.bodyHtml!!.contains("HTML version"), parsed.bodyHtml!!)
    }

    @Test
    fun an_attachment_is_listed_without_being_treated_as_the_body() {
        val message = mime {
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { setText("See attached") })
            mixed.addBodyPart(
                MimeBodyPart().apply {
                    setContent("id,total\n1,42\n", "text/csv")
                    fileName = "report.csv"
                    disposition = Part.ATTACHMENT
                },
            )
            setContent(mixed)
        }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertEquals("See attached", parsed.bodyText?.trim())
        assertEquals(1, parsed.attachments.size)
        assertEquals("report.csv", parsed.attachments.single().filename)
        assertEquals("text/csv", parsed.attachments.single().mimeType)
        assertFalse(parsed.attachments.single().isInline)
    }

    @Test
    fun an_inline_image_is_marked_inline_rather_than_offered_as_a_download() {
        val message = mime {
            val related = MimeMultipart("related")
            related.addBodyPart(
                MimeBodyPart().apply { setContent("<img src=\"cid:logo\">", "text/html") },
            )
            related.addBodyPart(
                MimeBodyPart().apply {
                    setContent(byteArrayOf(1, 2, 3), "image/png")
                    fileName = "logo.png"
                    disposition = Part.INLINE
                    setHeader("Content-ID", "<logo>")
                },
            )
            setContent(related)
        }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertEquals(1, parsed.attachments.size)
        assertTrue(parsed.attachments.single().isInline)
        assertTrue(parsed.bodyHtml!!.contains("cid:logo"))
    }

    @Test
    fun nested_multiparts_are_walked_to_the_bottom() {
        // multipart/mixed wrapping multipart/alternative is what most clients send once
        // there is an attachment, so the body sits two levels down.
        val message = mime {
            val alternative = MimeMultipart("alternative")
            alternative.addBodyPart(MimeBodyPart().apply { setText("Deep text") })
            alternative.addBodyPart(
                MimeBodyPart().apply { setContent("<p>Deep html</p>", "text/html") },
            )

            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { setContent(alternative) })
            mixed.addBodyPart(
                MimeBodyPart().apply {
                    setContent("data", "application/octet-stream")
                    fileName = "blob.bin"
                    disposition = Part.ATTACHMENT
                },
            )
            setContent(mixed)
        }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertEquals("Deep text", parsed.bodyText?.trim())
        assertTrue(parsed.bodyHtml!!.contains("Deep html"))
        assertEquals("blob.bin", parsed.attachments.single().filename)
    }

    @Test
    fun a_message_with_no_readable_body_still_parses() {
        // A calendar invite or a bare attachment has no text part at all. It must still
        // appear in the list rather than failing the whole sync.
        val message = mime { setContent(byteArrayOf(9, 9, 9), "application/octet-stream") }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertNull(parsed.bodyText)
        assertNull(parsed.bodyHtml)
    }

    @Test
    fun a_missing_subject_becomes_empty_rather_than_null() {
        val parsed = provider.toRemoteMessage(mime { subject = null }, inbox)

        assertEquals("", parsed.subject)
    }

    @Test
    fun an_unparseable_sender_falls_back_instead_of_failing_the_sync() {
        val message = MimeMessage(mimeSession).apply {
            setRecipients(jakarta.mail.Message.RecipientType.TO, "ada@example.com")
            subject = "No sender"
            setText("body")
            saveChanges()
        }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertEquals("unknown@invalid", parsed.from.address)
    }

    @Test
    fun addresses_are_canonicalised_and_every_recipient_is_kept() {
        val message = mime {
            setRecipients(jakarta.mail.Message.RecipientType.TO, "Ada@Example.COM, bob@example.com")
            setRecipients(jakarta.mail.Message.RecipientType.CC, "carol@example.com")
            setReplyTo(arrayOf(InternetAddress("noreply@example.com")))
            setText("body")
        }

        val parsed = provider.toRemoteMessage(message, inbox)

        assertEquals(listOf("ada@example.com", "bob@example.com"), parsed.to.map { it.address })
        assertEquals(listOf("carol@example.com"), parsed.cc.map { it.address })
        assertEquals("noreply@example.com", parsed.replyTo)
    }

    // ---- threading ---------------------------------------------------------

    @Test
    fun references_thread_a_reply_onto_its_conversation() {
        // IMAP has no conversation id, so References is the only thing tying a reply to
        // what it answers. Without it every reply starts its own thread.
        val message = mime {
            setHeader("References", "<original@example.com>")
            setHeader("Message-ID", "<reply@example.com>")
            setText("A reply")
        }

        assertEquals("<original@example.com>", provider.toRemoteMessage(message, inbox).threadId)
    }

    @Test
    fun in_reply_to_is_used_when_references_is_absent() {
        val message = mime {
            setHeader("In-Reply-To", "<original@example.com>")
            setHeader("Message-ID", "<reply@example.com>")
            setText("A reply")
        }

        assertEquals("<original@example.com>", provider.toRemoteMessage(message, inbox).threadId)
    }

    @Test
    fun a_message_starting_a_conversation_threads_on_its_own_id() {
        // Message-ID is set after saveChanges(), which regenerates it.
        val message = mime { setText("The first message") }
            .apply { setHeader("Message-ID", "<first@example.com>") }

        assertEquals("<first@example.com>", provider.toRemoteMessage(message, inbox).threadId)
    }

    // ---- sending -----------------------------------------------------------

    @Test
    fun an_account_with_no_smtp_server_says_so_before_trying_to_connect() {
        val failure = assertThrows<ProviderException> {
            provider.sendMessage(
                MailAccount(
                    provider = AccountProvider.EXCHANGE,
                    email = "ada@example.com",
                    username = "ada@example.com",
                    imapHost = "127.0.0.1",
                    imapPort = greenMail.imap.port,
                    smtpHost = null,
                    useTls = false,
                ),
                OutgoingMessage(
                    to = listOf(EmailAddress("someone@example.com")),
                    subject = "Hello",
                    bodyText = "Hi",
                ),
            )
        }

        assertTrue(failure.message.contains("SMTP"), failure.message)
    }

    @Test
    fun a_sent_message_arrives_and_its_id_is_returned() {
        val id = provider.sendMessage(
            account,
            OutgoingMessage(
                to = listOf(EmailAddress("ada@example.com")),
                subject = "Sent from the test",
                bodyText = "The body",
            ),
        )

        assertTrue(id.isNotBlank())
        greenMail.waitForIncomingEmail(++delivered)

        val received = greenMail.receivedMessages.last()
        assertEquals("Sent from the test", received.subject)
    }

    @Test
    fun a_rejected_password_when_sending_asks_the_user_to_reconnect() {
        every { tokenService.passwordFor(any()) } returns "wrong-password"

        assertThrows<ReauthenticationRequiredException> {
            provider.sendMessage(
                account,
                OutgoingMessage(
                    to = listOf(EmailAddress("ada@example.com")),
                    subject = "Will not send",
                    bodyText = "x",
                ),
            )
        }
    }
}
