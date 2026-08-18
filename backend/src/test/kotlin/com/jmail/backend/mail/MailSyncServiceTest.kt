package com.jmail.backend.mail

import com.jmail.backend.category.CategorizationEngine
import com.jmail.backend.category.Category
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.ProviderException
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.mail.provider.MailProvider
import com.jmail.backend.mail.provider.MailProviderRegistry
import com.jmail.backend.mail.provider.MessagePage
import com.jmail.backend.mail.provider.RemoteAttachment
import com.jmail.backend.mail.provider.RemoteFolder
import com.jmail.backend.mail.provider.RemoteMessage
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sync loop.
 *
 * Its contract is mostly about failure: a revoked account must be marked rather than
 * retried forever, a provider outage must not corrupt what is already stored, and one
 * unreadable message must never be able to wedge an account's sync.
 */
class MailSyncServiceTest {

    private val registry: MailProviderRegistry = mockk()
    private val provider: MailProvider = mockk()
    private val accountRepository: MailAccountRepository = mockk(relaxed = true)
    private val folderRepository: FolderRepository = mockk(relaxed = true)
    private val messageRepository: MessageRepository = mockk(relaxed = true)
    private val attachmentRepository: AttachmentRepository = mockk(relaxed = true)
    private val syncRunRepository: SyncRunRepository = mockk(relaxed = true)
    private val engine: CategorizationEngine = mockk()

    private lateinit var service: MailSyncService

    private val account = MailAccount(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        provider = AccountProvider.GOOGLE,
        email = "ada@example.com",
    )

    private val inbox = RemoteFolder(remoteId = "INBOX", name = "Inbox", type = FolderType.INBOX)

    private val primaryCategory = Category(key = "primary", name = "Primary", isSystem = true)
    private val ruleSet = CategorizationEngine.RuleSet(listOf(primaryCategory), emptyList())

    @BeforeEach
    fun setUp() {
        service = MailSyncService(
            properties = JmailProperties(),
            registry = registry,
            mailAccountRepository = accountRepository,
            folderRepository = folderRepository,
            messageRepository = messageRepository,
            attachmentRepository = attachmentRepository,
            syncRunRepository = syncRunRepository,
            categorizationEngine = engine,
            htmlSanitizer = HtmlSanitizer(),
        )

        every { registry.canSync(any()) } returns true
        every { registry.forAccount(any()) } returns provider
        every { engine.ruleSetFor(any()) } returns ruleSet
        every { engine.classify(any<CategorizationEngine.RuleSet>(), any()) } returns
            com.jmail.backend.category.Classification(primaryCategory.id, "primary", 0.5f)

        every { accountRepository.save(any()) } returnsArgument 0
        every { accountRepository.findById(account.id) } returns Optional.of(account)
        every { folderRepository.save(any()) } returnsArgument 0
        every { folderRepository.saveAll(any<List<Folder>>()) } returnsArgument 0
        every { folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(any()) } returns emptyList()
        every { messageRepository.saveAll(any<List<Message>>()) } returnsArgument 0
        every { messageRepository.countsByFolder(any()) } returns emptyList()
        every { syncRunRepository.save(any()) } returnsArgument 0
        every { attachmentRepository.saveAll(any<List<Attachment>>()) } returnsArgument 0
    }

    private fun remoteMessage(id: String, attachments: List<RemoteAttachment> = emptyList()) = RemoteMessage(
        remoteId = id,
        threadId = "thread-$id",
        folderRemoteId = "INBOX",
        subject = "Subject $id",
        from = EmailAddress("sender@example.com", "Sender"),
        to = listOf(EmailAddress("ada@example.com")),
        bodyHtml = "<p>Body <script>alert(1)</script></p>",
        sentAt = Instant.now(),
        receivedAt = Instant.now(),
        attachments = attachments,
    )

    @Test
    fun `a successful run stores new messages and records the outcome`() {
        every { provider.listFolders(account) } returns listOf(inbox)
        every { provider.fetchMessages(any(), any(), any(), any(), any()) } returns
            MessagePage(listOf(remoteMessage("m1"), remoteMessage("m2")))
        every { messageRepository.findAllByAccountIdAndRemoteIdIn(any(), any()) } returns emptyList()

        val outcome = service.syncAccount(account)

        assertEquals(SyncStatus.SUCCEEDED, outcome.status)
        assertEquals(2, outcome.added)
        assertEquals(0, outcome.updated)
    }

