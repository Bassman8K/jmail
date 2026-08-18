package com.jmail.backend.user

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.auth.dto.UpdatePreferencesRequest
import com.jmail.backend.common.BadRequestException
import com.jmail.backend.common.NotFoundException
import com.jmail.backend.mail.FolderRepository
import com.jmail.backend.mail.MessageRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Account management. The rules worth pinning are the ones that protect the user from
 * ending up in an unusable state: never without a mailbox, and never without a default
 * sender.
 */
class UserServiceTest {

    private val userRepository: UserRepository = mockk(relaxed = true)
    private val accountRepository: MailAccountRepository = mockk(relaxed = true)
    private val messageRepository: MessageRepository = mockk(relaxed = true)
    private val folderRepository: FolderRepository = mockk(relaxed = true)

    private lateinit var service: UserService

    private val principal = AuthenticatedUser(UUID.randomUUID(), "ada@example.com", "Ada")
    private lateinit var entity: UserAccount

    @BeforeEach
    fun setUp() {
        service = UserService(userRepository, accountRepository, messageRepository, folderRepository)
        entity = UserAccount(id = principal.userId, email = "ada@example.com", displayName = "Ada")
        every { userRepository.findById(principal.userId) } returns Optional.of(entity)
        every { userRepository.save(any()) } returnsArgument 0
        // JpaRepository.save is generic (<S : T> save(S): S), so a relaxed mock cannot infer
        // the return type and hands back a bare Object. Stub it explicitly.
        every { accountRepository.save(any()) } returnsArgument 0
        every { accountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(any()) } returns emptyList()
    }

    @Test
    fun `the current user is returned with their mailboxes`() {
        every { accountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(principal.userId) } returns
            listOf(MailAccount(userId = principal.userId, email = "ada@example.com", isPrimary = true))

        val response = service.currentUser(principal)

        assertEquals("ada@example.com", response.email)
        assertEquals(1, response.accounts.size)
        assertTrue(response.accounts.single().isPrimary)
    }

    @Test
    fun `an unknown user is reported as not found`() {
        every { userRepository.findById(any()) } returns Optional.empty()

        assertThrows<NotFoundException> { service.currentUser(principal) }
    }

    @Test
    fun `preferences update only what was sent`() {
        val response = service.updatePreferences(
            principal,
            UpdatePreferencesRequest(theme = UiTheme.DARK, density = UiDensity.COMPACT),
        )

        assertEquals(UiTheme.DARK, response.theme)
        assertEquals(UiDensity.COMPACT, response.density)
        assertEquals("Ada", response.displayName) // untouched
    }

    @Test
    fun `blank preference values are ignored rather than wiping the field`() {
        service.updatePreferences(
            principal,
            UpdatePreferencesRequest(displayName = "   ", timezone = "", locale = "  "),
        )

        assertEquals("Ada", entity.displayName)
        assertEquals("UTC", entity.timezone)
        assertEquals("en", entity.locale)
    }

    @Test
    fun `preference values are trimmed`() {
        service.updatePreferences(
            principal,
            UpdatePreferencesRequest(displayName = "  Ada Lovelace  ", timezone = " Europe/London "),
        )

        assertEquals("Ada Lovelace", entity.displayName)
        assertEquals("Europe/London", entity.timezone)
    }

    @Test
    fun `disconnecting a mailbox removes what was synced from it`() {
        val account = MailAccount(userId = principal.userId, email = "work@example.com")
        every { accountRepository.findByIdAndUserId(account.id, principal.userId) } returns account
        every { accountRepository.countByUserId(principal.userId) } returns 2

        service.unlinkAccount(principal, account.id)

        // Leaving messages behind would mean search results the user cannot open.
        verify { messageRepository.deleteAllByAccountId(account.id) }
        verify { folderRepository.deleteAllByAccountId(account.id) }
        verify { accountRepository.delete(account) }
    }

    @Test
    fun `the last remaining mailbox cannot be disconnected`() {
        val account = MailAccount(userId = principal.userId)
        every { accountRepository.findByIdAndUserId(account.id, principal.userId) } returns account
        every { accountRepository.countByUserId(principal.userId) } returns 1

        val failure = assertThrows<BadRequestException> { service.unlinkAccount(principal, account.id) }

        assertEquals("last_account", failure.code)
        verify(exactly = 0) { accountRepository.delete(any()) }
    }

    @Test
    fun `disconnecting the primary mailbox promotes another one`() {
        val primary = MailAccount(userId = principal.userId, isPrimary = true)
        val other = MailAccount(userId = principal.userId, isPrimary = false)
        every { accountRepository.findByIdAndUserId(primary.id, principal.userId) } returns primary
        every { accountRepository.countByUserId(principal.userId) } returns 2
        every { accountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(principal.userId) } returns listOf(other)

        service.unlinkAccount(principal, primary.id)

        // Composing needs a default sender; the flag must always land somewhere.
        assertTrue(other.isPrimary)
    }

    @Test
    fun `another user's mailbox cannot be disconnected`() {
        every { accountRepository.findByIdAndUserId(any(), any()) } returns null

        assertThrows<NotFoundException> { service.unlinkAccount(principal, UUID.randomUUID()) }
    }

    @Test
    fun `choosing a primary mailbox clears the flag from the others first`() {
        val account = MailAccount(userId = principal.userId, isPrimary = false)
        every { accountRepository.findByIdAndUserId(account.id, principal.userId) } returns account

        service.setPrimaryAccount(principal, account.id)

        verify { accountRepository.clearPrimaryFlag(principal.userId) }
        assertTrue(account.isPrimary)
    }

    @Test
    fun `an unknown mailbox cannot be made primary`() {
        every { accountRepository.findByIdAndUserId(any(), any()) } returns null

        assertThrows<NotFoundException> { service.setPrimaryAccount(principal, UUID.randomUUID()) }
    }
}
