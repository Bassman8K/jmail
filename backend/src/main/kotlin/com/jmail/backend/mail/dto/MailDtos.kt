package com.jmail.backend.mail.dto

import com.jmail.backend.common.EmailAddress
import com.jmail.backend.mail.Attachment
import com.jmail.backend.mail.Folder
import com.jmail.backend.mail.FolderType
import com.jmail.backend.mail.Message
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

@Schema(description = "A message as shown in the list; the body is omitted for size")
data class MessageSummary(
    val id: UUID,
    val accountId: UUID,
    val folderId: UUID,
    val threadId: String,
    val subject: String,
    val snippet: String,
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val receivedAt: Instant,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isImportant: Boolean,
    val isDraft: Boolean,
    val hasAttachments: Boolean,
    val categoryId: UUID?,
    @get:Schema(description = "0–1; below 0.35 the UI offers a correction affordance")
    val categoryConfidence: Float,
    val labels: List<String>,
    val sizeBytes: Long,
) {
    companion object {
        fun from(message: Message) = MessageSummary(
            id = message.id,
            accountId = message.accountId,
            folderId = message.folderId,
            threadId = message.threadId,
            subject = message.subject,
            snippet = message.snippet,
            from = EmailAddress(message.fromAddress, message.fromName.takeIf { it.isNotBlank() }),
            to = message.toRecipients,
            receivedAt = message.receivedAt,
            isRead = message.isRead,
            isStarred = message.isStarred,
            isImportant = message.isImportant,
            isDraft = message.isDraft,
            hasAttachments = message.hasAttachments,
            categoryId = message.categoryId,
            categoryConfidence = message.categoryConfidence,
            labels = message.labels,
            sizeBytes = message.sizeBytes,
        )
    }
}

@Schema(description = "A message with its body and attachments")
data class MessageDetail(
    val id: UUID,
    val accountId: UUID,
    val folderId: UUID,
    val threadId: String,
    val subject: String,
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val replyTo: String?,
    @get:Schema(description = "Sanitised HTML: scripts, styles and event handlers are removed")
    val bodyHtml: String?,
    val bodyText: String?,
    val sentAt: Instant,
    val receivedAt: Instant,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isImportant: Boolean,
    val isDraft: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val isSpam: Boolean,
    val categoryId: UUID?,
    val categoryConfidence: Float,
    val labels: List<String>,
    val sizeBytes: Long,
    val attachments: List<AttachmentResponse>,
    @get:Schema(description = "True when remote images were blocked and can be loaded on request")
    val hasBlockedImages: Boolean,
) {
    companion object {
        fun from(
            message: Message,
            attachments: List<Attachment>,
            hasBlockedImages: Boolean,
        ) = MessageDetail(
            id = message.id,
            accountId = message.accountId,
            folderId = message.folderId,
            threadId = message.threadId,
            subject = message.subject,
            from = EmailAddress(message.fromAddress, message.fromName.takeIf { it.isNotBlank() }),
            to = message.toRecipients,
            cc = message.ccRecipients,
            bcc = message.bccRecipients,
            replyTo = message.replyTo,
            bodyHtml = message.bodyHtml,
            bodyText = message.bodyText,
            sentAt = message.sentAt,
            receivedAt = message.receivedAt,
            isRead = message.isRead,
            isStarred = message.isStarred,
            isImportant = message.isImportant,
            isDraft = message.isDraft,
            isArchived = message.isArchived,
            isTrashed = message.isTrashed,
            isSpam = message.isSpam,
            categoryId = message.categoryId,
            categoryConfidence = message.categoryConfidence,
            labels = message.labels,
            sizeBytes = message.sizeBytes,
            attachments = attachments.map(AttachmentResponse::from),
            hasBlockedImages = hasBlockedImages,
        )
    }
}

