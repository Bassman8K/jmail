package com.jmail.backend.mail

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.category.CategorizationEngine
import com.jmail.backend.common.BadRequestException
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.NotFoundException
import com.jmail.backend.common.PageResponse
import com.jmail.backend.mail.dto.AssignCategoryRequest
import com.jmail.backend.mail.dto.BulkActionResponse
import com.jmail.backend.mail.dto.CategoryCountResponse
import com.jmail.backend.mail.dto.ComposeRequest
import com.jmail.backend.mail.dto.FolderCountResponse
import com.jmail.backend.mail.dto.FolderResponse
import com.jmail.backend.mail.dto.MailboxCountsResponse
import com.jmail.backend.mail.dto.MessageActionRequest
import com.jmail.backend.mail.dto.MessageDetail
import com.jmail.backend.mail.dto.MessageSummary
import com.jmail.backend.mail.dto.SyncResponse
import com.jmail.backend.mail.dto.ThreadResponse
import com.jmail.backend.mail.provider.FlagUpdate
import com.jmail.backend.mail.provider.MailProviderRegistry
import com.jmail.backend.mail.provider.OutgoingMessage
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Everything the client does with messages.
 *
 * Two invariants hold throughout:
 *
 *  * **Ownership is enforced in the query, not after it.** Every read is scoped to the
 *    caller's own account ids, so a guessed message id returns "not found" rather than
 *    someone else's mail.
 *
 *  * **The local store is the source of truth for the response.** Flag changes are written
 *    locally and pushed to the provider on a best-effort basis: the user's action must feel
 *    instant and must not be lost because Gmail was slow.
 */
