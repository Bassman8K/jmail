package com.jmail.backend.mail.provider

import com.jmail.backend.auth.CredentialCipher
import com.jmail.backend.auth.oauth.OAuthClient
import com.jmail.backend.auth.oauth.OAuthClientRegistry
import com.jmail.backend.auth.oauth.OAuthTokens
import com.jmail.backend.common.BadRequestException
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.ProviderException
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Token refreshing and provider selection — the plumbing every mail operation goes through.
 *
 * The important property is that a revoked grant becomes one clear, actionable state
 * (`REAUTH_REQUIRED`) rather than an endless stream of failing syncs.
 */
class ProviderTokenServiceTest {

    private val registry: OAuthClientRegistry = mockk()
    private val oauthClient: OAuthClient = mockk()
    private val accountRepository: MailAccountRepository = mockk()
    private val cipher = CredentialCipher(
        JmailProperties(security = JmailProperties.SecurityProperties(encryptionKey = "test-key")),
    )

    private lateinit var service: ProviderTokenService

    @BeforeEach
    fun setUp() {
        service = ProviderTokenService(registry, cipher, accountRepository)
        every { accountRepository.save(any()) } returnsArgument 0
        every { registry.clientFor(any()) } returns oauthClient
    }

    private fun oauthAccount(
        accessToken: String? = "valid-access",
        refreshToken: String? = "valid-refresh",
        expiresAt: Instant? = Instant.now().plusSeconds(3_600),
    ) = MailAccount(
        provider = AccountProvider.GOOGLE,
        accessToken = accessToken?.let(cipher::encrypt),
        refreshToken = refreshToken?.let(cipher::encrypt),
        tokenExpiresAt = expiresAt,
    )

    @Test
    fun a_valid_token_is_used_without_contacting_the_provider() {
        val account = oauthAccount()

        assertEquals("valid-access", service.accessTokenFor(account))
    }

    @Test
    fun an_expiring_token_is_refreshed_and_the_new_one_stored() {
        val account = oauthAccount(expiresAt = Instant.now().plusSeconds(10))
        every { oauthClient.refreshAccessToken("valid-refresh") } returns
            OAuthTokens(accessToken = "fresh-access", refreshToken = "rotated-refresh", expiresInSeconds = 3_600)

        val token = service.accessTokenFor(account)

        assertEquals("fresh-access", token)
        assertEquals("fresh-access", cipher.decrypt(account.accessToken))
        assertEquals("rotated-refresh", cipher.decrypt(account.refreshToken))
        assertEquals(AccountStatus.CONNECTED, account.status)
    }

    @Test
    fun a_refresh_that_omits_a_new_refresh_token_keeps_the_existing_one() {
        val account = oauthAccount(expiresAt = Instant.now().minusSeconds(1))
        every { oauthClient.refreshAccessToken(any()) } returns OAuthTokens(accessToken = "fresh")

        service.accessTokenFor(account)

        assertEquals("valid-refresh", cipher.decrypt(account.refreshToken))
    }

    @Test
    fun a_rejected_refresh_token_marks_the_account_for_reconnection() {
        val account = oauthAccount(expiresAt = Instant.now().minusSeconds(1))
        every { oauthClient.refreshAccessToken(any()) } throws RuntimeException("invalid_grant")

        assertThrows<ReauthenticationRequiredException> { service.accessTokenFor(account) }

        assertEquals(AccountStatus.REAUTH_REQUIRED, account.status)
        assertNotNull(account.statusDetail)
    }

    @Test
    fun an_account_with_no_refresh_token_is_marked_rather_than_retried() {
        val account = oauthAccount(accessToken = null, refreshToken = null, expiresAt = null)

        assertThrows<ReauthenticationRequiredException> { service.accessTokenFor(account) }

        assertEquals(AccountStatus.REAUTH_REQUIRED, account.status)
    }

    @Test
    fun a_provider_that_is_no_longer_configured_asks_for_reconnection() {
        val account = oauthAccount(expiresAt = Instant.now().minusSeconds(1))
        every { registry.clientFor(any()) } throws BadRequestException("provider_not_configured", "gone")

        assertThrows<ReauthenticationRequiredException> { service.accessTokenFor(account) }
    }

