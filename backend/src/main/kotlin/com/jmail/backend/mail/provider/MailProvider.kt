package com.jmail.backend.mail.provider

import com.jmail.backend.common.EmailAddress
import com.jmail.backend.mail.FolderType
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import java.time.Instant

/** A folder as the remote system describes it. */
data class RemoteFolder(
    val remoteId: String,
    val name: String,
    val path: String = name,
    val type: FolderType = FolderType.fromRemoteName(name),
    val unreadCount: Int = 0,
    val totalCount: Int = 0,
)

/** A message as fetched from a provider, before JMail maps and classifies it. */
data class RemoteMessage(
    val remoteId: String,
    val threadId: String,
    val folderRemoteId: String,
    val subject: String,
    val from: EmailAddress,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val replyTo: String? = null,
    val bodyHtml: String? = null,
    val bodyText: String? = null,
    val sentAt: Instant,
    val receivedAt: Instant,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isDraft: Boolean = false,
    val sizeBytes: Long = 0,
    val messageIdHeader: String? = null,
    val inReplyTo: String? = null,
    val listId: String? = null,
    val labels: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val attachments: List<RemoteAttachment> = emptyList(),
)

data class RemoteAttachment(
    val remoteId: String?,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentId: String? = null,
    val isInline: Boolean = false,
)

/** A message JMail is about to send on the user's behalf. */
data class OutgoingMessage(
    val to: List<EmailAddress>,
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String,
    val bodyHtml: String? = null,
    val bodyText: String,
    val inReplyToMessageId: String? = null,
    val threadRemoteId: String? = null,
)

/** The flag changes a provider is asked to apply; null means "leave as it is". */
data class FlagUpdate(
    val isRead: Boolean? = null,
    val isStarred: Boolean? = null,
    val isArchived: Boolean? = null,
    val isTrashed: Boolean? = null,
    val isSpam: Boolean? = null,
)

/** One page of a sync, plus the cursor to resume from. */
data class MessagePage(
    val messages: List<RemoteMessage>,
    val nextCursor: String? = null,
)

/**
 * The operations JMail needs from a mail system.
 *
 * Every provider is reduced to this interface so that sync, categorisation, search and the
 * API are written once. Gmail's REST API, Microsoft Graph, an on-premises Exchange server
 * over IMAP and the seeded demo mailbox all differ enormously in their transport, but they
 * agree on this vocabulary.
 *
 * Implementations are expected to throw `ProviderException` for transport failures and
 * `ReauthenticationRequiredException` when credentials have been revoked, so that the sync
 * loop can mark the account rather than crash.
 */
interface MailProvider {

    val provider: AccountProvider

    /** Whether this provider can send mail as well as read it. */
    val supportsSending: Boolean get() = true

    fun listFolders(account: MailAccount): List<RemoteFolder>

    /**
     * @param cursor opaque provider-specific position from the previous page; null starts
     *   from the newest message.
     * @param since incremental floor — messages older than this are not worth fetching again.
     */
    fun fetchMessages(
        account: MailAccount,
        folder: RemoteFolder,
        since: Instant? = null,
        cursor: String? = null,
        limit: Int = 100,
    ): MessagePage

    fun sendMessage(account: MailAccount, message: OutgoingMessage): String

    fun applyFlags(account: MailAccount, remoteMessageId: String, flags: FlagUpdate)

    /** Downloads one attachment's bytes. Null when the provider cannot serve it. */
    fun downloadAttachment(
        account: MailAccount,
        remoteMessageId: String,
        remoteAttachmentId: String,
    ): ByteArray?
}
