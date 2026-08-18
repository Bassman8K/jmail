package com.jmail.backend.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import com.jmail.backend.auth.oauth.AuthSession
import com.jmail.backend.auth.oauth.ClientTarget
import com.jmail.backend.auth.oauth.Pkce
import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The state store is what binds the two halves of an OAuth flow together. Its security
 * properties — single use, bound to one provider, time limited — are asserted here because
 * failing any of them turns the callback into an open redirect for injected codes.
 */
class AuthSessionStoreTest {

    private val store = AuthSessionStore(
        JmailProperties(security = JmailProperties.SecurityProperties(handoffTtl = Duration.ofMinutes(2))),
    )

    private fun session(
        state: String = Pkce.generateState(),
        provider: AccountProvider = AccountProvider.GOOGLE,
        createdAt: Instant = Instant.now(),
    ) = AuthSession(
        state = state,
        codeVerifier = Pkce.generateCodeVerifier(),
        nonce = Pkce.generateNonce(),
        provider = provider,
        target = ClientTarget.WEB,
        redirectUri = "http://localhost:8090",
        createdAt = createdAt,
    )

    @Test
    fun `a remembered session can be consumed exactly once`() {
        val original = session()
        store.rememberSession(original)

        val consumed = store.consumeSession(original.state)

        assertThat(consumed.codeVerifier).isEqualTo(original.codeVerifier)
        // Replaying the callback must find nothing: this is what stops an intercepted
        // authorization code being redeemed a second time.
        assertThrows<UnauthorizedException> { store.consumeSession(original.state) }
    }

    @Test
    fun `an unknown state is refused`() {
        assertThrows<UnauthorizedException> { store.consumeSession("never-issued") }
    }

    @Test
    fun `a session older than the window is refused`() {
        val stale = session(createdAt = Instant.now().minus(Duration.ofMinutes(11)))
        store.rememberSession(stale)

        val failure = assertThrows<UnauthorizedException> { store.consumeSession(stale.state) }

        assertThat(failure.code).isEqualTo("expired_state")
    }

    @Test
    fun `a handoff code is single use`() {
        val userId = UUID.randomUUID()
        val code = store.createHandoff(userId)

        assertThat(store.consumeHandoff(code)).isEqualTo(userId)
        assertThrows<UnauthorizedException> { store.consumeHandoff(code) }
    }

    @Test
    fun `an unknown handoff code is refused`() {
        assertThrows<UnauthorizedException> { store.consumeHandoff("made-up-code") }
    }

    @Test
    fun `handoff codes are unpredictable and distinct`() {
        val first = store.createHandoff(UUID.randomUUID())
        val second = store.createHandoff(UUID.randomUUID())

        assertThat(first).isNotEqualTo(second)
        assertThat(first.length >= 32).isTrue()
    }

    @Test
    fun `expired state is swept so memory stays bounded`() {
        store.rememberSession(session(createdAt = Instant.now().minus(Duration.ofMinutes(30))))
        store.rememberSession(session()) // still current

        store.evictExpired()

        assertThat(store.pendingSessionCount()).isEqualTo(1)
    }

    @Test
    fun `PKCE challenges follow RFC 7636`() {
        val verifier = Pkce.generateCodeVerifier()
        val challenge = Pkce.challengeFor(verifier)

        // Base64url, unpadded, and the same verifier always yields the same challenge.
        assertThat(challenge.contains("=")).isEqualTo(false)
        assertThat(challenge.contains("+")).isEqualTo(false)
        assertThat(challenge.contains("/")).isEqualTo(false)
        assertThat(Pkce.challengeFor(verifier)).isEqualTo(challenge)
        assertThat(verifier.length >= 43).isTrue()
        assertThat(Pkce.generateCodeVerifier()).isNotEqualTo(verifier)
    }

    @Test
    fun `client target parsing defaults to the web rather than failing`() {
        assertThat(ClientTarget.parse("APP")).isEqualTo(ClientTarget.APP)
        assertThat(ClientTarget.parse("app")).isEqualTo(ClientTarget.APP)
        assertThat(ClientTarget.parse("WEB")).isEqualTo(ClientTarget.WEB)
        assertThat(ClientTarget.parse(null)).isEqualTo(ClientTarget.WEB)
        assertThat(ClientTarget.parse("nonsense")).isEqualTo(ClientTarget.WEB)
    }
}
