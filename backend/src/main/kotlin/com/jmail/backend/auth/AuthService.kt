package com.jmail.backend.auth

import com.jmail.backend.auth.dto.AuthTokensResponse
import com.jmail.backend.auth.dto.ProviderSummary
import com.jmail.backend.auth.dto.SignInKind
import com.jmail.backend.auth.dto.StartAuthorizationResponse
import com.jmail.backend.auth.dto.UserResponse
import com.jmail.backend.auth.oauth.AppleOAuthClient
import com.jmail.backend.auth.oauth.AuthSession
import com.jmail.backend.auth.oauth.ClientTarget
import com.jmail.backend.auth.oauth.OAuthClientRegistry
import com.jmail.backend.auth.oauth.Pkce
import com.jmail.backend.common.BadRequestException
import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.NotFoundException
import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.demo.DemoMailboxSeeder
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.AccountProvisioningService
import com.jmail.backend.user.UserAccount
import com.jmail.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

/**
 * Orchestrates every way into JMail.
 *
 * The OAuth flow deliberately never puts tokens in a redirect URL. The provider callback
 * lands on the server, which redirects the browser back to the client with a single-use
 * *handoff code*; the client then POSTs that code to exchange it for real tokens. Redirect
 * URLs end up in browser history, referrer headers and access logs — handoff codes are
 * worthless seconds later, access tokens are not.
 */
