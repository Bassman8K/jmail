package com.jmail.backend.user

import com.jmail.backend.auth.CredentialCipher
import com.jmail.backend.auth.oauth.OAuthTokens
import com.jmail.backend.auth.oauth.ProviderProfile
import com.jmail.backend.config.JmailProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning a successful sign-in into rows.
 *
 * The behaviour that matters here is identity: signing in with Google and later with
 * Microsoft using the same address must land in *one* JMail account with two mailboxes, and
 * reconnecting an existing mailbox must update it rather than creating a second copy.
 */
class AccountProvisioningServiceTest {

    private val userRepository: UserRepository = mockk()
    private val accountRepository: MailAccountRepository = mockk()
    private val cipher = CredentialCipher(
        JmailProperties(security = JmailProperties.SecurityProperties(encryptionKey = "test-key-material")),
    )

    private lateinit var service: AccountProvisioningService

    @BeforeEach
    fun setUp() {
        service = AccountProvisioningService(userRepository, accountRepository, cipher)
        every { userRepository.save(any()) } returnsArgument 0
        every { accountRepository.save(any()) } returnsArgument 0
        every { accountRepository.countByUserId(any()) } returns 0
    }

    private val profile = ProviderProfile(
        providerAccountId = "google-sub-1",
        email = "Ada@Example.com",
        displayName = "Ada Lovelace",
        avatarUrl = "https://example.com/ada.png",
    )

    private val tokens = OAuthTokens(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresInSeconds = 3_600,
        scope = "openid email",
    )

    @Test
    fun `a first sign-in creates the person and their mailbox`() {
        every { userRepository.findByEmail("ada@example.com") } returns null
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        val savedAccount = slot<MailAccount>()
        every { accountRepository.save(capture(savedAccount)) } answers { savedAccount.captured }

        val user = service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens)

