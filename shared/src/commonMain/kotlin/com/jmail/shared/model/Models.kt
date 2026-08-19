package com.jmail.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The client-side mirror of the JMail API contract.
 *
 * These are deliberately hand-written rather than generated: the shapes are small, and
 * having them here means the compiler catches a backend change the moment the client is
 * built against it. `ApiContractTest` on the backend guards the field names in the other
 * direction.
 */

@Serializable
data class EmailAddress(
    val address: String,
    val name: String? = null,
) {
    /** What the UI shows: the person's name where known, otherwise the bare address. */
    val displayLabel: String get() = name?.takeIf { it.isNotBlank() } ?: address

    /** "AL" for Ada Lovelace, "A" for ada@…, used by the avatar placeholder. */
    val initials: String
        get() {
            val source = name?.takeIf { it.isNotBlank() } ?: address.substringBefore('@')
            return source
                .split(' ', '.', '_', '-')
                .filter { it.isNotBlank() }
                .take(2)
                .map { it.first().uppercaseChar() }
                .joinToString("")
                .ifEmpty { "?" }
        }
}

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

@Serializable
enum class AccountProvider {
    GOOGLE, MICROSOFT, APPLE, EXCHANGE, IMAP, DEMO;

    val label: String
        get() = when (this) {
            GOOGLE -> "Google"
            MICROSOFT -> "Microsoft"
            APPLE -> "Apple"
            EXCHANGE -> "Microsoft Exchange"
            IMAP -> "IMAP"
            DEMO -> "Demo"
        }
}

@Serializable
enum class AccountStatus { CONNECTED, REAUTH_REQUIRED, SYNCING, ERROR, DISABLED }

@Serializable
enum class SignInKind { OAUTH, DEMO }

@Serializable
enum class UiTheme { SYSTEM, LIGHT, DARK }

@Serializable
enum class UiDensity { COMPACT, COMFORTABLE, SPACIOUS }

@Serializable
data class ProviderSummary(
    val id: AccountProvider,
    val displayName: String,
    val kind: SignInKind,
    val icon: String,
)

@Serializable
data class MailAccount(
    val id: String,
    val provider: AccountProvider,
    val providerName: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val status: AccountStatus,
    val statusDetail: String? = null,
    val isPrimary: Boolean = false,
    val color: String? = null,
    val lastSyncAt: String? = null,
) {
    val needsAttention: Boolean get() = status == AccountStatus.REAUTH_REQUIRED || status == AccountStatus.ERROR
}

@Serializable
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val locale: String = "en",
    val timezone: String = "UTC",
    val theme: UiTheme = UiTheme.SYSTEM,
    val density: UiDensity = UiDensity.COMFORTABLE,
    val accounts: List<MailAccount> = emptyList(),
)

@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: User,
)

@Serializable
data class StartAuthorization(
    val authorizationUrl: String,
    val state: String,
    val expiresInSeconds: Long,
)

// ---------------------------------------------------------------------------
// Mail
// ---------------------------------------------------------------------------

@Serializable
enum class FolderType { INBOX, SENT, DRAFTS, ARCHIVE, SPAM, TRASH, SCHEDULED, CUSTOM }

@Serializable
data class MailFolder(
    val id: String,
    val accountId: String,
    val name: String,
    val path: String,
    val type: FolderType,
    val unreadCount: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
data class Category(
    val id: String,
    val key: String,
    val name: String,
    val description: String? = null,
    val color: String = "#4F46E5",
    val icon: String = "inbox",
    val position: Int = 0,
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true,
    val total: Long = 0,
    val unread: Long = 0,
    val ruleCount: Long = 0,
)

@Serializable
data class MessageSummary(
    val id: String,
    val accountId: String,
    val folderId: String,
    val threadId: String,
    val subject: String,
    val snippet: String,
    val from: EmailAddress,
    val to: List<EmailAddress> = emptyList(),
    val receivedAt: String,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isImportant: Boolean = false,
    val isDraft: Boolean = false,
    val hasAttachments: Boolean = false,
    val categoryId: String? = null,
    val categoryConfidence: Float = 0f,
    val labels: List<String> = emptyList(),
    val sizeBytes: Long = 0,
) {
    /** Subjects are frequently empty in real mail; the UI must never render a blank row. */
    val displaySubject: String get() = subject.ifBlank { "(no subject)" }

    /** Below this the reader offers a "wrong category?" correction. */
    val isLowConfidence: Boolean get() = categoryId != null && categoryConfidence < 0.35f
}

@Serializable
data class Attachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isInline: Boolean = false,
)