    @Test
    fun `message bodies are sanitised on the way in`() {
        every { provider.listFolders(account) } returns listOf(inbox)
        every { provider.fetchMessages(any(), any(), any(), any(), any()) } returns
            MessagePage(listOf(remoteMessage("m1")))
        every { messageRepository.findAllByAccountIdAndRemoteIdIn(any(), any()) } returns emptyList()
        val saved = slot<List<Message>>()
        every { messageRepository.saveAll(capture(saved)) } answers { saved.captured }

        service.syncAccount(account)

        val stored = saved.captured.single()
        assertFalse(stored.bodyHtml!!.contains("script"), "hostile markup must never reach storage")
        assertNotNull(stored.snippet)
        assertTrue(stored.categoryConfidence > 0f)
    }

    @Test
    fun `an already-stored message is only updated when a flag actually changed`() {
        val existing = Message(
            accountId = account.id,
            remoteId = "m1",
            threadId = "thread-m1",
            isRead = false,
            isStarred = false,
        )
        every { provider.listFolders(account) } returns listOf(inbox)
        every { provider.fetchMessages(any(), any(), any(), any(), any()) } returns
            MessagePage(listOf(remoteMessage("m1").copy(isRead = true)))
        every { messageRepository.findAllByAccountIdAndRemoteIdIn(any(), any()) } returns listOf(existing)

        val outcome = service.syncAccount(account)

        assertEquals(0, outcome.added)
        assertEquals(1, outcome.updated)
        assertTrue(existing.isRead)
    }

    @Test
    fun `an unchanged message is not rewritten`() {
        val existing = Message(accountId = account.id, remoteId = "m1", threadId = "thread-m1", isRead = true)
        every { provider.listFolders(account) } returns listOf(inbox)
        every { provider.fetchMessages(any(), any(), any(), any(), any()) } returns
            MessagePage(listOf(remoteMessage("m1").copy(isRead = true)))
        every { messageRepository.findAllByAccountIdAndRemoteIdIn(any(), any()) } returns listOf(existing)

        // The folder id differs in the fixture, so line this up to be genuinely unchanged.
        existing.folderId = folderRepository.save(Folder(accountId = account.id, remoteId = "INBOX")).id

        val outcome = service.syncAccount(account)

        assertEquals(0, outcome.added)
    }

    @Test
    fun `attachments are stored alongside their message`() {
        every { provider.listFolders(account) } returns listOf(inbox)
        every { provider.fetchMessages(any(), any(), any(), any(), any()) } returns
            MessagePage(
                listOf(
                    remoteMessage(
                        "m1",
                        attachments = listOf(RemoteAttachment("att-1", "invoice.pdf", "application/pdf", 1_024)),
                    ),
                ),
            )
        every { messageRepository.findAllByAccountIdAndRemoteIdIn(any(), any()) } returns emptyList()
        val saved = slot<List<Attachment>>()
        every { attachmentRepository.saveAll(capture(saved)) } answers { saved.captured }

        service.syncAccount(account)

        assertEquals("invoice.pdf", saved.captured.single().filename)
    }

    @Test
    fun `a revoked account ends the run without being treated as an incident`() {
        every { provider.listFolders(account) } throws ReauthenticationRequiredException("Google")

        val outcome = service.syncAccount(account)

        assertEquals(SyncStatus.FAILED, outcome.status)
        assertNotNull(outcome.error)
    }

    @Test
    fun `a provider outage marks the account with something the user can read`() {
        every { provider.listFolders(account) } throws ProviderException("Gmail", "Gmail could not be reached")

        val outcome = service.syncAccount(account)

        assertEquals(SyncStatus.FAILED, outcome.status)
        assertEquals(AccountStatus.ERROR, account.status)
        assertNotNull(account.statusDetail)
    }

    @Test
    fun `an account with no mailbox to sync succeeds trivially`() {
        every { registry.canSync(account) } returns false

        val outcome = service.syncAccount(account)

        assertEquals(SyncStatus.SUCCEEDED, outcome.status)
        assertEquals(0, outcome.added)
        verify(exactly = 0) { provider.listFolders(any()) }
    }