        assertEquals("ada@example.com", user.email) // canonicalised
        assertEquals("Ada Lovelace", user.displayName)
        assertEquals(AccountProvider.GOOGLE, savedAccount.captured.provider)
        assertTrue(savedAccount.captured.isPrimary, "the first mailbox becomes the default sender")
        assertNotNull(savedAccount.captured.color)
        assertNotNull(user.lastLoginAt)
    }

    @Test
    fun `credentials are encrypted before they are stored`() {
        every { userRepository.findByEmail(any()) } returns null
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        val saved = slot<MailAccount>()
        every { accountRepository.save(capture(saved)) } answers { saved.captured }

        service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens)

        assertNotEquals("access-token", saved.captured.accessToken)
        assertEquals("access-token", cipher.decrypt(saved.captured.accessToken))
        assertEquals("refresh-token", cipher.decrypt(saved.captured.refreshToken))
    }

    @Test
    fun `signing in again with the same address attaches to the existing person`() {
        val existing = UserAccount(email = "ada@example.com", displayName = "Ada Lovelace")
        every { userRepository.findByEmail("ada@example.com") } returns existing
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        every { accountRepository.countByUserId(existing.id) } returns 1

        val user = service.completeOAuthSignIn(AccountProvider.MICROSOFT, profile, tokens)

        assertEquals(existing.id, user.id, "the same address is the same person, whatever the provider")
    }

    @Test
    fun `a second mailbox does not steal the primary flag`() {
        val existing = UserAccount(email = "ada@example.com", displayName = "Ada")
        every { userRepository.findByEmail(any()) } returns existing
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        every { accountRepository.countByUserId(existing.id) } returns 1
        val saved = slot<MailAccount>()
        every { accountRepository.save(capture(saved)) } answers { saved.captured }

        service.completeOAuthSignIn(AccountProvider.MICROSOFT, profile, tokens)

        assertFalse(saved.captured.isPrimary)
    }

    @Test
    fun `reconnecting an existing mailbox updates it instead of duplicating it`() {
        val user = UserAccount(email = "ada@example.com", displayName = "Ada")
        val existingAccount = MailAccount(
            userId = user.id,
            provider = AccountProvider.GOOGLE,
            providerAccountId = "google-sub-1",
            email = "ada@example.com",
            status = AccountStatus.REAUTH_REQUIRED,
            statusDetail = "Reconnect this account",
            refreshToken = cipher.encrypt("old-refresh"),
        )
        every { userRepository.findByEmail(any()) } returns user
        every {
            accountRepository.findByUserIdAndProviderAndProviderAccountId(user.id, AccountProvider.GOOGLE, "google-sub-1")
        } returns existingAccount

        service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens)

        assertEquals(AccountStatus.CONNECTED, existingAccount.status)
        assertNull(existingAccount.statusDetail)
        verify(exactly = 1) { accountRepository.save(existingAccount) }
    }

    @Test
    fun `a refresh token is kept when the provider omits it on reconnect`() {
        val user = UserAccount(email = "ada@example.com", displayName = "Ada")
        val existingAccount = MailAccount(
            userId = user.id,
            provider = AccountProvider.GOOGLE,
            providerAccountId = "google-sub-1",
            refreshToken = cipher.encrypt("the-only-refresh-token"),
        )
        every { userRepository.findByEmail(any()) } returns user
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns existingAccount

        // Google issues a refresh token on first consent only.
        service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens.copy(refreshToken = null))

        assertEquals("the-only-refresh-token", cipher.decrypt(existingAccount.refreshToken))
    }

    @Test
    fun `linking adds the mailbox to the signed-in user rather than resolving by address`() {
        val signedInUser = UserAccount(email = "someone-else@example.com", displayName = "Someone")
        every { userRepository.findById(signedInUser.id) } returns Optional.of(signedInUser)
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        every { accountRepository.countByUserId(signedInUser.id) } returns 1

        val user = service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens, linkToUserId = signedInUser.id)

        assertEquals(signedInUser.id, user.id)
        verify(exactly = 0) { userRepository.findByEmail(any()) }
    }

    @Test
    fun `a display name the user edited is not overwritten by the provider`() {
        val existing = UserAccount(email = "ada@example.com", displayName = "Ada (work)")
        every { userRepository.findByEmail(any()) } returns existing
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        every { accountRepository.countByUserId(any()) } returns 1

        assertEquals("Ada (work)", service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens).displayName)
    }

    @Test
    fun `a missing avatar is filled in from the provider`() {
        val existing = UserAccount(email = "ada@example.com", displayName = "Ada", avatarUrl = null)
        every { userRepository.findByEmail(any()) } returns existing
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null
        every { accountRepository.countByUserId(any()) } returns 1

        assertEquals(
            "https://example.com/ada.png",
            service.completeOAuthSignIn(AccountProvider.GOOGLE, profile, tokens).avatarUrl,
        )
    }

    @Test
    fun `an Apple display name override wins over the derived one`() {
        every { userRepository.findByEmail(any()) } returns null
        every { accountRepository.findByUserIdAndProviderAndProviderAccountId(any(), any(), any()) } returns null

        val user = service.completeOAuthSignIn(
            AccountProvider.APPLE,
            profile.copy(displayName = "ada"), // Apple's ID token carries no name
            tokens,
            displayNameOverride = "Ada Lovelace",
        )

        assertEquals("Ada Lovelace", user.displayName)
    }

    @Test
    fun `accounts are listed for a user`() {
        val userId = UUID.randomUUID()
        every { accountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(userId) } returns
            listOf(MailAccount(userId = userId))

        assertEquals(1, service.accountsOf(userId).size)
    }

    @Test
    fun `an account knows when its token needs refreshing`() {
        val oauth = MailAccount(provider = AccountProvider.GOOGLE)
        assertTrue(oauth.needsTokenRefresh(), "no expiry recorded means refresh before use")

        oauth.tokenExpiresAt = java.time.Instant.now().plusSeconds(3_600)
        assertFalse(oauth.needsTokenRefresh())

        oauth.tokenExpiresAt = java.time.Instant.now().plusSeconds(30)
        assertTrue(oauth.needsTokenRefresh(), "expiring within the minute counts as expired")

        // Credential accounts have no token lifetime at all.
        assertFalse(MailAccount(provider = AccountProvider.EXCHANGE).needsTokenRefresh())
    }
}
