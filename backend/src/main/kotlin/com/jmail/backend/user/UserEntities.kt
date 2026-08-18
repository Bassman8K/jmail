package com.jmail.backend.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Domain entities are mapped with plain UUID foreign-key columns rather than JPA
 * associations. Every read path in JMail is an explicit, owner-scoped query, so lazy
 * associations would only add proxy initialisation and N+1 risk to the message list.
 */

enum class UiDensity { COMPACT, COMFORTABLE, SPACIOUS }

enum class UiTheme { SYSTEM, LIGHT, DARK }

@Entity
@Table(name = "users")
class UserAccount(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    /** Always stored lowercased; see EmailAddresses.canonical. */
    @Column(name = "email", nullable = false, length = 320)
    var email: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(name = "avatar_url", length = 1000)
    var avatarUrl: String? = null,

    @Column(name = "locale", nullable = false, length = 20)
    var locale: String = "en",

    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String = "UTC",

    @Enumerated(EnumType.STRING)
    @Column(name = "density", nullable = false, length = 20)
    var density: UiDensity = UiDensity.COMFORTABLE,

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 20)
    var theme: UiTheme = UiTheme.SYSTEM,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
) {
    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

enum class AccountProvider {
    GOOGLE,
    MICROSOFT,
    APPLE,
    /** Microsoft Exchange, on-premises or hosted, reached over EWS/IMAP with credentials. */
    EXCHANGE,
    /** Any other IMAP/SMTP server. */
    IMAP,
    /** Seeded local mailbox used by the demo sign-in. */
    DEMO,
    ;

    val displayName: String
        get() = when (this) {
            GOOGLE -> "Google"
            MICROSOFT -> "Microsoft"
            APPLE -> "Apple"
            EXCHANGE -> "Microsoft Exchange"
            IMAP -> "IMAP"
            DEMO -> "Demo"
        }

    /** True when tokens come from an OAuth flow rather than stored credentials. */
    val isOAuth: Boolean
        get() = this == GOOGLE || this == MICROSOFT || this == APPLE
}

enum class AccountStatus { CONNECTED, REAUTH_REQUIRED, SYNCING, ERROR, DISABLED }

@Entity
@Table(name = "mail_accounts")
class MailAccount(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    var provider: AccountProvider = AccountProvider.DEMO,

    /** The provider's own stable identifier for this mailbox (`sub`, Graph id, or address). */
    @Column(name = "provider_account_id", nullable = false, length = 255)
    var providerAccountId: String = "",

    @Column(name = "email", nullable = false, length = 320)
    var email: String = "",

    @Column(name = "display_name", nullable = false, length = 200)
    var displayName: String = "",

    @Column(name = "avatar_url", length = 1000)
    var avatarUrl: String? = null,

    // ---- OAuth credentials (encrypted at rest by CredentialCipher) ----
    @Column(name = "access_token", columnDefinition = "text")
    var accessToken: String? = null,

    @Column(name = "refresh_token", columnDefinition = "text")
    var refreshToken: String? = null,

    @Column(name = "token_expires_at")
    var tokenExpiresAt: Instant? = null,

    @Column(name = "scopes", columnDefinition = "text")
    var scopes: String? = null,

    // ---- Credential connections (Exchange / IMAP) ----
    @Column(name = "imap_host", length = 255)
    var imapHost: String? = null,

    @Column(name = "imap_port")
    var imapPort: Int? = null,

    @Column(name = "smtp_host", length = 255)
    var smtpHost: String? = null,

    @Column(name = "smtp_port")
    var smtpPort: Int? = null,

    @Column(name = "ews_url", length = 1000)
    var ewsUrl: String? = null,

    @Column(name = "username", length = 255)
    var username: String? = null,

    @Column(name = "password_secret", columnDefinition = "text")
    var passwordSecret: String? = null,

    @Column(name = "use_tls", nullable = false)
    var useTls: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: AccountStatus = AccountStatus.CONNECTED,

    @Column(name = "status_detail", length = 500)
    var statusDetail: String? = null,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    /** Accent colour used to tint this account's messages in a unified inbox. */
    @Column(name = "color", length = 9)
    var color: String? = null,

    @Column(name = "last_sync_at")
    var lastSyncAt: Instant? = null,

    @Column(name = "sync_cursor", length = 500)
    var syncCursor: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    /** True when the access token is absent or expires within the next minute. */
    fun needsTokenRefresh(now: Instant = Instant.now()): Boolean {
        if (!provider.isOAuth) return false
        val expiry = tokenExpiresAt ?: return accessToken == null
        return expiry.isBefore(now.plusSeconds(60))
    }

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}