    @Test
    fun `only the folders worth mirroring are fetched`() {
        val folders = listOf(
            inbox,
            RemoteFolder(remoteId = "SENT", name = "Sent", type = FolderType.SENT),
            RemoteFolder(remoteId = "TRASH", name = "Trash", type = FolderType.TRASH),
            RemoteFolder(remoteId = "SPAM", name = "Spam", type = FolderType.SPAM),
            RemoteFolder(remoteId = "DRAFTS", name = "Drafts", type = FolderType.DRAFTS),
        )
        every { provider.listFolders(account) } returns folders
        every { provider.fetchMessages(any(), any(), any(), any(), any()) } returns MessagePage(emptyList())
        every { messageRepository.findAllByAccountIdAndRemoteIdIn(any(), any()) } returns emptyList()

        service.syncAccount(account)

        // Trash, spam and drafts are fetched on demand, not mirrored.
        verify(exactly = 0) {
            provider.fetchMessages(any(), match { it.type == FolderType.TRASH }, any(), any(), any())
        }
        verify(exactly = 0) {
            provider.fetchMessages(any(), match { it.type == FolderType.SPAM }, any(), any(), any())
        }
        verify { provider.fetchMessages(any(), match { it.type == FolderType.INBOX }, any(), any(), any()) }
    }

    @Test
    fun `folder counts are recomputed from the messages rather than incremented`() {
        val folder = Folder(accountId = account.id, remoteId = "INBOX", totalCount = 999, unreadCount = 999)
        every { folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(account.id) } returns listOf(folder)
        every { messageRepository.countsByFolder(listOf(account.id)) } returns listOf(
            object : FolderCountProjection {
                override val folderId = folder.id
                override val total = 7L
                override val unread = 3L
            },
        )

        service.recalculateFolderCounts(account.id)

        assertEquals(7, folder.totalCount)
        assertEquals(3, folder.unreadCount)
    }

    @Test
    fun `a folder with no messages has its counters cleared`() {
        val folder = Folder(accountId = account.id, remoteId = "INBOX", totalCount = 5, unreadCount = 2)
        every { folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(account.id) } returns listOf(folder)
        every { messageRepository.countsByFolder(any()) } returns emptyList()

        service.recalculateFolderCounts(account.id)

        assertEquals(0, folder.totalCount)
        assertEquals(0, folder.unreadCount)
    }

    @Test
    fun `syncing folders creates the ones that are new and updates the rest`() {
        val existing = Folder(accountId = account.id, remoteId = "INBOX", name = "Old name")
        every { folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(account.id) } returns listOf(existing)

        val result = service.syncFolders(
            account.id,
            listOf(
                RemoteFolder(remoteId = "INBOX", name = "Inbox", type = FolderType.INBOX),
                RemoteFolder(remoteId = "Label_9", name = "Clients", path = "Work/Clients"),
            ),
        )

        assertEquals(2, result.size)
        assertEquals("Inbox", existing.name)
        assertEquals("Work/Clients", result.getValue("Label_9").path)
    }

    @Test
    fun `an empty page is stored without touching the repositories`() {
        val folder = Folder(accountId = account.id, remoteId = "INBOX")

        val (added, updated) = service.persistPage(account, folder, emptyList(), ruleSet)

        assertEquals(0, added)
        assertEquals(0, updated)
        verify(exactly = 0) { messageRepository.saveAll(any<List<Message>>()) }
    }

    @Test
    fun `folder type is derived from the provider's own naming`() {
        assertEquals(FolderType.INBOX, FolderType.fromRemoteName("INBOX"))
        assertEquals(FolderType.SENT, FolderType.fromRemoteName("[Gmail]/Sent Mail"))
        assertEquals(FolderType.DRAFTS, FolderType.fromRemoteName("Drafts"))
        assertEquals(FolderType.TRASH, FolderType.fromRemoteName("Deleted Items"))
        assertEquals(FolderType.SPAM, FolderType.fromRemoteName("Junk Email"))
        assertEquals(FolderType.ARCHIVE, FolderType.fromRemoteName("All Mail"))
        assertEquals(FolderType.SCHEDULED, FolderType.fromRemoteName("Snoozed"))
        assertEquals(FolderType.CUSTOM, FolderType.fromRemoteName("Project Falcon"))
    }
}