@Serializable
data class MessageDetail(
    val id: String,
    val accountId: String,
    val folderId: String,
    val threadId: String,
    val subject: String,
    val from: EmailAddress,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val replyTo: String? = null,
    val bodyHtml: String? = null,
    val bodyText: String? = null,
    val sentAt: String,
    val receivedAt: String,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isImportant: Boolean = false,
    val isDraft: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val isSpam: Boolean = false,
    val categoryId: String? = null,
    val categoryConfidence: Float = 0f,
    val labels: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val attachments: List<Attachment> = emptyList(),
    val hasBlockedImages: Boolean = false,
) {
    val displaySubject: String get() = subject.ifBlank { "(no subject)" }
}

@Serializable
data class MailThread(
    val threadId: String,
    val subject: String,
    val messageCount: Int,
    val unreadCount: Int,
    val participants: List<EmailAddress> = emptyList(),
    val messages: List<MessageDetail> = emptyList(),
)

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 50,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class MailboxCounts(
    val categories: List<CategoryCount> = emptyList(),
    val folders: List<FolderCount> = emptyList(),
    val totalUnread: Long = 0,
)

@Serializable
data class CategoryCount(val categoryId: String? = null, val total: Long = 0, val unread: Long = 0)

@Serializable
data class FolderCount(val folderId: String, val total: Long = 0, val unread: Long = 0)

@Serializable
data class BulkActionResult(
    val affected: Int,
    val failedRemoteSync: List<String> = emptyList(),
)

@Serializable
data class SyncResult(
    val accountId: String,
    val status: String,
    val messagesAdded: Int = 0,
    val messagesUpdated: Int = 0,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

@Serializable
data class RecipientInput(val address: String, val name: String? = null)

@Serializable
data class ComposeRequest(
    val accountId: String? = null,
    val to: List<RecipientInput>,
    val cc: List<RecipientInput> = emptyList(),
    val bcc: List<RecipientInput> = emptyList(),
    val subject: String = "",
    val bodyText: String,
    val bodyHtml: String? = null,
    val inReplyToMessageId: String? = null,
    val threadId: String? = null,
    val saveAsDraft: Boolean = false,
)

@Serializable
data class MessageActionRequest(
    val messageIds: List<String>,
    val isRead: Boolean? = null,
    val isStarred: Boolean? = null,
    val isImportant: Boolean? = null,
    val isArchived: Boolean? = null,
    val isTrashed: Boolean? = null,
    val isSpam: Boolean? = null,
)

@Serializable
data class AssignCategoryRequest(
    val messageIds: List<String>,
    val categoryId: String? = null,
)

@Serializable
data class UpdatePreferencesRequest(
    val theme: UiTheme? = null,
    val density: UiDensity? = null,
    val displayName: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
    val description: String? = null,
    val color: String = "#4F46E5",
    val icon: String = "label",
)

@Serializable
data class UpdateCategoryRequest(
    val name: String? = null,
    val description: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val isEnabled: Boolean? = null,
)

@Serializable
data class HandoffExchangeRequest(val code: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String? = null, val allSessions: Boolean = false)

/** The uniform error body every JMail endpoint returns. */
@Serializable
data class ApiErrorBody(
    val code: String = "unknown_error",
    val message: String = "Something went wrong",
    val details: Map<String, String> = emptyMap(),
    val path: String? = null,
    @SerialName("timestamp") val timestamp: String? = null,
)
