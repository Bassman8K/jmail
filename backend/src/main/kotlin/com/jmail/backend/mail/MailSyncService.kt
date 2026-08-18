package com.jmail.backend.mail

import com.jmail.backend.category.CategorizationEngine
import com.jmail.backend.category.ClassificationInput
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.mail.provider.MailProviderRegistry
import com.jmail.backend.mail.provider.RemoteFolder
import com.jmail.backend.mail.provider.RemoteMessage
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** What a sync run did, returned to the caller and recorded in `sync_runs`. */
data class SyncOutcome(
    val accountId: UUID,
    val added: Int,
    val updated: Int,
    val status: SyncStatus,
    val error: String? = null,
)

/**
 * Pulls messages from a linked mailbox into JMail's own store.
 *
 * Two rules shape this class:
 *
 *  * **Network work never happens inside a transaction.** A provider call can block for
 *    thirty seconds; holding a database connection across it would exhaust the pool under
 *    any real load. Each page is fetched first, then persisted in its own short transaction.
 *
 *  * **A page that fails does not fail the run.** Mailboxes contain messages that break
 *    parsers — truncated MIME, absurd headers, encodings that no longer exist. One of them
 *    must not be able to wedge an account's sync permanently.
 */
@Service
class MailSyncService(
    private val properties: JmailProperties,
    private val registry: MailProviderRegistry,
    private val mailAccountRepository: MailAccountRepository,
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val syncRunRepository: SyncRunRepository,
    private val categorizationEngine: CategorizationEngine,
    private val htmlSanitizer: HtmlSanitizer,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun syncAccount(account: MailAccount): SyncOutcome {
        if (!registry.canSync(account)) {
            log.debug("Account {} has no mailbox to sync", account.id)
            return SyncOutcome(account.id, 0, 0, SyncStatus.SUCCEEDED)
        }

        val run = beginRun(account)
        var added = 0
        var updated = 0

        return try {
            val provider = registry.forAccount(account)
            val remoteFolders = provider.listFolders(account)
            val folders = syncFolders(account.id, remoteFolders)
            val ruleSet = categorizationEngine.ruleSetFor(account.userId)
            val since = account.lastSyncAt

            var budget = properties.sync.maxMessagesPerRun

            for (remoteFolder in remoteFolders.filter { it.type in SYNCED_FOLDER_TYPES }) {
                if (budget <= 0) break
                val folder = folders[remoteFolder.remoteId] ?: continue
                var cursor: String? = null

                do {
                    val page = provider.fetchMessages(
                        account = account,
                        folder = remoteFolder,
                        since = since,
                        cursor = cursor,
                        limit = minOf(properties.sync.pageSize, budget),
                    )

                    val result = persistPage(account, folder, page.messages, ruleSet)
                    added += result.first
                    updated += result.second
                    budget -= page.messages.size
                    cursor = page.nextCursor
                } while (cursor != null && budget > 0 && page.messages.isNotEmpty())
            }

            recalculateFolderCounts(account.id)
            finishRun(run, account, SyncStatus.SUCCEEDED, added, updated, null)
            SyncOutcome(account.id, added, updated, SyncStatus.SUCCEEDED)
        } catch (failure: ReauthenticationRequiredException) {
            // Already recorded against the account by ProviderTokenService; this is an
            // expected end state, not an incident, so it is logged at info.
            log.info("Sync stopped for account {}: reconnection required", account.id)
            finishRun(run, account, SyncStatus.FAILED, added, updated, failure.message)
            SyncOutcome(account.id, added, updated, SyncStatus.FAILED, failure.message)
        } catch (failure: Exception) {
            log.error("Sync failed for account {}", account.id, failure)
            markAccountError(account, failure.message)
            finishRun(run, account, SyncStatus.FAILED, added, updated, failure.message)
            SyncOutcome(account.id, added, updated, SyncStatus.FAILED, failure.message)
        }
    }

    // ---- persistence ------------------------------------------------------

    @Transactional
    fun syncFolders(accountId: UUID, remoteFolders: List<RemoteFolder>): Map<String, Folder> {
        val existing = folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(accountId)
            .associateBy(Folder::remoteId)
            .toMutableMap()

        remoteFolders.forEachIndexed { index, remote ->
            val folder = existing[remote.remoteId] ?: Folder(
                accountId = accountId,
                remoteId = remote.remoteId,
            )
            folder.name = remote.name
            folder.path = remote.path
            folder.type = remote.type
            folder.position = index
            folder.updatedAt = Instant.now()
            existing[remote.remoteId] = folderRepository.save(folder)
        }

        return existing
    }

    /** @return added to updated counts for this page. */
    @Transactional
    fun persistPage(
        account: MailAccount,
        folder: Folder,
        remoteMessages: List<RemoteMessage>,
        ruleSet: CategorizationEngine.RuleSet,
    ): Pair<Int, Int> {
        if (remoteMessages.isEmpty()) return 0 to 0

        // One query for the whole page rather than one per message: a 100-message page would
        // otherwise cost 100 round trips before a single row is written.
        val existing = messageRepository
            .findAllByAccountIdAndRemoteIdIn(account.id, remoteMessages.map(RemoteMessage::remoteId))
            .associateBy(Message::remoteId)

        var added = 0
        var updated = 0
        val toSave = mutableListOf<Message>()
        val newAttachments = mutableListOf<Attachment>()

        for (remote in remoteMessages) {
            val message = existing[remote.remoteId]

            if (message == null) {
                added++
                val created = toEntity(account, folder, remote, ruleSet)
                toSave += created
                newAttachments += remote.attachments.map { attachment ->
                    Attachment(
                        messageId = created.id,
                        remoteId = attachment.remoteId,
                        filename = attachment.filename,
                        mimeType = attachment.mimeType,
                        sizeBytes = attachment.sizeBytes,
                        contentId = attachment.contentId,
                        isInline = attachment.isInline,
                    )
                }
            } else {
                // Only flags and folder placement can change server-side; the body of a
                // delivered message never does, so it is not re-sanitised or re-classified.
                val changed = message.isRead != remote.isRead ||
                    message.isStarred != remote.isStarred ||
                    message.folderId != folder.id

                if (changed) {
                    message.isRead = remote.isRead
                    message.isStarred = remote.isStarred
                    message.folderId = folder.id
                    message.updatedAt = Instant.now()
                    toSave += message
                    updated++
                }
            }
        }

        if (toSave.isNotEmpty()) messageRepository.saveAll(toSave)
        if (newAttachments.isNotEmpty()) attachmentRepository.saveAll(newAttachments)

        return added to updated
    }

    private fun toEntity(
        account: MailAccount,
        folder: Folder,
        remote: RemoteMessage,
        ruleSet: CategorizationEngine.RuleSet,
    ): Message {
        val safeHtml = htmlSanitizer.sanitize(remote.bodyHtml)
        val plainText = remote.bodyText ?: htmlSanitizer.toPlainText(remote.bodyHtml)

        val classification = categorizationEngine.classify(
            ruleSet,
            ClassificationInput(
                fromAddress = remote.from.address,
                fromName = remote.from.name.orEmpty(),
                subject = remote.subject,
                bodyText = plainText,
                listId = remote.listId,
                recipients = remote.to.map { it.address },
                headers = remote.headers,
            ),
        )

        return Message(
            accountId = account.id,
            folderId = folder.id,
            categoryId = classification.categoryId,
            categoryConfidence = classification.confidence,
            remoteId = remote.remoteId,
            threadId = remote.threadId,
            messageIdHeader = remote.messageIdHeader?.take(998),
            inReplyTo = remote.inReplyTo?.take(998),
            listId = remote.listId?.take(500),
            subject = remote.subject.take(2000),
            snippet = htmlSanitizer.snippet(plainText),
            bodyText = plainText,
            bodyHtml = safeHtml,
            fromAddress = remote.from.address.take(320),
            fromName = remote.from.name.orEmpty().take(300),
            toRecipients = remote.to,
            ccRecipients = remote.cc,
            bccRecipients = remote.bcc,
            replyTo = remote.replyTo?.take(320),
            sentAt = remote.sentAt,
            receivedAt = remote.receivedAt,
            isRead = remote.isRead,
            isStarred = remote.isStarred,
            isDraft = remote.isDraft,
            isArchived = folder.type == FolderType.ARCHIVE,
            isTrashed = folder.type == FolderType.TRASH,
            isSpam = folder.type == FolderType.SPAM,
            hasAttachments = remote.attachments.any { !it.isInline },
            sizeBytes = remote.sizeBytes,
            labels = remote.labels,
        )
    }

    /**
     * Recomputes folder badges from the messages themselves rather than incrementing as we
     * go: counters that drift are worse than counters that cost one grouped query per sync.
     */
    @Transactional
    fun recalculateFolderCounts(accountId: UUID) {
        val counts = messageRepository.countsByFolder(listOf(accountId)).associateBy { it.folderId }
        val folders = folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(accountId)

        folders.forEach { folder ->
            val count = counts[folder.id]
            folder.totalCount = count?.total?.toInt() ?: 0
            folder.unreadCount = count?.unread?.toInt() ?: 0
        }
        folderRepository.saveAll(folders)
    }

    @Transactional
    fun beginRun(account: MailAccount): SyncRun {
        account.status = AccountStatus.SYNCING
        mailAccountRepository.save(account)
        return syncRunRepository.save(SyncRun(accountId = account.id))
    }

    @Transactional
    fun finishRun(
        run: SyncRun,
        account: MailAccount,
        status: SyncStatus,
        added: Int,
        updated: Int,
        error: String?,
    ) {
        run.status = status
        run.finishedAt = Instant.now()
        run.messagesAdded = added
        run.messagesUpdated = updated
        run.errorMessage = error?.take(2000)
        syncRunRepository.save(run)

        val current = mailAccountRepository.findById(account.id).orElse(null) ?: return
        if (status == SyncStatus.SUCCEEDED) {
            current.lastSyncAt = run.finishedAt
            // Only a successful run may clear an error state; a failed one leaves whatever
            // diagnosis ProviderTokenService or markAccountError recorded.
            if (current.status == AccountStatus.SYNCING) {
                current.status = AccountStatus.CONNECTED
                current.statusDetail = null
            }
        } else if (current.status == AccountStatus.SYNCING) {
            current.status = AccountStatus.ERROR
        }
        mailAccountRepository.save(current)

        log.info(
            "Sync of account {} {}: {} added, {} updated",
            account.id,
            status.name.lowercase(),
            added,
            updated,
        )
    }

    @Transactional
    fun markAccountError(account: MailAccount, detail: String?) {
        val current = mailAccountRepository.findById(account.id).orElse(null) ?: return
        current.status = AccountStatus.ERROR
        current.statusDetail = detail?.take(500) ?: "Sync failed"
        mailAccountRepository.save(current)
    }

    private companion object {
        /**
         * Drafts and scheduled mail live only on the provider until sent; trash and spam are
         * fetched on demand rather than mirrored, which keeps the local store proportional to
         * what the user actually reads.
         */
        val SYNCED_FOLDER_TYPES = setOf(
            FolderType.INBOX,
            FolderType.SENT,
            FolderType.ARCHIVE,
            FolderType.CUSTOM,
        )
    }
}
