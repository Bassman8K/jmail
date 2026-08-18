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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
}