    @Test
    fun a_credential_account_has_no_token_to_hand_out() {
        assertThrows<IllegalArgumentException> {
            service.accessTokenFor(MailAccount(provider = AccountProvider.EXCHANGE))
        }
    }

    @Test
    fun a_stored_password_is_decrypted_for_the_imap_client() {
        val account = MailAccount(
            provider = AccountProvider.EXCHANGE,
            passwordSecret = cipher.encrypt("hunter2"),
        )

        assertEquals("hunter2", service.passwordFor(account))
    }

    @Test
    fun a_missing_password_asks_the_user_to_reconnect() {
        assertThrows<ReauthenticationRequiredException> {
            service.passwordFor(MailAccount(provider = AccountProvider.EXCHANGE))
        }
    }
}

/** Provider selection, including the deliberate aliasing of IMAP onto the Exchange client. */
class MailProviderRegistryTest {

    private val gmail: MailProvider = mockk { every { provider } returns AccountProvider.GOOGLE }
    private val graph: MailProvider = mockk { every { provider } returns AccountProvider.MICROSOFT }
    private val imap: MailProvider = mockk { every { provider } returns AccountProvider.EXCHANGE }
    private val demo: MailProvider = mockk { every { provider } returns AccountProvider.DEMO }

    private val registry = MailProviderRegistry(listOf(gmail, graph, imap, demo))

    @Test
    fun each_account_resolves_to_its_own_client() {
        assertEquals(gmail, registry.forAccount(MailAccount(provider = AccountProvider.GOOGLE)))
        assertEquals(graph, registry.forAccount(MailAccount(provider = AccountProvider.MICROSOFT)))
        assertEquals(demo, registry.forAccount(MailAccount(provider = AccountProvider.DEMO)))
    }

    @Test
    fun a_plain_imap_account_reuses_the_exchange_client() {
        // The protocol is identical; the distinction exists for labelling and autodiscovery.
        assertEquals(imap, registry.forAccount(MailAccount(provider = AccountProvider.IMAP)))
    }

    @Test
    fun a_provider_with_no_client_is_reported_clearly() {
        val failure = assertThrows<BadRequestException> {
            MailProviderRegistry(listOf(gmail)).forProvider(AccountProvider.MICROSOFT)
        }

        assertEquals("unsupported_provider", failure.code)
    }

    @Test
    fun an_apple_account_without_a_mailbox_is_not_syncable() {
        // Sign in with Apple proves identity; iCloud mail is added separately over IMAP.
        assertTrue(!registry.canSync(MailAccount(provider = AccountProvider.APPLE)))
        assertTrue(registry.canSync(MailAccount(provider = AccountProvider.APPLE, imapHost = "imap.mail.me.com")))
        assertTrue(registry.canSync(MailAccount(provider = AccountProvider.GOOGLE)))
    }
}

/** The demo mailbox behaves like a provider so nothing else has to special-case it. */
class DemoMailProviderTest {

    private val provider = DemoMailProvider()
    private val account = MailAccount(provider = AccountProvider.DEMO, email = "demo@jmail.app")

    @Test
    fun it_has_nothing_remote_to_sync() {
        assertTrue(provider.listFolders(account).isEmpty())
        assertTrue(
            provider.fetchMessages(
                account,
                RemoteFolder(remoteId = "INBOX", name = "Inbox"),
            ).messages.isEmpty(),
        )
    }

    @Test
    fun sending_succeeds_without_leaving_the_machine() {
        val id = provider.sendMessage(
            account,
            OutgoingMessage(
                to = listOf(EmailAddress("tom@example.com")),
                subject = "Hello",
                bodyText = "Hi",
            ),
        )

        assertTrue(id.startsWith("demo-sent-"))
        assertTrue(provider.supportsSending)
    }

    @Test
    fun flag_changes_and_downloads_are_no_ops() {
        provider.applyFlags(account, "demo-1", FlagUpdate(isRead = true))

        assertNull(provider.downloadAttachment(account, "demo-1", "att-1"))
    }
}
