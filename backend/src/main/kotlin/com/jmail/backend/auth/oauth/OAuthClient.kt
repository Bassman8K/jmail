package com.jmail.backend.auth.oauth

import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant

/**
 * One OAuth 2.0 + OpenID Connect provider JMail can sign a user in with.
 *
 * Implementations differ only in how they identify the user and how the client is
 * authenticated at the token endpoint (a static secret for Google and Microsoft, a signed
 * assertion for Apple), so the authorization-code plumbing lives in [AbstractOidcClient].
 */
interface OAuthClient {

    val provider: AccountProvider

    /** False when the deployment has no credentials for this provider. */
    val isConfigured: Boolean

    fun buildAuthorizationUrl(session: AuthSession): String

    fun exchangeCode(code: String, session: AuthSession): OAuthTokens

    fun profileFrom(tokens: OAuthTokens): ProviderProfile

    fun refreshAccessToken(refreshToken: String): OAuthTokens
}

abstract class AbstractOidcClient(
    protected val properties: JmailProperties.OAuthProviderProperties,
    protected val restClient: RestClient,
) : OAuthClient {

    protected val log = LoggerFactory.getLogger(javaClass)!!

    override val isConfigured: Boolean
        get() = properties.isConfigured

    /** The redirect URI registered with the provider; must match on both legs of the flow. */
    abstract fun redirectUri(): String

    /** Extra authorization parameters a specific provider needs (prompt, access_type, …). */
    protected open fun extraAuthorizationParameters(): Map<String, String> = emptyMap()

    /** How the client authenticates at the token endpoint. */
    protected open fun clientSecret(): String = properties.clientSecret

    override fun buildAuthorizationUrl(session: AuthSession): String {
        require(isConfigured) { "${provider.displayName} sign-in is not configured" }

        val builder = UriComponentsBuilder.fromUriString(properties.authorizationUriResolved())
            .queryParam("client_id", properties.clientId)
            .queryParam("redirect_uri", redirectUri())
            .queryParam("response_type", "code")
            .queryParam("scope", properties.scopes.joinToString(" "))
            .queryParam("state", session.state)
            .queryParam("nonce", session.nonce)
            .queryParam("code_challenge", Pkce.challengeFor(session.codeVerifier))
            .queryParam("code_challenge_method", "S256")

        extraAuthorizationParameters().forEach { (name, value) -> builder.queryParam(name, value) }

        // encode() escapes the space-separated scope list and any provider-specific values;
        // build(true) would assert they are pre-encoded, which they are not.
        return builder.encode().build().toUriString()
    }

    override fun exchangeCode(code: String, session: AuthSession): OAuthTokens {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", redirectUri())
            add("client_id", properties.clientId)
            add("client_secret", clientSecret())
            add("code_verifier", session.codeVerifier)
        }
        return postForTokens(form)
    }

    override fun refreshAccessToken(refreshToken: String): OAuthTokens {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "refresh_token")
            add("refresh_token", refreshToken)
            add("client_id", properties.clientId)
            add("client_secret", clientSecret())
        }
        // Providers commonly omit refresh_token on a refresh response; the caller keeps the
        // existing one in that case rather than wiping a still-valid credential.
        return postForTokens(form)
    }

    private fun postForTokens(form: LinkedMultiValueMap<String, String>): OAuthTokens {
        val response = runCatching {
            restClient.post()
                .uri(properties.tokenUriResolved())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(TokenEndpointResponse::class.java)
        }.getOrElse { failure ->
            throw ProviderException(
                provider = provider.displayName,
                message = "Could not reach ${provider.displayName} to complete sign-in",
                cause = failure,
            )
        } ?: throw ProviderException(provider.displayName, "${provider.displayName} returned an empty token response")

        if (response.error != null) {
            log.warn("{} token endpoint returned {}: {}", provider, response.error, response.errorDescription)
            throw ProviderException(
                provider.displayName,
                "${provider.displayName} rejected the sign-in: ${response.errorDescription ?: response.error}",
            )
        }

        val accessToken = response.accessToken
            ?: throw ProviderException(provider.displayName, "${provider.displayName} returned no access token")

        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = response.refreshToken,
            idToken = response.idToken,
            expiresInSeconds = response.expiresIn ?: 3600,
            scope = response.scope,
        )
    }

    /**
     * Reads the claims of an ID token received directly from the token endpoint.
     *
     * The signature is not re-verified here: the token arrived over an authenticated TLS
     * connection to the provider's own endpoint in response to a request JMail made, which
     * OpenID Connect Core §3.1.3.7 explicitly allows. Issuer, audience and expiry *are*
     * checked, because those catch token substitution, which TLS does not.
     */
    protected fun claimsOf(idToken: String): JWTClaimsSet {
        val claims = runCatching { SignedJWT.parse(idToken).jwtClaimsSet }.getOrElse { failure ->
            throw ProviderException(provider.displayName, "${provider.displayName} returned an unreadable ID token", failure)
        }

        val expectedIssuer = properties.issuerResolved()
        val issuerMatches = expectedIssuer.isBlank() ||
            claims.issuer == expectedIssuer ||
            // Microsoft's multi-tenant issuer contains the resolved tenant GUID rather than
            // the literal "common" placeholder that was configured.
            (expectedIssuer.contains("common") && claims.issuer?.startsWith("https://login.microsoftonline.com/") == true)

        if (!issuerMatches) {
            throw ProviderException(provider.displayName, "ID token issuer did not match ${provider.displayName}")
        }
        if (properties.clientId !in claims.audience) {
            throw ProviderException(provider.displayName, "ID token was not issued for this application")
        }
        if (claims.expirationTime?.toInstant()?.isBefore(Instant.now()) != false) {
            throw ProviderException(provider.displayName, "ID token has already expired")
        }
        return claims
    }
}

/**
 * Token endpoint response. Field names follow RFC 6749; unknown fields are ignored so a
 * provider adding new ones never breaks sign-in.
 */
internal data class TokenEndpointResponse(
    @com.fasterxml.jackson.annotation.JsonProperty("access_token") val accessToken: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") val refreshToken: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("id_token") val idToken: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("token_type") val tokenType: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("expires_in") val expiresIn: Long? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("scope") val scope: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("error") val error: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("error_description") val errorDescription: String? = null,
)
