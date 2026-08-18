package com.jmail.backend.user

import com.jmail.backend.auth.CredentialCipher
import com.jmail.backend.auth.ExchangeCredentials
import com.jmail.backend.auth.oauth.OAuthTokens
import com.jmail.backend.auth.oauth.ProviderProfile
import com.jmail.backend.common.EmailAddresses
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Turns a successful authentication into persisted rows: the person, and the mailbox they
 * just proved they own.
 *
 * Identity is keyed on the email address, so signing in with Google and later with Microsoft
 * using the same address lands in one JMail account with two mailboxes attached, rather than
 * two disconnected profiles. Signing in again with a mailbox that is already linked updates
 * its credentials instead of creating a duplicate.
 */
@Service
class AccountProvisioningService(
    private val userRepository: UserRepository,
    private val mailAccountRepository: MailAccountRepository,
    private val credentialCipher: CredentialCipher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param linkToUserId when set, the mailbox is attached to that existing user instead of
     *   resolving a user from the address — this is "add another account" rather than "sign in".
     */
    @Transactional
    fun completeOAuthSignIn(
        provider: AccountProvider,
        profile: ProviderProfile,
        tokens: OAuthTokens,
        linkToUserId: UUID? = null,
        displayNameOverride: String? = null,
    ): UserAccount {
        val now = Instant.now()
        val email = EmailAddresses.canonical(profile.email)
        val displayName = displayNameOverride?.takeIf { it.isNotBlank() } ?: profile.displayName

        val user = linkToUserId
            ?.let { id -> userRepository.findById(id).orElse(null) }
            ?: findOrCreateUser(email, displayName, profile.avatarUrl, now)

        val existing = mailAccountRepository.findByUserIdAndProviderAndProviderAccountId(
            userId = user.id,
            provider = provider,
            providerAccountId = profile.providerAccountId,
        )

        val account = existing ?: MailAccount(
            userId = user.id,
            provider = provider,
            providerAccountId = profile.providerAccountId,
            isPrimary = mailAccountRepository.countByUserId(user.id) == 0L,
            color = colorFor(mailAccountRepository.countByUserId(user.id).toInt()),
        )

        account.email = email
        account.displayName = displayName
        account.avatarUrl = profile.avatarUrl ?: account.avatarUrl
        account.accessToken = credentialCipher.encrypt(tokens.accessToken)
        // Providers omit the refresh token when re-authorising an existing grant; keeping the
        // stored one is the difference between a working account and a forced reconnect.
        account.refreshToken = tokens.refreshToken?.let(credentialCipher::encrypt) ?: account.refreshToken
        account.tokenExpiresAt = tokens.expiresAt(now)
        account.scopes = tokens.scope ?: account.scopes
        account.status = AccountStatus.CONNECTED
        account.statusDetail = null
        account.updatedAt = now

        mailAccountRepository.save(account)
        touchLogin(user, now)

        log.info("Linked {} mailbox for user {}", provider, user.id)
        return user
    }

    @Transactional
    fun completeCredentialSignIn(
        provider: AccountProvider,
        credentials: ExchangeCredentials,
        linkToUserId: UUID? = null,
    ): UserAccount {
        val now = Instant.now()
        val email = EmailAddresses.canonical(credentials.email)
        val displayName = credentials.displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')

        val user = linkToUserId
            ?.let { id -> userRepository.findById(id).orElse(null) }
            ?: findOrCreateUser(email, displayName, null, now)

        val existing = mailAccountRepository.findByUserIdAndProviderAndProviderAccountId(
            userId = user.id,
            provider = provider,
            providerAccountId = email,
        )

        val account = existing ?: MailAccount(
            userId = user.id,
            provider = provider,
            providerAccountId = email,
            isPrimary = mailAccountRepository.countByUserId(user.id) == 0L,
            color = colorFor(mailAccountRepository.countByUserId(user.id).toInt()),
        )

        account.email = email
        account.displayName = displayName
        account.username = credentials.username
        account.passwordSecret = credentialCipher.encrypt(credentials.password)
        account.imapHost = credentials.imapHost
        account.imapPort = credentials.imapPort
        account.smtpHost = credentials.smtpHost
        account.smtpPort = credentials.smtpPort
        account.useTls = credentials.useTls
        account.status = AccountStatus.CONNECTED
        account.statusDetail = null
        account.updatedAt = now

        mailAccountRepository.save(account)
        touchLogin(user, now)

        log.info("Linked {} mailbox for user {}", provider, user.id)
        return user
    }

    @Transactional(readOnly = true)
    fun accountsOf(userId: UUID): List<MailAccount> =
        mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(userId)

    private fun findOrCreateUser(
        email: String,
        displayName: String,
        avatarUrl: String?,
        now: Instant,
    ): UserAccount {
        val existing = userRepository.findByEmail(email)
        if (existing != null) {
            // Only fill gaps: a provider's idea of the display name should not overwrite one
            // the user has since edited in JMail.
            if (existing.avatarUrl == null && avatarUrl != null) existing.avatarUrl = avatarUrl
            return userRepository.save(existing)
        }

        return userRepository.save(
            UserAccount(
                email = email,
                displayName = displayName,
                avatarUrl = avatarUrl,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun touchLogin(user: UserAccount, now: Instant) {
        user.lastLoginAt = now
        user.updatedAt = now
        userRepository.save(user)
    }

    /** Distinct accent colours so a unified inbox stays readable at a glance. */
    private fun colorFor(index: Int): String = ACCOUNT_COLORS[index % ACCOUNT_COLORS.size]

    private companion object {
        val ACCOUNT_COLORS = listOf("#4F46E5", "#0EA5E9", "#10B981", "#F59E0B", "#EC4899", "#8B5CF6")
    }
}
