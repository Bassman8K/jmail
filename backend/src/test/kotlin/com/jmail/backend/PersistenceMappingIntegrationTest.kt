package com.jmail.backend

import com.jmail.backend.auth.RefreshToken
import com.jmail.backend.auth.RefreshTokenRepository
import com.jmail.backend.category.Category
import com.jmail.backend.category.CategoryRepository
import com.jmail.backend.category.CategoryRule
import com.jmail.backend.category.CategoryRuleRepository
import com.jmail.backend.category.RuleField
import com.jmail.backend.category.RuleOperation
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.mail.Attachment
import com.jmail.backend.mail.AttachmentRepository
import com.jmail.backend.mail.Folder
import com.jmail.backend.mail.FolderRepository
import com.jmail.backend.mail.FolderType
import com.jmail.backend.mail.Message
import com.jmail.backend.mail.MessageRepository
import com.jmail.backend.mail.SyncRun
import com.jmail.backend.mail.SyncRunRepository
import com.jmail.backend.mail.SyncStatus
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import com.jmail.backend.user.UiDensity
import com.jmail.backend.user.UiTheme
import com.jmail.backend.user.UserAccount
import com.jmail.backend.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises every entity against the real schema.
 *
 * Hibernate's `ddl-auto: validate` is deliberately switched off (it reports false mismatches
 * for TIMESTAMPTZ and generated columns on PostgreSQL), so this is what guarantees the
 * mapping and the Flyway migrations have not drifted apart: if a column is renamed, dropped
 * or retyped, one of these round trips fails.
 */
@Transactional
class PersistenceMappingIntegrationTest : AbstractIntegrationTest() {

    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var accounts: MailAccountRepository
    @Autowired private lateinit var folders: FolderRepository
    @Autowired private lateinit var messages: MessageRepository
    @Autowired private lateinit var attachments: AttachmentRepository
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var rules: CategoryRuleRepository
    @Autowired private lateinit var refreshTokens: RefreshTokenRepository
    @Autowired private lateinit var syncRuns: SyncRunRepository

    private fun persistUser(email: String = "mapping-${UUID.randomUUID()}@example.com"): UserAccount =
        users.save(
            UserAccount(
                email = email,
                displayName = "Mapping Test",
                avatarUrl = "https://example.com/a.png",
                locale = "en-GB",
                timezone = "Europe/London",
                density = UiDensity.COMPACT,
                theme = UiTheme.DARK,
            ),
        )

