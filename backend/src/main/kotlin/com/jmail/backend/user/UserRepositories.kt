package com.jmail.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<UserAccount, UUID> {

    fun findByEmail(email: String): UserAccount?

    fun existsByEmail(email: String): Boolean
}

@Repository
interface MailAccountRepository : JpaRepository<MailAccount, UUID> {

    fun findAllByUserIdOrderByIsPrimaryDescCreatedAtAsc(userId: UUID): List<MailAccount>

    /** Ownership-scoped lookup: the only way a controller should fetch an account. */
    fun findByIdAndUserId(id: UUID, userId: UUID): MailAccount?

    fun findByUserIdAndProviderAndProviderAccountId(
        userId: UUID,
        provider: AccountProvider,
        providerAccountId: String,
    ): MailAccount?

    fun findFirstByUserIdAndIsPrimaryTrue(userId: UUID): MailAccount?

    fun countByUserId(userId: UUID): Long

    @Query(
        """
        SELECT account FROM MailAccount account
        WHERE account.status IN (com.jmail.backend.user.AccountStatus.CONNECTED)
          AND (account.lastSyncAt IS NULL OR account.lastSyncAt < :staleBefore)
        ORDER BY account.lastSyncAt ASC NULLS FIRST
        """,
    )
    fun findAccountsDueForSync(@Param("staleBefore") staleBefore: Instant): List<MailAccount>

    @Modifying
    @Query("UPDATE MailAccount account SET account.isPrimary = false WHERE account.userId = :userId")
    fun clearPrimaryFlag(@Param("userId") userId: UUID): Int
}
