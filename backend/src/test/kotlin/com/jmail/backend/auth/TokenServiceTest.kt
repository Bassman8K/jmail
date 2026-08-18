package com.jmail.backend.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.UserAccount
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Session tokens are the app's front door, so the failure cases matter more than the happy
 * path: a token that is expired, tampered with, from another issuer, or replayed after
 * rotation must all be refused.
 */
class TokenServiceTest {

    private val refreshTokenRepository: RefreshTokenRepository = mockk(relaxed = true)
    private lateinit var tokenService: TokenService

    private val user = UserAccount(
        id = UUID.randomUUID(),
        email = "ada@example.com",
        displayName = "Ada Lovelace",
    )

    private fun properties(
        secret: String = "a-test-secret-that-is-comfortably-long-enough-for-hs256",
        accessTtl: Duration = Duration.ofMinutes(30),
    ) = JmailProperties(
        security = JmailProperties.SecurityProperties(
            jwtSecret = secret,
            accessTokenTtl = accessTtl,
            refreshTokenTtl = Duration.ofDays(30),
        ),
    )

    @BeforeEach
    fun setUp() {
        tokenService = TokenService(properties(), refreshTokenRepository)
    }

    @Nested
    @DisplayName("access tokens")
    inner class AccessTokens {

        @Test
        fun `round trip carries the identity the API needs`() {
            val token = tokenService.issueAccessToken(user)

            val verified = tokenService.verifyAccessToken(token)

            assertThat(verified.userId).isEqualTo(user.id)
            assertThat(verified.email).isEqualTo("ada@example.com")
            assertThat(verified.displayName).isEqualTo("Ada Lovelace")
        }

        @Test
        fun `rejects a token signed with a different secret`() {
            val foreign = TokenService(
                properties(secret = "a-completely-different-secret-of-sufficient-length"),
                refreshTokenRepository,
            )
            val token = foreign.issueAccessToken(user)

            assertThrows<UnauthorizedException> { tokenService.verifyAccessToken(token) }
        }

        @Test
        fun `rejects a tampered payload`() {
            val token = tokenService.issueAccessToken(user)
            val parts = token.split(".")
            // Flip the payload while keeping the original signature.
            val tampered = "${parts[0]}.${parts[1].dropLast(4)}AAAA.${parts[2]}"

            assertThrows<UnauthorizedException> { tokenService.verifyAccessToken(tampered) }
        }

        @Test
        fun `rejects an expired token`() {
            val shortLived = TokenService(
                properties(accessTtl = Duration.ofSeconds(-1)), // already expired when issued
                refreshTokenRepository,
            )
            val token = shortLived.issueAccessToken(user)

            assertThrows<UnauthorizedException> { shortLived.verifyAccessToken(token) }
        }

        @Test
        fun `rejects anything that is not a token at all`() {
            assertThrows<UnauthorizedException> { tokenService.verifyAccessToken("not-a-jwt") }
            assertThrows<UnauthorizedException> { tokenService.verifyAccessToken("") }
        }

        @Test
        fun `a short secret is refused at construction rather than weakening HS256`() {
            assertThrows<IllegalArgumentException> {
                TokenService(properties(secret = "too-short"), refreshTokenRepository)
            }
        }

        @Test
        fun `a blank secret falls back to an ephemeral key so development still runs`() {
            val ephemeral = TokenService(properties(secret = ""), refreshTokenRepository)

            val token = ephemeral.issueAccessToken(user)

            assertThat(ephemeral.verifyAccessToken(token).userId).isEqualTo(user.id)
        }
    }

