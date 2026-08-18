package com.jmail.backend.mail.provider

import com.jmail.backend.auth.CredentialCipher
import com.jmail.backend.auth.oauth.OAuthClientRegistry
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Hands out a currently-valid access token for a linked mailbox, refreshing it when needed.
 *
 * Providers issue access tokens that live for an hour or less, so almost every sync run
 * needs a refresh. Doing it here means no provider implementation has to think about token
 * lifetimes, and a revoked grant is turned into one clear signal — the account is marked
 * `REAUTH_REQUIRED` and the user is asked to reconnect, rather than every sync failing
 * mysteriously.
 */
@Service
class ProviderTokenService(
    private val registry: OAuthClientRegistry,
    private val credentialCipher: CredentialCipher,
    private val mailAccountRepository: MailAccountRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs in its own transaction so that a refreshed token is committed even if the sync
     * that triggered it later fails — otherwise the refresh would be rolled back and the
     * (now invalidated) old token written back on the next attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun accessTokenFor(account: MailAccount): String {
        require(account.provider.isOAuth) {
            "${account.provider} accounts authenticate with credentials, not tokens"
        }

        if (!account.needsTokenRefresh()) {
            return credentialCipher.decrypt(account.accessToken)
                ?: refresh(account) // key rotated or ciphertext unreadable: refresh from scratch
        }
        return refresh(account)
    }

    /** The stored password for an Exchange/IMAP mailbox. */
    fun passwordFor(account: MailAccount): String =
        credentialCipher.decrypt(account.passwordSecret)
            ?: throw ReauthenticationRequiredException(account.provider.displayName)

    private fun refresh(account: MailAccount): String {
        val refreshToken = credentialCipher.decrypt(account.refreshToken)
            ?: throw markReauthRequired(account, "no usable refresh token is stored")

        val client = runCatching { registry.clientFor(account.provider) }.getOrElse {
            throw markReauthRequired(account, "the provider is no longer configured on this server")
        }

        val tokens = runCatching { client.refreshAccessToken(refreshToken) }.getOrElse { failure ->
            log.warn("Refreshing the {} token for account {} failed", account.provider, account.id, failure)
            throw markReauthRequired(account, "the provider rejected the refresh token")
        }

        val now = Instant.now()
        account.accessToken = credentialCipher.encrypt(tokens.accessToken)
        // Providers rotate refresh tokens inconsistently; keep the existing one when the
        // response omits it, or the account would be unable to refresh again.
        tokens.refreshToken?.let { account.refreshToken = credentialCipher.encrypt(it) }
        account.tokenExpiresAt = tokens.expiresAt(now)
        account.status = AccountStatus.CONNECTED
        account.statusDetail = null
        account.updatedAt = now
        mailAccountRepository.save(account)

        log.debug("Refreshed the {} access token for account {}", account.provider, account.id)
        return tokens.accessToken
    }

    private fun markReauthRequired(account: MailAccount, reason: String): ReauthenticationRequiredException {
        account.status = AccountStatus.REAUTH_REQUIRED
        account.statusDetail = "Reconnect this account: $reason."
        account.updatedAt = Instant.now()
        mailAccountRepository.save(account)

        log.info("Account {} needs reauthentication: {}", account.id, reason)
        return ReauthenticationRequiredException(account.provider.displayName)
    }
}
