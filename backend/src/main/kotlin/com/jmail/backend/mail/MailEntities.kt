package com.jmail.backend.mail

import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.EmailAddressListConverter
import com.jmail.backend.common.StringListConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class FolderType {
    INBOX,
    SENT,
    DRAFTS,
    ARCHIVE,
    SPAM,
    TRASH,
    SCHEDULED,
    CUSTOM,
    ;

    companion object {
        /**
         * Maps a provider's folder name onto a well-known type. Providers disagree on
         * naming ("Deleted Items" vs "Trash", "[Gmail]/Sent Mail" vs "Sent"), and the UI
         * needs one vocabulary to render the right icon and empty state.
         */
        fun fromRemoteName(name: String): FolderType {
            val normalised = name.substringAfterLast('/').trim().lowercase()
            return when {
                normalised == "inbox" -> INBOX
                normalised.contains("sent") -> SENT
                normalised.contains("draft") -> DRAFTS
                normalised.contains("archive") || normalised == "all mail" -> ARCHIVE
                normalised.contains("junk") || normalised.contains("spam") -> SPAM
                normalised.contains("trash") || normalised.contains("deleted") -> TRASH
                normalised.contains("scheduled") || normalised.contains("snoozed") -> SCHEDULED
                else -> CUSTOM
            }
        }
    }
}

@Entity
@Table(name = "folders")
class Folder(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false)
    var accountId: UUID = UUID.randomUUID(),

    /** The provider's identifier, used to round-trip moves and flag changes. */
    @Column(name = "remote_id", nullable = false, length = 500)
    var remoteId: String = "",

    @Column(name = "name", nullable = false, length = 255)
    var name: String = "",

    @Column(name = "path", nullable = false, length = 1000)
    var path: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    var type: FolderType = FolderType.CUSTOM,

    @Column(name = "parent_id")
    var parentId: UUID? = null,

    @Column(name = "unread_count", nullable = false)
    var unreadCount: Int = 0,

    @Column(name = "total_count", nullable = false)
    var totalCount: Int = 0,

    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "messages")
class Message(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false)
    var accountId: UUID = UUID.randomUUID(),

    @Column(name = "folder_id", nullable = false)
    var folderId: UUID = UUID.randomUUID(),

    @Column(name = "category_id")
    var categoryId: UUID? = null,

    @Column(name = "remote_id", nullable = false, length = 500)
    var remoteId: String = "",

    /** Groups a conversation. Falls back to the message id when a provider has no threads. */
    @Column(name = "thread_id", nullable = false, length = 500)
    var threadId: String = "",

    @Column(name = "message_id_header", length = 998)
    var messageIdHeader: String? = null,

    @Column(name = "in_reply_to", length = 998)
    var inReplyTo: String? = null,

    @Column(name = "list_id", length = 500)
    var listId: String? = null,

    @Column(name = "subject", nullable = false, length = 2000)
    var subject: String = "",

    /** Short plain-text preview shown in the list; never contains HTML. */
    @Column(name = "snippet", nullable = false, length = 500)
    var snippet: String = "",

    @Column(name = "body_text", columnDefinition = "text")
    var bodyText: String? = null,

    /** Sanitised HTML. Raw provider HTML is never stored; see HtmlSanitizer. */
    @Column(name = "body_html", columnDefinition = "text")
    var bodyHtml: String? = null,

    @Column(name = "from_address", nullable = false, length = 320)
    var fromAddress: String = "",

    @Column(name = "from_name", nullable = false, length = 300)
    var fromName: String = "",

    @Convert(converter = EmailAddressListConverter::class)
    @Column(name = "to_recipients", nullable = false, columnDefinition = "text")
    var toRecipients: List<EmailAddress> = emptyList(),

    @Convert(converter = EmailAddressListConverter::class)
    @Column(name = "cc_recipients", nullable = false, columnDefinition = "text")
    var ccRecipients: List<EmailAddress> = emptyList(),

    @Convert(converter = EmailAddressListConverter::class)
    @Column(name = "bcc_recipients", nullable = false, columnDefinition = "text")
    var bccRecipients: List<EmailAddress> = emptyList(),

    @Column(name = "reply_to", length = 320)
    var replyTo: String? = null,

    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant = Instant.now(),

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant = Instant.now(),

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,

    @Column(name = "is_starred", nullable = false)
    var isStarred: Boolean = false,

    @Column(name = "is_important", nullable = false)
    var isImportant: Boolean = false,

    @Column(name = "is_draft", nullable = false)
    var isDraft: Boolean = false,

    @Column(name = "is_archived", nullable = false)
    var isArchived: Boolean = false,

    @Column(name = "is_trashed", nullable = false)
    var isTrashed: Boolean = false,

    @Column(name = "is_spam", nullable = false)
    var isSpam: Boolean = false,

    @Column(name = "has_attachments", nullable = false)
    var hasAttachments: Boolean = false,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0,

    @Convert(converter = StringListConverter::class)
    @Column(name = "labels", nullable = false, columnDefinition = "text")
    var labels: List<String> = emptyList(),

    /** 0–1 confidence from the classifier; 1 when a person filed it themselves. */
    @Column(name = "category_confidence", nullable = false)
    var categoryConfidence: Float = 0f,

    /** True once a user moves the message by hand, which stops the classifier overriding it. */
    @Column(name = "category_pinned", nullable = false)
    var categoryPinned: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "attachments")
class Attachment(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "message_id", nullable = false)
    var messageId: UUID = UUID.randomUUID(),

    @Column(name = "remote_id", length = 500)
    var remoteId: String? = null,

    @Column(name = "filename", nullable = false, length = 500)
    var filename: String = "",

    @Column(name = "mime_type", nullable = false, length = 255)
    var mimeType: String = "application/octet-stream",

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0,

    /** Set for images referenced by `cid:` from the HTML body. */
    @Column(name = "content_id", length = 255)
    var contentId: String? = null,

    @Column(name = "is_inline", nullable = false)
    var isInline: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

enum class SyncStatus { RUNNING, SUCCEEDED, FAILED }

@Entity
@Table(name = "sync_runs")
class SyncRun(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false)
    var accountId: UUID = UUID.randomUUID(),

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: SyncStatus = SyncStatus.RUNNING,

    @Column(name = "messages_added", nullable = false)
    var messagesAdded: Int = 0,

    @Column(name = "messages_updated", nullable = false)
    var messagesUpdated: Int = 0,

    @Column(name = "error_message", length = 2000)
    var errorMessage: String? = null,
)
