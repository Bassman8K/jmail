package com.jmail.backend.user

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.auth.dto.AccountResponse
import com.jmail.backend.auth.dto.UpdatePreferencesRequest
import com.jmail.backend.auth.dto.UserResponse
import com.jmail.backend.common.BadRequestException
import com.jmail.backend.common.NotFoundException
import com.jmail.backend.mail.FolderRepository
import com.jmail.backend.mail.MessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The signed-in person and the mailboxes they have connected.
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val mailAccountRepository: MailAccountRepository,
    private val messageRepository: MessageRepository,
    private val folderRepository: FolderRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun currentUser(user: AuthenticatedUser): UserResponse {
        val entity = userRepository.findById(user.userId)
            .orElseThrow { NotFoundException("User", user.userId) }

        return UserResponse.from(
            entity,
            mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(entity.id),
        )
    }

    @Transactional
    fun updatePreferences(user: AuthenticatedUser, request: UpdatePreferencesRequest): UserResponse {
        val entity = userRepository.findById(user.userId)
            .orElseThrow { NotFoundException("User", user.userId) }

        request.theme?.let { entity.theme = it }
        request.density?.let { entity.density = it }
        request.displayName?.takeIf { it.isNotBlank() }?.let { entity.displayName = it.trim() }
        request.timezone?.takeIf { it.isNotBlank() }?.let { entity.timezone = it.trim() }
        request.locale?.takeIf { it.isNotBlank() }?.let { entity.locale = it.trim() }
        entity.updatedAt = Instant.now()

        val saved = userRepository.save(entity)
        return UserResponse.from(
            saved,
            mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(saved.id),
        )
    }

    @Transactional(readOnly = true)
    fun accounts(user: AuthenticatedUser): List<AccountResponse> =
        mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId)
            .map(AccountResponse::from)

    /**
     * Disconnects a mailbox and removes everything synced from it.
     *
     * Deliberately destructive: leaving a disconnected account's messages behind would mean
     * search results the user cannot open and counts they cannot clear.
     */
    @Transactional
    fun unlinkAccount(user: AuthenticatedUser, accountId: UUID) {
        val account = mailAccountRepository.findByIdAndUserId(accountId, user.userId)
            ?: throw NotFoundException("Account", accountId)

        val remaining = mailAccountRepository.countByUserId(user.userId)
        if (remaining <= 1) {
            throw BadRequestException(
                "last_account",
                "This is your only mailbox. Connect another one before disconnecting it.",
            )
        }

        messageRepository.deleteAllByAccountId(account.id)
        folderRepository.deleteAllByAccountId(account.id)
        mailAccountRepository.delete(account)

        // The primary flag must always land somewhere, or composing has no default sender.
        if (account.isPrimary) {
            mailAccountRepository.findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(user.userId)
                .firstOrNull()
                ?.let { next ->
                    next.isPrimary = true
                    mailAccountRepository.save(next)
                }
        }

        log.info("User {} disconnected {} account {}", user.userId, account.provider, account.id)
    }

    @Transactional
    fun setPrimaryAccount(user: AuthenticatedUser, accountId: UUID): List<AccountResponse> {
        val account = mailAccountRepository.findByIdAndUserId(accountId, user.userId)
            ?: throw NotFoundException("Account", accountId)

        mailAccountRepository.clearPrimaryFlag(user.userId)
        account.isPrimary = true
        account.updatedAt = Instant.now()
        mailAccountRepository.save(account)

        return accounts(user)
    }
}
