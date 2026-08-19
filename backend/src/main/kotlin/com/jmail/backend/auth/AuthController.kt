package com.jmail.backend.auth

import com.jmail.backend.auth.dto.AuthTokensResponse
import com.jmail.backend.auth.dto.HandoffExchangeRequest
import com.jmail.backend.auth.dto.LogoutRequest
import com.jmail.backend.auth.dto.ProviderSummary
import com.jmail.backend.auth.dto.RefreshTokenRequest
import com.jmail.backend.auth.dto.StartAuthorizationResponse
import com.jmail.backend.auth.oauth.ClientTarget
import com.jmail.backend.auth.oauth.OAuthClientRegistry
import com.jmail.backend.common.ApiException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Every entry point into JMail.
 *
 * The OAuth flow is three legs:
 *  1. `POST /{provider}/start` — the client gets an authorization URL and opens it.
 *  2. `GET|POST /{provider}/callback` — the provider redirects here; JMail redirects the
 *     browser onward to the client with a single-use handoff code.
 *  3. `POST /exchange` — the client swaps that code for real tokens.
 *
 * Tokens never travel in a redirect URL, where they would persist in browser history and
 * proxy logs.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
@Tag(name = "Authentication", description = "Sign in with Google, Microsoft or Apple")
@SecurityRequirements // this whole controller is reachable without a token
class AuthController(
    private val authService: AuthService,
    private val registry: OAuthClientRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/providers")
    @Operation(
        summary = "List the sign-in methods this server can complete",
        description = "Providers without credentials configured are omitted, so the client " +
            "only ever renders buttons that work.",
    )
    fun providers(): List<ProviderSummary> = authService.availableProviders()

    @PostMapping("/{provider}/start")
    @Operation(summary = "Begin an OAuth sign-in and get the URL to open")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Authorization URL created"),
        ApiResponse(responseCode = "400", description = "Unknown or unconfigured provider", content = []),
    )
    fun startAuthorization(
        @PathVariable provider: String,
        @RequestParam(defaultValue = "WEB") target: String,
        @AuthenticationPrincipal currentUser: AuthenticatedUser?,
    ): StartAuthorizationResponse = authService.startAuthorization(
        provider = registry.parseProvider(provider),
        target = ClientTarget.parse(target),
        // A signed-in caller is adding a second mailbox rather than starting a new account.
        linkToUserId = currentUser?.userId,
    )

    @GetMapping("/{provider}/callback")
    @Operation(
        summary = "OAuth redirect target",
        description = "Called by the provider, not by the client. Always answers with a 302 " +
            "back to the app so the user never sees a bare error page.",
    )
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false, name = "error_description") errorDescription: String?,
        @RequestParam(required = false) target: String?,
    ): ResponseEntity<Void> = handleCallback(
        provider = provider,
        code = code,
        state = state,
        error = error,
        errorDescription = errorDescription,
        target = target,
        appleUserPayload = null,
    )

    @PostMapping("/{provider}/callback")
    @Operation(
        summary = "OAuth redirect target for form_post responses",
        description = "Apple form-posts its callback whenever name or email scopes are " +
            "requested, and delivers the user's name here on first consent only.",
    )
    fun callbackFormPost(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false, name = "error_description") errorDescription: String?,
        @RequestParam(required = false) target: String?,
        @RequestParam(required = false, name = "user") user: String?,
    ): ResponseEntity<Void> = handleCallback(
        provider = provider,
        code = code,
        state = state,
        error = error,
        errorDescription = errorDescription,
        target = target,
        appleUserPayload = user,
    )

    private fun handleCallback(
        provider: String,
        code: String?,
        state: String?,
        error: String?,
        errorDescription: String?,
        target: String?,
        appleUserPayload: String?,
    ): ResponseEntity<Void> {
        val clientTarget = ClientTarget.parse(target)

        if (error != null) {
            log.info("Provider {} reported '{}' during sign-in: {}", provider, error, errorDescription)
            return redirect(authService.errorRedirectUrl(clientTarget, error))
        }
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            return redirect(authService.errorRedirectUrl(clientTarget, "missing_code"))
        }

        return try {
            redirect(
                authService.completeAuthorization(
                    provider = registry.parseProvider(provider),
                    code = code,
                    state = state,
                    appleUserPayload = appleUserPayload,
                ),
            )
        } catch (failure: ApiException) {
            // The user is mid-flow in a browser: send them back to the app with a code it can
            // render properly, rather than returning JSON they would see as raw text.
            log.warn("Sign-in with {} failed: {}", provider, failure.message)
            redirect(authService.errorRedirectUrl(clientTarget, failure.code))
        }
    }

    @PostMapping("/exchange")
    @Operation(summary = "Exchange a single-use handoff code for access and refresh tokens")
    fun exchangeHandoff(
        @Valid @RequestBody request: HandoffExchangeRequest,
        httpRequest: HttpServletRequest,
    ): AuthTokensResponse = authService.exchangeHandoff(
        code = request.code,
        userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT),
        clientIp = clientAddress(httpRequest),
    )

    @PostMapping("/refresh")
    @Operation(
        summary = "Exchange a refresh token for a new session",
        description = "Refresh tokens rotate on every use: the response contains a new one " +
            "and the presented token is immediately revoked. Store the newest one only.",
    )
    fun refresh(
        @Valid @RequestBody request: RefreshTokenRequest,
        httpRequest: HttpServletRequest,
    ): AuthTokensResponse = authService.refresh(
        refreshToken = request.refreshToken,
        userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT),
        clientIp = clientAddress(httpRequest),
    )

    @PostMapping("/logout")
    @Operation(summary = "Revoke this session, or every session for the signed-in user")
    fun logout(
        @RequestBody(required = false) request: LogoutRequest?,
        @AuthenticationPrincipal currentUser: AuthenticatedUser?,
    ): ResponseEntity<Void> {
        authService.logout(
            refreshToken = request?.refreshToken,
            allSessions = request?.allSessions == true,
            userId = currentUser?.userId,
        )
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/demo")
    @Operation(
        summary = "Sign in to the seeded demo mailbox",
        description = "Available only when jmail.demo.enabled is true. Exists so the app is " +
            "fully usable on a clean checkout with no provider credentials.",
    )
    fun signInAsDemo(httpRequest: HttpServletRequest): AuthTokensResponse =
        authService.signInAsDemoUser(
            userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT),
            clientIp = clientAddress(httpRequest),
        )

    private fun redirect(url: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build()

    private fun clientAddress(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
}
