package com.jmail.backend.auth.oauth

import com.jmail.backend.user.AccountProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Tokens as returned by a provider's token endpoint. */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresInSeconds: Long = 3600,
    val scope: String? = null,
) {
    fun expiresAt(now: Instant = Instant.now()): Instant = now.plusSeconds(expiresInSeconds)
}

/** The identity a provider asserts about the person who just signed in. */
data class ProviderProfile(
    /** The provider's stable identifier for this account (OIDC `sub`, Graph object id). */
    val providerAccountId: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

data class OAuthSignInResult(
    val tokens: OAuthTokens,
    val profile: ProviderProfile,
)

/** Where the user should end up once the browser round-trip completes. */
enum class ClientTarget {
    /** Browser build: redirect back to the web origin. */
    WEB,

    /** Desktop/mobile build: redirect to the `jmail://` deep link the app registered. */
    APP,
    ;

    companion object {
        fun parse(value: String?): ClientTarget =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WEB
    }
}

/**
 * One in-flight authorization attempt.
 *
 * Holds the PKCE verifier and nonce between the redirect out to the provider and the
 * callback coming back, which is what binds the two halves of the flow together and stops
 * an attacker injecting their own authorization code.
 */
data class AuthSession(
    val state: String,
    val codeVerifier: String,
    val nonce: String,
    val provider: AccountProvider,
    val target: ClientTarget,
    val redirectUri: String,
    val createdAt: Instant = Instant.now(),
    /** Set when an existing user is adding another mailbox rather than signing in. */
    val linkToUserId: java.util.UUID? = null,
)

/**
 * PKCE (RFC 7636). JMail is a public client on desktop, mobile and web, so the
 * authorization code is always bound to a one-time verifier that never leaves the server.
 */
object Pkce {

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A high-entropy verifier: 43–128 characters of unreserved ASCII per the RFC. */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64).also(random::nextBytes)
        return encoder.encodeToString(bytes)
    }

    /** S256 challenge: BASE64URL(SHA256(verifier)). */
    fun challengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }

    fun generateState(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    fun generateNonce(): String = encoder.encodeToString(ByteArray(24).also(random::nextBytes))
}
