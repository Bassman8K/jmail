package com.jmail.backend.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * A long-lived refresh token, stored only as a SHA-256 hash so that a database leak
 * cannot be replayed against the API. Rotation is enforced: redeeming a token revokes it
 * and records the token that replaced it, which makes re-use of an old token detectable.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID(),

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String = "",

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "replaced_by")
    var replacedBy: UUID? = null,

    @Column(name = "user_agent", length = 500)
    var userAgent: String? = null,

    @Column(name = "client_ip", length = 64)
    var clientIp: String? = null,
) {
    fun isUsable(now: Instant = Instant.now()): Boolean = revokedAt == null && expiresAt.isAfter(now)
}

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    fun findAllByUserId(userId: UUID): List<RefreshToken>

    @Modifying
    @Query(
        """
        UPDATE RefreshToken token SET token.revokedAt = :now
        WHERE token.userId = :userId AND token.revokedAt IS NULL
        """,
    )
    fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

    /** Housekeeping: expired tokens carry no value and are pruned on a schedule. */
    @Modifying
    @Query("DELETE FROM RefreshToken token WHERE token.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}
