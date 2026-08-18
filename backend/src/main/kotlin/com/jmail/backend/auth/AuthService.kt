package com.jmail.backend.auth

import com.jmail.backend.auth.dto.AuthTokensResponse
import com.jmail.backend.auth.dto.ExchangeSignInRequest
import com.jmail.backend.auth.dto.ExchangeSuggestionResponse
import com.jmail.backend.auth.dto.MailProviderResponse
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
    private val exchangeAuthenticator: ExchangeAuthenticator,
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

        if (properties.exchange.enabled) {
            add(
                ProviderSummary(
                    id = AccountProvider.EXCHANGE,
                    displayName = "Microsoft Exchange or IMAP",
                    kind = SignInKind.CREDENTIALS,
                    icon = "exchange",
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

    /**
     * Signs in against an on-premises Exchange or generic IMAP server.
     *
     * Credentials are proven against the real server before anything is written, so a typo
     * produces an error rather than a permanently broken account.
     */
    @Transactional
    fun signInWithExchange(
        request: ExchangeSignInRequest,
        userAgent: String?,
        clientIp: String?,
        linkToUserId: UUID? = null,
    ): AuthTokensResponse {
        if (!properties.exchange.enabled) {
            throw BadRequestException("provider_not_configured", "Exchange sign-in is disabled on this server")
        }

        val email = EmailAddresses.canonical(request.email)
        val suggestion = exchangeAuthenticator.suggestSettings(email)

        val credentials = ExchangeCredentials(
            email = email,
            password = request.password,
            imapHost = request.imapHost?.takeIf { it.isNotBlank() } ?: suggestion.imapHost,
            imapPort = request.imapPort ?: suggestion.imapPort,
            smtpHost = request.smtpHost?.takeIf { it.isNotBlank() } ?: suggestion.smtpHost,
            smtpPort = request.smtpPort ?: suggestion.smtpPort,
            useTls = request.useTls,
            displayName = request.displayName,
        )

        if (credentials.imapHost.isBlank()) {
            throw BadRequestException(
                "imap_host_required",
                "Enter the mail server for this address — we could not work it out automatically",
                mapOf("field" to "imapHost"),
            )
        }

        verifyWithGuidance(credentials, suggestion.provider)

        // Exchange Online and on-premises Exchange are labelled as such; everything else is
        // an IMAP connection, whoever hosts it.
        val provider = if (suggestion.provider?.id in EXCHANGE_PROVIDER_IDS) {
            AccountProvider.EXCHANGE
        } else {
            AccountProvider.IMAP
        }
        val user = provisioningService.completeCredentialSignIn(provider, credentials, linkToUserId)

        return issue(user, userAgent, clientIp)
    }

    /**
     * Verifies credentials, and turns the provider's flat "authentication failed" into
     * something the user can act on.
     *
     * Gmail, iCloud, Yahoo and the rest all reject an account password over IMAP once
     * two-factor authentication is on, and they all report it identically. Without this, the
     * user sees "wrong password", checks their password, finds it correct, and concludes the
     * app is broken — which is the single most common way an IMAP sign-in is abandoned.
     */
    private fun verifyWithGuidance(
        credentials: ExchangeCredentials,
        provider: KnownMailProvider?,
    ) {
        try {
            exchangeAuthenticator.verify(credentials)
        } catch (failure: UnauthorizedException) {
            if (failure.code == "exchange_authentication_failed" && provider?.requiresAppPassword == true) {
                throw UnauthorizedException(
                    "${provider.displayName} does not accept your normal password here. " +
                        "Create an app password and use that instead.",
                    "app_password_required",
                )
            }
            throw failure
        }
    }

    fun suggestExchangeSettings(email: String): ExchangeSuggestionResponse {
        val suggestion = exchangeAuthenticator.suggestSettings(EmailAddresses.canonical(email))
        val provider = suggestion.provider

        return ExchangeSuggestionResponse(
            imapHost = suggestion.imapHost,
            imapPort = suggestion.imapPort,
            smtpHost = suggestion.smtpHost,
            smtpPort = suggestion.smtpPort,
            useTls = suggestion.useTls,
            confident = suggestion.confident,
            providerId = provider?.id,
            providerName = provider?.displayName,
            requiresAppPassword = provider?.requiresAppPassword == true,
            appPasswordUrl = provider?.appPasswordUrl,
            helpText = provider?.helpText,
        )
    }

    /** Every mail service the address-and-password sign-in knows how to reach. */
    fun knownMailProviders(): List<MailProviderResponse> =
        exchangeAuthenticator.knownProviders().map { provider ->
            MailProviderResponse(
                id = provider.id,
                displayName = provider.displayName,
                imapHost = provider.imapHost,
                imapPort = provider.imapPort,
                smtpHost = provider.smtpHost,
                smtpPort = provider.smtpPort,
                useTls = provider.useTls,
                requiresAppPassword = provider.requiresAppPassword,
                appPasswordUrl = provider.appPasswordUrl,
                helpText = provider.helpText,
                requiresManualServer = provider.imapHost.isBlank(),
            )
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

        /** Services that are Exchange, and are labelled that way on the account. */
        val EXCHANGE_PROVIDER_IDS = setOf("office365", "exchange")
    }
}