    @Nested
    @DisplayName("refresh tokens")
    inner class RefreshTokens {

        @Test
        fun `stores only the hash, never the token itself`() {
            val saved = slot<RefreshToken>()
            every { refreshTokenRepository.save(capture(saved)) } answers { saved.captured }

            val raw = tokenService.issueRefreshToken(user.id)

            assertThat(saved.captured.tokenHash).isEqualTo(tokenService.hash(raw))
            assertThat(saved.captured.tokenHash).isNotEqualTo(raw)
            assertThat(saved.captured.tokenHash.length).isEqualTo(64) // SHA-256 hex
        }

        @Test
        fun `rotation revokes the presented token and links it to its replacement`() {
            val existing = RefreshToken(
                userId = user.id,
                tokenHash = tokenService.hash("original-token"),
                expiresAt = Instant.now().plus(Duration.ofDays(1)),
            )
            val replacement = RefreshToken(userId = user.id)

            every { refreshTokenRepository.findByTokenHash(tokenService.hash("original-token")) } returns existing
            every { refreshTokenRepository.save(any()) } returnsArgument 0
            every { refreshTokenRepository.save(match { it.tokenHash != existing.tokenHash }) } returns replacement

            val (userId, rotated) = tokenService.rotateRefreshToken("original-token")

            assertThat(userId).isEqualTo(user.id)
            assertThat(rotated).isNotEqualTo("original-token")
            assertThat(existing.revokedAt).isNotNull()
        }

        @Test
        fun `re-using a revoked token revokes every session, because it means the token leaked`() {
            val revoked = RefreshToken(
                userId = user.id,
                tokenHash = tokenService.hash("leaked-token"),
                expiresAt = Instant.now().plus(Duration.ofDays(1)),
                revokedAt = Instant.now().minusSeconds(60),
            )
            every { refreshTokenRepository.findByTokenHash(any()) } returns revoked

            assertThrows<UnauthorizedException> { tokenService.rotateRefreshToken("leaked-token") }

            verify { refreshTokenRepository.revokeAllForUser(user.id, any()) }
        }

        @Test
        fun `an expired refresh token is refused`() {
            every { refreshTokenRepository.findByTokenHash(any()) } returns RefreshToken(
                userId = user.id,
                tokenHash = tokenService.hash("old-token"),
                expiresAt = Instant.now().minusSeconds(1),
            )

            assertThrows<UnauthorizedException> { tokenService.rotateRefreshToken("old-token") }
        }

        @Test
        fun `an unknown refresh token is refused`() {
            every { refreshTokenRepository.findByTokenHash(any()) } returns null

            assertThrows<UnauthorizedException> { tokenService.rotateRefreshToken("never-issued") }
        }

        @Test
        fun `isUsable reflects revocation and expiry`() {
            val now = Instant.now()

            assertThat(RefreshToken(expiresAt = now.plusSeconds(60)).isUsable(now)).isTrue()
            assertThat(RefreshToken(expiresAt = now.minusSeconds(1)).isUsable(now)).isFalse()
            assertThat(
                RefreshToken(expiresAt = now.plusSeconds(60), revokedAt = now).isUsable(now),
            ).isFalse()
        }
    }

    @Test
    fun `issueTokens returns a usable pair with the configured lifetime`() {
        every { refreshTokenRepository.save(any()) } returnsArgument 0

        val pair = tokenService.issueTokens(user, userAgent = "JMail/1.0", clientIp = "127.0.0.1")

        assertThat(pair.expiresInSeconds).isEqualTo(1_800)
        assertThat(tokenService.verifyAccessToken(pair.accessToken).userId).isEqualTo(user.id)
        assertThat(pair.refreshToken.length).isGreaterThan(32)
    }

    @Test
    fun `the authenticated principal never puts the address in its string form`() {
        val principal = AuthenticatedUser(user.id, "ada@example.com", "Ada")

        assertThat(principal.toString().contains("ada@example.com")).isFalse()
    }

    @Test
    fun `hashing is stable and collision free for distinct inputs`() {
        assertThat(tokenService.hash("token-a")).isEqualTo(tokenService.hash("token-a"))
        assertThat(tokenService.hash("token-a")).isNotEqualTo(tokenService.hash("token-b"))
    }
}