@Service
class MessageService(
    private val messageRepository: MessageRepository,
    private val folderRepository: FolderRepository,
    private val attachmentRepository: AttachmentRepository,
    private val mailAccountRepository: MailAccountRepository,
    private val registry: MailProviderRegistry,
    private val htmlSanitizer: HtmlSanitizer,
    private val mailSyncService: MailSyncService,
    private val categorizationEngine: CategorizationEngine,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(user: AuthenticatedUser, filter: MessageFilter, pageable: Pageable): PageResponse<MessageSummary> {
        val scoped = filter.copy(accountIds = accountIdsOf(user))
        val folderIds = if (scoped.folderType != null) {
            folderRepository.findAllByAccountIdIn(scoped.accountIds)
                .filter { it.type == scoped.folderType }
                .map(Folder::id)
        } else {
            emptyList()
        }

        val page = messageRepository.findAll(MessageSpecifications.matching(scoped, folderIds), pageable)
        return PageResponse.of(page, MessageSummary::from)
    }

    @Transactional(readOnly = true)
    fun detail(user: AuthenticatedUser, messageId: UUID, allowRemoteImages: Boolean = false): MessageDetail {
        val message = findOwned(user, messageId)
        val attachments = attachmentRepository.findAllByMessageId(message.id)

        val body = if (allowRemoteImages) {
            // Re-sanitise from the stored HTML rather than trusting a client flag to unblock:
            // the safelist still applies, only the image rewriting is skipped.
            htmlSanitizer.sanitize(message.bodyHtml, allowRemoteImages = true)
        } else {
            message.bodyHtml
        }

        return MessageDetail.from(
            message = message.also { it.bodyHtml = body },
            attachments = attachments,
            hasBlockedImages = !allowRemoteImages && htmlSanitizer.containsRemoteImages(message.bodyHtml),
        )
    }

    @Transactional(readOnly = true)
    fun thread(user: AuthenticatedUser, threadId: String): ThreadResponse {
        val accountIds = accountIdsOf(user)
        val messages = messageRepository.findAllByAccountIdInAndThreadIdOrderByReceivedAtAsc(accountIds, threadId)
        if (messages.isEmpty()) throw NotFoundException("Thread", threadId)

        val attachmentsByMessage = attachmentRepository
            .findAllByMessageIdIn(messages.map(Message::id))
            .groupBy(Attachment::messageId)

        return ThreadResponse(
            threadId = threadId,
            subject = messages.first().subject,
            messageCount = messages.size,
            unreadCount = messages.count { !it.isRead },
            participants = messages
                .map { EmailAddress(it.fromAddress, it.fromName.takeIf(String::isNotBlank)) }
                .distinctBy(EmailAddress::address),
            messages = messages.map { message ->
                MessageDetail.from(
                    message = message,
                    attachments = attachmentsByMessage[message.id].orEmpty(),
                    hasBlockedImages = htmlSanitizer.containsRemoteImages(message.bodyHtml),
                )
            },
        )
    }

    @Transactional(readOnly = true)
    fun search(user: AuthenticatedUser, query: String, pageable: Pageable): PageResponse<MessageSummary> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_SEARCH_LENGTH) {
            throw BadRequestException(
                "search_query_too_short",
                "Enter at least $MIN_SEARCH_LENGTH characters to search",
            )
        }

        val page = messageRepository.search(accountIdsOf(user), trimmed, pageable)
        return PageResponse.of(page, MessageSummary::from)
    }

    @Transactional
    fun applyAction(user: AuthenticatedUser, request: MessageActionRequest): BulkActionResponse {
        val accountIds = accountIdsOf(user)
        val messages = messageRepository.findAllByIdInAndAccountIdIn(request.messageIds, accountIds)
        if (messages.isEmpty()) throw NotFoundException("Message", request.messageIds.firstOrNull() ?: "")

        val now = Instant.now()
        messages.forEach { message ->
            request.isRead?.let { message.isRead = it }
            request.isStarred?.let { message.isStarred = it }
            request.isImportant?.let { message.isImportant = it }
            request.isArchived?.let { message.isArchived = it }
            request.isTrashed?.let { message.isTrashed = it }
            request.isSpam?.let { message.isSpam = it }
            message.updatedAt = now
        }
        messageRepository.saveAll(messages)

        val failed = pushFlagsToProviders(messages, request)
        mailSyncService.recalculateFolderCounts(messages.first().accountId)

        return BulkActionResponse(affected = messages.size, failedRemoteSync = failed)
    }

    /**
     * Pushes flag changes upstream. Failures are reported, never thrown: the local change is
     * already committed and a provider outage must not roll back what the user just did. The
     * next sync reconciles anything that did not land.
     */
    private fun pushFlagsToProviders(messages: List<Message>, request: MessageActionRequest): List<UUID> {
        val flags = FlagUpdate(
            isRead = request.isRead,
            isStarred = request.isStarred,
            isArchived = request.isArchived,
            isTrashed = request.isTrashed,
            isSpam = request.isSpam,
        )
        if (flags == FlagUpdate()) return emptyList()

        val accounts = mailAccountRepository.findAllById(messages.map(Message::accountId).distinct())
            .associateBy(MailAccount::id)

        return messages.mapNotNull { message ->
            val account = accounts[message.accountId] ?: return@mapNotNull message.id
            runCatching {
                registry.forAccount(account).applyFlags(account, message.remoteId, flags)
            }.onFailure { failure ->
                log.warn("Could not push flags for message {}: {}", message.id, failure.message)
            }.exceptionOrNull()?.let { message.id }
        }
    }

    @Transactional
    fun assignCategory(user: AuthenticatedUser, request: AssignCategoryRequest): BulkActionResponse {
        val accountIds = accountIdsOf(user)
        val affected = messageRepository.assignCategory(
            ids = request.messageIds,
            accountIds = accountIds,
            categoryId = request.categoryId,
            now = Instant.now(),
        )
        if (affected == 0) throw NotFoundException("Message", request.messageIds.firstOrNull() ?: "")

        return BulkActionResponse(affected = affected)
    }

    @Transactional
    fun compose(user: AuthenticatedUser, request: ComposeRequest): MessageDetail {
        val account = resolveSendingAccount(user, request.accountId)
        val provider = registry.forAccount(account)

        if (!provider.supportsSending && !request.saveAsDraft) {
            throw BadRequestException(
                "sending_not_supported",
                "${account.provider.displayName} accounts cannot send mail from JMail",
            )
        }

        val bodyHtml = htmlSanitizer.sanitize(request.bodyHtml, allowRemoteImages = true)
        val outgoing = OutgoingMessage(
            to = request.to.map { it.toEmailAddress() },
            cc = request.cc.map { it.toEmailAddress() },
            bcc = request.bcc.map { it.toEmailAddress() },
            subject = request.subject,
            bodyHtml = bodyHtml,
            bodyText = request.bodyText,
            inReplyToMessageId = request.inReplyToMessageId,
            threadRemoteId = request.threadId,
        )

        val remoteId = if (request.saveAsDraft) {
            "draft-${UUID.randomUUID()}"
        } else {
            provider.sendMessage(account, outgoing)
        }

        val folderType = if (request.saveAsDraft) FolderType.DRAFTS else FolderType.SENT
        val folder = folderRepository.findFirstByAccountIdAndType(account.id, folderType)
            ?: folderRepository.save(
                Folder(
                    accountId = account.id,
                    remoteId = folderType.name.lowercase(),
                    name = folderType.name.lowercase().replaceFirstChar(Char::uppercase),
                    path = folderType.name.lowercase().replaceFirstChar(Char::uppercase),
                    type = folderType,
                ),
            )

        val now = Instant.now()
        val message = messageRepository.save(
            Message(
                accountId = account.id,
                folderId = folder.id,
                remoteId = remoteId,
                threadId = request.threadId ?: remoteId,
                inReplyTo = request.inReplyToMessageId,
                subject = request.subject,
                snippet = htmlSanitizer.snippet(request.bodyText),
                bodyText = request.bodyText,
                bodyHtml = bodyHtml,
                fromAddress = account.email,
                fromName = account.displayName,
                toRecipients = outgoing.to,
                ccRecipients = outgoing.cc,
                bccRecipients = outgoing.bcc,
                sentAt = now,
                receivedAt = now,
                isRead = true,
                isDraft = request.saveAsDraft,
                sizeBytes = request.bodyText.length.toLong(),
                createdAt = now,
                updatedAt = now,
            ),
        )

        log.info(
            "{} message {} from account {}",
            if (request.saveAsDraft) "Saved draft" else "Sent",
            message.id,
            account.id,
        )
        return MessageDetail.from(message, emptyList(), hasBlockedImages = false)
    }

    @Transactional(readOnly = true)
    fun counts(user: AuthenticatedUser): MailboxCountsResponse {
        val accountIds = accountIdsOf(user)
        if (accountIds.isEmpty()) return MailboxCountsResponse(emptyList(), emptyList(), 0)

        val categories = messageRepository.countsByCategory(accountIds)
        val folders = messageRepository.countsByFolder(accountIds)

        return MailboxCountsResponse(
            categories = categories.map { CategoryCountResponse(it.categoryId, it.total, it.unread) },
            folders = folders.map { FolderCountResponse(it.folderId, it.total, it.unread) },
            totalUnread = categories.sumOf { it.unread },
        )
    }

    @Transactional(readOnly = true)
    fun folders(user: AuthenticatedUser): List<FolderResponse> =
        folderRepository.findAllByAccountIdIn(accountIdsOf(user)).map(FolderResponse::from)

    @Transactional(readOnly = true)
    fun downloadAttachment(user: AuthenticatedUser, messageId: UUID, attachmentId: UUID): Pair<Attachment, ByteArray> {
        val message = findOwned(user, messageId)
        val attachment = attachmentRepository.findByIdAndMessageId(attachmentId, message.id)
            ?: throw NotFoundException("Attachment", attachmentId)

        val account = mailAccountRepository.findById(message.accountId)
            .orElseThrow { NotFoundException("Account", message.accountId) }

        val bytes = attachment.remoteId
            ?.let { registry.forAccount(account).downloadAttachment(account, message.remoteId, it) }
            ?: throw NotFoundException("Attachment content", attachmentId)

        return attachment to bytes
    }

    /** Runs a sync now, for one account or all of them, and reports what happened. */
    fun syncNow(user: AuthenticatedUser, accountId: UUID?): List<SyncResponse> {
        val accounts = if (accountId != null) {
            listOfNotNull(mailAccountRepository.findByIdAndUserId(accountId, user.userId))
                .ifEmpty { throw NotFoundException("Account", accountId) }
        } else {
            mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId)
        }

        return accounts.map { account ->
            val outcome = mailSyncService.syncAccount(account)
            SyncResponse(
                accountId = outcome.accountId,
                status = outcome.status.name,
                messagesAdded = outcome.added,
                messagesUpdated = outcome.updated,
                error = outcome.error,
            )
        }
    }

    /** Re-runs classification over stored messages, e.g. after a rule change. */
    @Transactional
    fun reclassify(user: AuthenticatedUser, limit: Int = 1000): Int {
        categorizationEngine.invalidate(user.userId)
        val ruleSet = categorizationEngine.ruleSetFor(user.userId)
        val accountIds = accountIdsOf(user)
        if (accountIds.isEmpty()) return 0

        val page = messageRepository.findAll(
            MessageSpecifications.matching(MessageFilter(accountIds = accountIds)),
            Pageable.ofSize(limit),
        )

        // Messages a user filed by hand are left alone; overruling an explicit choice is the
        // fastest way to lose someone's trust in automatic categorisation.
        val updated = page.content.filterNot(Message::categoryPinned).onEach { message ->
            val classification = categorizationEngine.classify(
                ruleSet,
                com.jmail.backend.category.ClassificationInput(
                    fromAddress = message.fromAddress,
                    fromName = message.fromName,
                    subject = message.subject,
                    bodyText = message.bodyText,
                    listId = message.listId,
                    recipients = message.toRecipients.map(EmailAddress::address),
                ),
            )
            message.categoryId = classification.categoryId
            message.categoryConfidence = classification.confidence
        }

        messageRepository.saveAll(updated)
        return updated.size
    }

    private fun findOwned(user: AuthenticatedUser, messageId: UUID): Message =
        messageRepository.findByIdAndAccountIdIn(messageId, accountIdsOf(user))
            ?: throw NotFoundException("Message", messageId)

    private fun resolveSendingAccount(user: AuthenticatedUser, accountId: UUID?): MailAccount {
        if (accountId != null) {
            return mailAccountRepository.findByIdAndUserId(accountId, user.userId)
                ?: throw NotFoundException("Account", accountId)
        }
        return mailAccountRepository.findFirstByUserIdAndIsPrimaryTrue(user.userId)
            ?: mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId).firstOrNull()
            ?: throw BadRequestException("no_account", "Connect a mailbox before sending mail")
    }

    private fun accountIdsOf(user: AuthenticatedUser): List<UUID> =
        mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId).map(MailAccount::id)

    private companion object {
        const val MIN_SEARCH_LENGTH = 2
    }
}