    private fun persistAccount(userId: UUID): MailAccount = accounts.save(
        MailAccount(
            userId = userId,
            provider = AccountProvider.EXCHANGE,
            providerAccountId = "provider-${UUID.randomUUID()}",
            email = "mailbox@example.com",
            displayName = "Mailbox",
            accessToken = "encrypted-access",
            refreshToken = "encrypted-refresh",
            tokenExpiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS),
            scopes = "openid email",
            imapHost = "imap.example.com",
            imapPort = 993,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            ewsUrl = "https://example.com/EWS/Exchange.asmx",
            username = "mailbox",
            passwordSecret = "encrypted-password",
            useTls = true,
            status = AccountStatus.CONNECTED,
            statusDetail = null,
            isPrimary = true,
            color = "#4F46E5",
            syncCursor = "cursor-1",
        ),
    )

    @Test
    fun `a user round trips with every preference column`() {
        val saved = persistUser()

        val loaded = users.findById(saved.id).orElseThrow()

        assertEquals("Mapping Test", loaded.displayName)
        assertEquals("en-GB", loaded.locale)
        assertEquals("Europe/London", loaded.timezone)
        assertEquals(UiDensity.COMPACT, loaded.density)
        assertEquals(UiTheme.DARK, loaded.theme)
        assertNotNull(loaded.createdAt)
    }

    @Test
    fun `an account round trips with both credential shapes`() {
        val user = persistUser()
        val saved = persistAccount(user.id)

        val loaded = accounts.findByIdAndUserId(saved.id, user.id)

        assertNotNull(loaded)
        assertEquals(AccountProvider.EXCHANGE, loaded.provider)
        assertEquals("encrypted-refresh", loaded.refreshToken)
        assertEquals("encrypted-password", loaded.passwordSecret)
        assertEquals(993, loaded.imapPort)
        assertEquals("https://example.com/EWS/Exchange.asmx", loaded.ewsUrl)
        assertTrue(loaded.isPrimary)
    }

    @Test
    fun `a message round trips with its json recipient lists intact`() {
        val user = persistUser()
        val account = persistAccount(user.id)
        val folder = folders.save(
            Folder(accountId = account.id, remoteId = "INBOX", name = "Inbox", path = "Inbox", type = FolderType.INBOX),
        )

        val saved = messages.save(
            Message(
                accountId = account.id,
                folderId = folder.id,
                remoteId = "remote-1",
                threadId = "thread-1",
                messageIdHeader = "<a@example.com>",
                inReplyTo = "<b@example.com>",
                listId = "list.example.com",
                subject = "Mapping test",
                snippet = "Snippet",
                bodyText = "Body text",
                bodyHtml = "<p>Body</p>",
                fromAddress = "sender@example.com",
                fromName = "Sender",
                toRecipients = listOf(
                    EmailAddress("one@example.com", "One"),
                    EmailAddress("two@example.com", "Two, with comma"),
                ),
                ccRecipients = listOf(EmailAddress("cc@example.com")),
                bccRecipients = emptyList(),
                replyTo = "reply@example.com",
                sentAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                isStarred = true,
                hasAttachments = true,
                sizeBytes = 4_096,
                labels = listOf("Label_1", "Label_2"),
                categoryConfidence = 0.75f,
            ),
        )

        val loaded = messages.findById(saved.id).orElseThrow()

        assertEquals(2, loaded.toRecipients.size)
        assertEquals("Two, with comma", loaded.toRecipients[1].name)
        assertEquals(listOf("Label_1", "Label_2"), loaded.labels)
        assertEquals(0.75f, loaded.categoryConfidence)
        assertTrue(loaded.isStarred)
        assertEquals("list.example.com", loaded.listId)
    }

    @Test
    fun `attachments are linked to their message`() {
        val user = persistUser()
        val account = persistAccount(user.id)
        val folder = folders.save(
            Folder(accountId = account.id, remoteId = "INBOX", name = "Inbox", path = "Inbox"),
        )
        val message = messages.save(
            Message(
                accountId = account.id,
                folderId = folder.id,
                remoteId = "remote-att",
                threadId = "thread-att",
                fromAddress = "sender@example.com",
            ),
        )

        attachments.save(
            Attachment(
                messageId = message.id,
                remoteId = "att-1",
                filename = "invoice.pdf",
                mimeType = "application/pdf",
                sizeBytes = 84_213,
                contentId = "cid-1",
                isInline = false,
            ),
        )

        val loaded = attachments.findAllByMessageId(message.id)

        assertEquals(1, loaded.size)
        assertEquals("invoice.pdf", loaded.first().filename)
        assertEquals(84_213, loaded.first().sizeBytes)
    }

    @Test
    fun `a category and its rules round trip`() {
        val user = persistUser()
        val category = categories.save(
            Category(
                userId = user.id,
                key = "mapping-${UUID.randomUUID().toString().take(8)}",
                name = "Mapping",
                description = "A category",
                color = "#123456",
                icon = "work",
                position = 12,
            ),
        )

        rules.save(
            CategoryRule(
                categoryId = category.id,
                field = RuleField.SENDER_DOMAIN,
                operation = RuleOperation.ENDS_WITH,
                value = "example.com",
                weight = 55,
            ),
        )

        val loadedRules = rules.findAllByCategoryIdOrderByWeightDesc(category.id)

        assertEquals(1, loadedRules.size)
        assertEquals(RuleField.SENDER_DOMAIN, loadedRules.first().field)
        assertEquals(RuleOperation.ENDS_WITH, loadedRules.first().operation)
        assertEquals(55, loadedRules.first().weight)
    }

    @Test
    fun `the built-in categories from the migration are visible to every user`() {
        val user = persistUser()

        val visible = categories.findVisibleFor(user.id)

        assertTrue(visible.any { it.key == "primary" && it.isSystem })
        assertTrue(visible.size >= 8, "expected the eight seeded system categories, saw ${visible.size}")
        // Ordered by position, which is what the sidebar renders directly.
        assertEquals(visible.map { it.position }.sorted(), visible.map { it.position })
    }

    @Test
    fun `a refresh token round trips including its revocation columns`() {
        val user = persistUser()
        val saved = refreshTokens.save(
            RefreshToken(
                userId = user.id,
                tokenHash = "a".repeat(64),
                expiresAt = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS),
                userAgent = "JMail/1.0",
                clientIp = "127.0.0.1",
            ),
        )

        val loaded = refreshTokens.findByTokenHash("a".repeat(64))

        assertNotNull(loaded)
        assertEquals(saved.id, loaded.id)
        assertEquals("JMail/1.0", loaded.userAgent)
        assertTrue(loaded.isUsable())
    }

    @Test
    fun `a sync run records its outcome`() {
        val user = persistUser()
        val account = persistAccount(user.id)

        val run = syncRuns.save(
            SyncRun(
                accountId = account.id,
                status = SyncStatus.SUCCEEDED,
                finishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                messagesAdded = 12,
                messagesUpdated = 3,
            ),
        )

        val latest = syncRuns.findFirstByAccountIdOrderByStartedAtDesc(account.id)

        assertNotNull(latest)
        assertEquals(run.id, latest.id)
        assertEquals(12, latest.messagesAdded)
        assertEquals(SyncStatus.SUCCEEDED, latest.status)
    }

    @Test
    fun `the grouped count projections work against the real schema`() {
        val user = persistUser()
        val account = persistAccount(user.id)
        val folder = folders.save(Folder(accountId = account.id, remoteId = "INBOX", name = "Inbox", path = "Inbox"))

        messages.saveAll(
            listOf(
                Message(accountId = account.id, folderId = folder.id, remoteId = "c1", threadId = "t1", fromAddress = "a@b.example", isRead = false),
                Message(accountId = account.id, folderId = folder.id, remoteId = "c2", threadId = "t2", fromAddress = "a@b.example", isRead = true),
            ),
        )

        val byFolder = messages.countsByFolder(listOf(account.id)).first { it.folderId == folder.id }

        assertEquals(2, byFolder.total)
        assertEquals(1, byFolder.unread)
    }

    @Test
    fun `native full-text search runs against the generated tsvector column`() {
        val user = persistUser()
        val account = persistAccount(user.id)
        val folder = folders.save(Folder(accountId = account.id, remoteId = "INBOX", name = "Inbox", path = "Inbox"))

        messages.save(
            Message(
                accountId = account.id,
                folderId = folder.id,
                remoteId = "search-1",
                threadId = "search-thread",
                subject = "Quarterly invoice for consulting",
                bodyText = "The attached invoice covers the consulting engagement.",
                fromAddress = "billing@example.com",
            ),
        )
        messages.flush() // the generated column is computed on write

        val results = messages.search(listOf(account.id), "invoice", PageRequest.of(0, 10))

        assertTrue(results.totalElements >= 1)
        assertTrue(results.content.any { it.remoteId == "search-1" })
    }
}