data class AttachmentResponse(
    val id: UUID,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isInline: Boolean,
) {
    companion object {
        fun from(attachment: Attachment) = AttachmentResponse(
            id = attachment.id,
            filename = attachment.filename,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            isInline = attachment.isInline,
        )
    }
}

@Schema(description = "A conversation, oldest message first")
data class ThreadResponse(
    val threadId: String,
    val subject: String,
    val messageCount: Int,
    val unreadCount: Int,
    val participants: List<EmailAddress>,
    val messages: List<MessageDetail>,
)

data class FolderResponse(
    val id: UUID,
    val accountId: UUID,
    val name: String,
    val path: String,
    val type: FolderType,
    val unreadCount: Int,
    val totalCount: Int,
) {
    companion object {
        fun from(folder: Folder) = FolderResponse(
            id = folder.id,
            accountId = folder.accountId,
            name = folder.name,
            path = folder.path,
            type = folder.type,
            unreadCount = folder.unreadCount,
            totalCount = folder.totalCount,
        )
    }
}

@Schema(description = "Badge counts for the sidebar, in one round trip")
data class MailboxCountsResponse(
    val categories: List<CategoryCountResponse>,
    val folders: List<FolderCountResponse>,
    val totalUnread: Long,
)

data class CategoryCountResponse(val categoryId: UUID?, val total: Long, val unread: Long)

data class FolderCountResponse(val folderId: UUID, val total: Long, val unread: Long)

@Schema(description = "A message to send, or save as a draft")
data class ComposeRequest(
    @get:Schema(description = "Which linked mailbox to send from; defaults to the primary one")
    val accountId: UUID? = null,

    @field:NotEmpty(message = "Add at least one recipient")
    @field:Valid
    val to: List<@Valid RecipientRequest>,

    @field:Valid
    val cc: List<@Valid RecipientRequest> = emptyList(),

    @field:Valid
    val bcc: List<@Valid RecipientRequest> = emptyList(),

    @field:Size(max = 2000, message = "That subject is too long")
    val subject: String = "",

    @field:NotBlank(message = "The message body cannot be empty")
    val bodyText: String,

    val bodyHtml: String? = null,

    @get:Schema(description = "Message-ID of the message being replied to, for correct threading")
    val inReplyToMessageId: String? = null,

    val threadId: String? = null,

    @get:Schema(description = "Save to Drafts instead of sending")
    val saveAsDraft: Boolean = false,
)

data class RecipientRequest(
    @field:NotBlank
    @field:Email(message = "That does not look like an email address")
    val address: String,
    @field:Size(max = 300)
    val name: String? = null,
) {
    fun toEmailAddress() = EmailAddress(address.lowercase().trim(), name?.trim()?.takeIf { it.isNotEmpty() })
}

@Schema(description = "A flag change applied to one or more messages")
data class MessageActionRequest(
    @field:NotEmpty(message = "Select at least one message")
    @field:Size(max = 500, message = "Too many messages in one request")
    val messageIds: List<UUID>,
    val isRead: Boolean? = null,
    val isStarred: Boolean? = null,
    val isImportant: Boolean? = null,
    val isArchived: Boolean? = null,
    val isTrashed: Boolean? = null,
    val isSpam: Boolean? = null,
)

data class AssignCategoryRequest(
    @field:NotEmpty(message = "Select at least one message")
    @field:Size(max = 500)
    val messageIds: List<UUID>,
    @get:Schema(description = "Null clears the category and lets the classifier decide again")
    val categoryId: UUID? = null,
)

@Schema(description = "The result of a bulk action")
data class BulkActionResponse(
    val affected: Int,
    @get:Schema(description = "Messages whose change could not be pushed to the provider")
    val failedRemoteSync: List<UUID> = emptyList(),
)

@Schema(description = "Outcome of a manually triggered sync")
data class SyncResponse(
    val accountId: UUID,
    val status: String,
    val messagesAdded: Int,
    val messagesUpdated: Int,
    val error: String? = null,
)