@Service
class AuthService(
    private val properties: JmailProperties,
    private val registry: OAuthClientRegistry,
    private val sessionStore: AuthSessionStore,
    private val tokenService: TokenService,
    private val provisioningService: AccountProvisioningService,
    private val demoMailboxSeeder: DemoMailboxSeeder,
    private val userRepository: UserRepository,
    private val appleOAuthClient: AppleOAuthClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Only the methods this deployment can actually complete, in the order the UI shows them. */
    fun availableProviders(): List<ProviderSummary> = buildList {
        listOf(AccountProvider.GOOGLE, AccountProvider.MICROSOFT, AccountProvider.APPLE)
            .filter(registry::isConfigured)
            .forEach { provider ->
                add(
                    ProviderSummary(
                        id = provider,
                        displayName = "Continue with ${provider.displayName}",
                        kind = SignInKind.OAUTH,
                        icon = provider.name.lowercase(),
                    ),
                )
            }

        if (demoMailboxSeeder.isEnabled) {
            add(
                ProviderSummary(
                    id = AccountProvider.DEMO,
                    displayName = "Explore the demo mailbox",
                    kind = SignInKind.DEMO,
                    icon = "demo",
                ),
            )
        }
    }

    fun startAuthorization(
        provider: AccountProvider,
        target: ClientTarget,
        linkToUserId: UUID? = null,
    ): StartAuthorizationResponse {
        val client = registry.clientFor(provider)

        val session = AuthSession(
            state = Pkce.generateState(),
            codeVerifier = Pkce.generateCodeVerifier(),
            nonce = Pkce.generateNonce(),
            provider = provider,
            target = target,
            redirectUri = client.let { properties.baseUrl },
            linkToUserId = linkToUserId,
        )
        sessionStore.rememberSession(session)

        return StartAuthorizationResponse(
            authorizationUrl = client.buildAuthorizationUrl(session),
            state = session.state,
            expiresInSeconds = AUTHORIZATION_WINDOW_SECONDS,
        )
    }

    /**
     * Completes the provider round-trip and returns the URL the browser should be sent to.
     *
     * @param appleUserPayload Apple's one-and-only delivery of the user's name, form-posted
     *   on first consent.
     */
    @Transactional
    fun completeAuthorization(
        provider: AccountProvider,
        code: String,
        state: String,
        appleUserPayload: String? = null,
    ): String {
        val session = sessionStore.consumeSession(state)
        if (session.provider != provider) {
            throw UnauthorizedException("This sign-in link does not match the provider", "provider_mismatch")
        }

        val client = registry.clientFor(provider)
        val tokens = client.exchangeCode(code, session)
        val profile = client.profileFrom(tokens)

        val displayNameOverride = if (provider == AccountProvider.APPLE) {
            appleOAuthClient.nameFromCallback(appleUserPayload)
        } else {
            null
        }

        val user = provisioningService.completeOAuthSignIn(
            provider = provider,
            profile = profile,
            tokens = tokens,
            linkToUserId = session.linkToUserId,
            displayNameOverride = displayNameOverride,
        )

        val handoff = sessionStore.createHandoff(user.id)
        return redirectUrlFor(session.target, handoff)
    }

    /** Reports a provider failure back to the client's UI rather than a blank browser page. */
    fun errorRedirectUrl(target: ClientTarget, error: String): String {
        val base = when (target) {
            ClientTarget.WEB -> properties.webOrigin
            ClientTarget.APP -> APP_CALLBACK_SCHEME
        }
        return UriComponentsBuilder.fromUriString(base)
            .queryParam("error", error)
            .encode()
            .build()
            .toUriString()
    }

    @Transactional
    fun exchangeHandoff(code: String, userAgent: String?, clientIp: String?): AuthTokensResponse {
        val userId = sessionStore.consumeHandoff(code)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("User", userId) }
        return issue(user, userAgent, clientIp)
    }

    @Transactional
    fun refresh(refreshToken: String, userAgent: String?, clientIp: String?): AuthTokensResponse {
        val (userId, rotated) = tokenService.rotateRefreshToken(refreshToken, userAgent, clientIp)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("User", userId) }

        return AuthTokensResponse(
            accessToken = tokenService.issueAccessToken(user),
            refreshToken = rotated,
            expiresIn = properties.security.accessTokenTtl.seconds,
            user = UserResponse.from(user, provisioningService.accountsOf(user.id)),
        )
    }

    @Transactional
    fun logout(refreshToken: String?, allSessions: Boolean, userId: UUID?) {
        when {
            allSessions && userId != null -> {
                val revoked = tokenService.revokeAllSessions(userId)
                log.info("Revoked {} sessions for user {}", revoked, userId)
            }
            refreshToken != null -> tokenService.revokeRefreshToken(refreshToken)
        }
    }

    /** One-click sign-in to the seeded mailbox. Refused unless explicitly enabled. */
    @Transactional
    fun signInAsDemoUser(userAgent: String?, clientIp: String?): AuthTokensResponse {
        if (!demoMailboxSeeder.isEnabled) {
            throw BadRequestException("demo_disabled", "Demo sign-in is not enabled on this server")
        }
        return issue(demoMailboxSeeder.provisionDemoUser(), userAgent, clientIp)
    }

    private fun issue(user: UserAccount, userAgent: String?, clientIp: String?): AuthTokensResponse {
        val tokens = tokenService.issueTokens(user, userAgent, clientIp)
        return AuthTokensResponse(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresIn = tokens.expiresInSeconds,
            user = UserResponse.from(user, provisioningService.accountsOf(user.id)),
        )
    }

    private fun redirectUrlFor(target: ClientTarget, handoffCode: String): String {
        val base = when (target) {
            // The app's own origin with the code as a query parameter, rather than a
            // dedicated /auth/callback path: the browser build is a single page served by
            // whatever static server is in front of it, and not every one of them rewrites
            // unknown paths back to index.html. The root always resolves.
            ClientTarget.WEB -> properties.webOrigin
            // Registered by the desktop bundle (CFBundleURLTypes) and the Android manifest.
            ClientTarget.APP -> APP_CALLBACK_SCHEME
        }
        return UriComponentsBuilder.fromUriString(base)
            .queryParam("code", handoffCode)
            .encode()
            .build()
            .toUriString()
    }

    private companion object {
        const val AUTHORIZATION_WINDOW_SECONDS = 600L
        const val APP_CALLBACK_SCHEME = "jmail://auth/callback"
    }
}
