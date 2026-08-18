package com.jmail.backend.auth

import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Properties

/** Everything needed to connect to a mailbox with a username and password. */
data class ExchangeCredentials(
    val email: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String,
    val smtpPort: Int = 587,
    val username: String = email,
    val useTls: Boolean = true,
    val displayName: String? = null,
)

/** Server settings suggested for an address, shown pre-filled on the sign-in form. */
data class ExchangeSuggestion(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val useTls: Boolean = true,
    /** True when the suggestion comes from a known provider rather than a naming guess. */
    val confident: Boolean = false,
    /** The recognised service, when the domain identifies one. */
    val provider: KnownMailProvider? = null,
)

/**
 * Sign-in for Microsoft Exchange mailboxes that are not reachable through Entra ID: on-premises
 * servers and hosted Exchange behind a corporate gateway. These have no OAuth endpoint, so the
 * user supplies credentials and JMail proves them by opening a real IMAP session.
 *
 * Credentials are verified before anything is stored, so a typo never creates a broken account,
 * and are then held encrypted (see CredentialCipher) — never in plaintext or in a log line.
 */
@Component
class ExchangeAuthenticator(private val properties: JmailProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Opens an IMAP session with the supplied credentials.
     *
     * @throws UnauthorizedException when the server rejects the credentials or cannot be reached.
     */
    fun verify(credentials: ExchangeCredentials) {
        val exchange = properties.exchange
        val protocol = if (credentials.useTls) "imaps" else "imap"

        val sessionProperties = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", credentials.imapHost)
            put("mail.$protocol.port", credentials.imapPort.toString())
            put("mail.$protocol.connectiontimeout", exchange.connectTimeout.toMillis().toString())
            put("mail.$protocol.timeout", exchange.readTimeout.toMillis().toString())
            put("mail.$protocol.writetimeout", exchange.readTimeout.toMillis().toString())
            if (credentials.useTls) {
                put("mail.imaps.ssl.enable", "true")
                // Exchange servers commonly present certificates for a different internal
                // hostname; identity is still proven by the credential exchange itself.
                put("mail.imaps.ssl.checkserveridentity", "true")
            } else {
                put("mail.imap.starttls.enable", "true")
            }
        }

        val store = Session.getInstance(sessionProperties).getStore(protocol)
        try {
            store.connect(credentials.imapHost, credentials.imapPort, credentials.username, credentials.password)
            log.info("Verified Exchange credentials for {}", maskedAddress(credentials.email))
        } catch (failure: AuthenticationFailedException) {
            throw UnauthorizedException(
                "That username or password was not accepted by ${credentials.imapHost}",
                "exchange_authentication_failed",
            )
        } catch (failure: MessagingException) {
            log.warn("Could not reach Exchange server {}:{}", credentials.imapHost, credentials.imapPort, failure)
            throw UnauthorizedException(
                "Could not reach ${credentials.imapHost}:${credentials.imapPort}. Check the server and port.",
                "exchange_unreachable",
            )
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Suggests server settings for an address so most people never open the advanced section.
     *
     * Microsoft-hosted domains resolve to Exchange Online; anything else falls back to the
     * `imap.<domain>` / `smtp.<domain>` convention, flagged as a guess so the UI can say so.
     */
    fun suggestSettings(email: String): ExchangeSuggestion {
        val exchange = properties.exchange

        // A deployment pinned to one server wins: it is a deliberate operator decision.
        if (exchange.defaultHost.isNotBlank()) {
            return ExchangeSuggestion(
                imapHost = exchange.defaultHost,
                imapPort = exchange.defaultImapPort,
                smtpHost = exchange.defaultHost,
                smtpPort = exchange.defaultSmtpPort,
                confident = true,
                provider = KnownMailProviders.EXCHANGE_ON_PREMISES,
            )
        }

        // A recognised service: exact settings, and whatever the user needs to know about
        // app passwords before they try their account password and fail.
        KnownMailProviders.forEmail(email)?.let { known ->
            return ExchangeSuggestion(
                imapHost = known.imapHost,
                imapPort = known.imapPort,
                smtpHost = known.smtpHost,
                smtpPort = known.smtpPort,
                useTls = known.useTls,
                confident = true,
                provider = known,
            )
        }

        val domain = EmailAddresses.domainOf(email)
        if (domain.isEmpty()) {
            return ExchangeSuggestion("", 993, "", 587, confident = false)
        }

        // Unknown domain: fall back to the naming convention most self-hosted servers use,
        // flagged as a guess so the UI can say so rather than presenting it as fact.
        return ExchangeSuggestion(
            imapHost = "imap.$domain",
            imapPort = 993,
            smtpHost = "smtp.$domain",
            smtpPort = 587,
            confident = false,
        )
    }

    /** Everything offered on the "sign in with your email" picker. */
    fun knownProviders(): List<KnownMailProvider> = KnownMailProviders.all

    /** `ada@example.com` becomes `a**@example.com`; enough to correlate, not enough to leak. */
    private fun maskedAddress(email: String): String {
        val local = email.substringBefore('@')
        val domain = email.substringAfter('@', "")
        val masked = if (local.length <= 1) "*" else local.first() + "*".repeat(local.length - 1)
        return if (domain.isEmpty()) masked else "$masked@$domain"
    }

}
