package com.jmail.backend.auth

import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.UserAccount
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** The identity carried by an authenticated request. */
data class AuthenticatedUser(
    val userId: UUID,
    val email: String,
    val displayName: String,
) {
    override fun toString(): String = "AuthenticatedUser(userId=$userId)" // never log the address
}

/** A freshly minted credential pair handed to a client. */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val issuedAt: Instant,
)

/**
 * Issues and verifies JMail's own session tokens.
 *
 * Access tokens are short-lived, stateless HS256 JWTs. Refresh tokens are opaque random
 * strings stored as SHA-256 hashes and rotated on every use, so a stolen refresh token is
 * usable at most once and its re-use is detectable.
 */
@Service
class TokenService(
    private val properties: JmailProperties,
    private val refreshTokenRepository: RefreshTokenRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val secret: ByteArray = resolveSecret(properties.security.jwtSecret)

    fun issueTokens(
        user: UserAccount,
        userAgent: String? = null,
        clientIp: String? = null,
    ): TokenPair {
        val now = Instant.now()
        val accessToken = issueAccessToken(user, now)
        val refreshToken = issueRefreshToken(user.id, now, userAgent, clientIp)

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = properties.security.accessTokenTtl.seconds,
            issuedAt = now,
        )
    }

    fun issueAccessToken(user: UserAccount, now: Instant = Instant.now()): String {
        val claims = JWTClaimsSet.Builder()
            .subject(user.id.toString())
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(properties.security.accessTokenTtl)))
            .jwtID(UUID.randomUUID().toString())
            .claim("email", user.email)
            .claim("name", user.displayName)
            .build()

        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(MACSigner(secret))
        return jwt.serialize()
    }

    /**
     * Verifies signature, issuer, audience and expiry. Returns the identity, or throws
     * [UnauthorizedException] — the caller never distinguishes *why* a token was rejected,
     * which keeps token-probing uninformative.
     */
    fun verifyAccessToken(token: String): AuthenticatedUser {
        val claims = runCatching {
            val jwt = SignedJWT.parse(token)
            require(jwt.verify(MACVerifier(secret))) { "signature mismatch" }

            val claimsSet = jwt.jwtClaimsSet
            require(claimsSet.issuer == ISSUER) { "unexpected issuer" }
            require(claimsSet.audience.contains(AUDIENCE)) { "unexpected audience" }
            require(claimsSet.expirationTime?.toInstant()?.isAfter(Instant.now()) == true) { "expired" }
            claimsSet
        }.getOrElse { failure ->
            log.debug("Rejected access token: {}", failure.message)
            throw UnauthorizedException("Your session has expired. Sign in again.", "invalid_token")
        }

        return AuthenticatedUser(
            userId = UUID.fromString(claims.subject),
            email = claims.getStringClaim("email").orEmpty(),
            displayName = claims.getStringClaim("name").orEmpty(),
        )
    }

    @Transactional
    fun issueRefreshToken(
        userId: UUID,
        now: Instant = Instant.now(),
        userAgent: String? = null,
        clientIp: String? = null,
    ): String {
        val raw = randomToken()
        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                tokenHash = hash(raw),
                issuedAt = now,
                expiresAt = now.plus(properties.security.refreshTokenTtl),
                userAgent = userAgent?.take(500),
                clientIp = clientIp?.take(64),
            ),
        )
        return raw
    }

    /**
     * Rotates a refresh token: the presented token is revoked and a new one issued.
     *
     * Presenting an already-revoked token means it leaked (the legitimate client has since
     * rotated), so every session for that user is revoked rather than just failing the call.
     */
    @Transactional
    fun rotateRefreshToken(
        rawToken: String,
        userAgent: String? = null,
        clientIp: String? = null,
    ): Pair<UUID, String> {
        val now = Instant.now()
        val stored = refreshTokenRepository.findByTokenHash(hash(rawToken))
            ?: throw UnauthorizedException("That session is no longer valid", "invalid_refresh_token")

        if (stored.revokedAt != null) {
            log.warn("Refresh token re-use detected for user {}; revoking all sessions", stored.userId)
            refreshTokenRepository.revokeAllForUser(stored.userId, now)
            throw UnauthorizedException("That session is no longer valid", "invalid_refresh_token")
        }

        if (!stored.isUsable(now)) {
            throw UnauthorizedException("That session has expired", "invalid_refresh_token")
        }

        val replacement = randomToken()
        val saved = refreshTokenRepository.save(
            RefreshToken(
                userId = stored.userId,
                tokenHash = hash(replacement),
                issuedAt = now,
                expiresAt = now.plus(properties.security.refreshTokenTtl),
                userAgent = userAgent?.take(500),
                clientIp = clientIp?.take(64),
            ),
        )

        stored.revokedAt = now
        stored.replacedBy = saved.id
        refreshTokenRepository.save(stored)

        return stored.userId to replacement
    }

    @Transactional
    fun revokeRefreshToken(rawToken: String) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))?.let { token ->
            token.revokedAt = Instant.now()
            refreshTokenRepository.save(token)
        }
    }

    @Transactional
    fun revokeAllSessions(userId: UUID): Int = refreshTokenRepository.revokeAllForUser(userId, Instant.now())

    /** SHA-256 hex. Refresh tokens are high-entropy random values, so no salt is needed. */
    internal fun hash(rawToken: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun randomToken(): String {
        val bytes = ByteArray(48).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun resolveSecret(configured: String): ByteArray {
        if (configured.isBlank()) {
            log.warn(
                "jmail.security.jwt-secret is not set. Generating an ephemeral signing key: " +
                    "everyone is signed out when the server restarts. Set JMAIL_JWT_SECRET in " +
                    "anything other than local development.",
            )
            return ByteArray(64).also(SecureRandom()::nextBytes)
        }

        val bytes = configured.toByteArray()
        require(bytes.size >= MIN_SECRET_BYTES) {
            "jmail.security.jwt-secret must be at least $MIN_SECRET_BYTES bytes for HS256 " +
                "(was ${bytes.size})"
        }
        return bytes
    }

    private companion object {
        const val ISSUER = "https://jmail.app"
        const val AUDIENCE = "jmail-client"
        const val MIN_SECRET_BYTES = 32
    }
}
