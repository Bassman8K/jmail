package com.jmail.backend.auth

import com.jmail.backend.auth.oauth.AuthSession
import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived server state for sign-in: the PKCE session between redirect and callback, and
 * the single-use handoff code the client redeems for real tokens.
 *
 * Both live in memory on purpose. They expire in minutes, and losing them on a restart
 * costs a user one retry of a flow they are actively performing — much cheaper than the
 * write amplification of persisting them. A multi-instance deployment behind a load
 * balancer needs sticky sessions for the callback leg, or this swapped for Redis; that
 * trade is documented in docs/ARCHITECTURE.md.
 */
@Component
class AuthSessionStore(private val properties: JmailProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    private val sessions = ConcurrentHashMap<String, AuthSession>()
    private val handoffs = ConcurrentHashMap<String, Handoff>()

    /** How long a user has to complete the provider's consent screen. */
    private val sessionTtl: Duration = Duration.ofMinutes(10)

    fun rememberSession(session: AuthSession) {
        sessions[session.state] = session
    }

    /**
     * Consumes the session bound to [state]. Single use: a replayed callback finds nothing,
     * which is what stops an intercepted authorization code being redeemed twice.
     */
    fun consumeSession(state: String): AuthSession {
        val session = sessions.remove(state)
            ?: throw UnauthorizedException(
                "This sign-in link has expired or was already used. Start again.",
                "invalid_state",
            )

        if (session.createdAt.plus(sessionTtl).isBefore(Instant.now())) {
            throw UnauthorizedException("This sign-in attempt timed out. Start again.", "expired_state")
        }
        return session
    }

    /**
     * Issues the code the browser is redirected back with. Tokens are never placed in a
     * redirect URL, where they would land in browser history and server access logs.
     */
    fun createHandoff(userId: UUID): String {
        val code = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))

        handoffs[code] = Handoff(userId, Instant.now().plus(properties.security.handoffTtl))
        return code
    }

    fun consumeHandoff(code: String): UUID {
        val handoff = handoffs.remove(code)
            ?: throw UnauthorizedException("That sign-in code is not valid", "invalid_handoff_code")

        if (handoff.expiresAt.isBefore(Instant.now())) {
            throw UnauthorizedException("That sign-in code has expired", "expired_handoff_code")
        }
        return handoff.userId
    }

    /** Bounded memory: sweep anything that can no longer be redeemed. */
    @Scheduled(fixedDelay = 60_000)
    fun evictExpired() {
        val now = Instant.now()
        val staleSessions = sessions.entries.removeIf { it.value.createdAt.plus(sessionTtl).isBefore(now) }
        val staleHandoffs = handoffs.entries.removeIf { it.value.expiresAt.isBefore(now) }

        if (staleSessions || staleHandoffs) {
            log.debug("Evicted expired auth state; {} sessions and {} handoffs remain", sessions.size, handoffs.size)
        }
    }

    internal fun pendingSessionCount(): Int = sessions.size

    internal fun pendingHandoffCount(): Int = handoffs.size

    private data class Handoff(val userId: UUID, val expiresAt: Instant)
}
