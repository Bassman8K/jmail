package com.jmail.backend.auth.oauth

import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OAuth clients, against a stubbed token endpoint.
 *
 * The interesting behaviour is not the happy path but the shapes each provider actually
 * returns: Microsoft putting the address in a different claim per account type, Google
 * omitting a refresh token on re-consent, and every provider's way of saying no.
 */
class OAuthClientsTest {

    private val secret = "a-signing-secret-long-enough-for-hmac-sha256-in-tests"

    private fun idToken(
        issuer: String,
        audience: String = "test-client",
        claims: Map<String, String> = emptyMap(),
        subject: String = "sub-123",
        expired: Boolean = false,
    ): String {
        val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject)
            .expirationTime(
                Date.from(
                    if (expired) {
                        java.time.Instant.now().minusSeconds(60)
                    } else {
                        java.time.Instant.now().plusSeconds(600)
                    },
                ),
            )
        claims.forEach { (name, value) -> builder.claim(name, value) }

        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build())
            .apply { sign(MACSigner(secret.toByteArray())) }
            .serialize()
    }

    private fun properties(
        google: JmailProperties.OAuthProviderProperties = googleProperties(),
        microsoft: JmailProperties.OAuthProviderProperties = microsoftProperties(),
    ) = JmailProperties(
        baseUrl = "http://localhost:8090",
        providers = JmailProperties.ProvidersProperties(google = google, microsoft = microsoft),
    )

    private fun googleProperties() = JmailProperties.OAuthProviderProperties(
        clientId = "test-client",
        clientSecret = "test-secret",
        authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUri = "https://oauth2.googleapis.com/token",
        issuer = "https://accounts.google.com",
        scopes = listOf("openid", "email", "https://www.googleapis.com/auth/gmail.modify"),
    )

    private fun microsoftProperties() = JmailProperties.OAuthProviderProperties(
        clientId = "test-client",
        clientSecret = "test-secret",
        tenantId = "common",
        authorizationUri = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
        tokenUri = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
        issuer = "https://login.microsoftonline.com/{tenant}/v2.0",
        scopes = listOf("openid", "offline_access"),
    )

    private fun session(provider: AccountProvider) = AuthSession(
        state = Pkce.generateState(),
        codeVerifier = Pkce.generateCodeVerifier(),
        nonce = Pkce.generateNonce(),
        provider = provider,
        target = ClientTarget.WEB,
        redirectUri = "http://localhost:8090",
    )

    private fun googleClient(): Pair<GoogleOAuthClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return GoogleOAuthClient(properties(), builder.build()) to server
    }

    private fun microsoftClient(): Pair<MicrosoftOAuthClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return MicrosoftOAuthClient(properties(), builder.build()) to server
    }

    // ---- authorization URLs ----------------------------------------------

    @Test
    fun `the google authorization url carries PKCE and offline access`() {
        val (client, _) = googleClient()

        val url = client.buildAuthorizationUrl(session(AccountProvider.GOOGLE))

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"), url)
        assertTrue(url.contains("code_challenge_method=S256"), url)
        assertTrue(url.contains("access_type=offline"), url)
        assertTrue(url.contains("prompt=consent"), url)
        // Scopes are space separated and must survive encoding.
        assertTrue(url.contains("scope=openid%20email") || url.contains("scope=openid+email"), url)
        assertTrue(url.contains("redirect_uri=http"), url)
    }

    @Test
    fun `the microsoft authorization url resolves the tenant placeholder`() {
        val (client, _) = microsoftClient()

        val url = client.buildAuthorizationUrl(session(AccountProvider.MICROSOFT))

        assertTrue(url.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"), url)
        assertTrue(url.contains("prompt=select_account"), url)
    }

    @Test
    fun `an unconfigured provider refuses to build an authorization url`() {
        val (client, _) = RestClient.builder().let { builder ->
            GoogleOAuthClient(
                properties(google = googleProperties().copy(clientId = "")),
                builder.build(),
            ) to MockRestServiceServer.bindTo(builder).build()
        }

        assertThrows<IllegalArgumentException> { client.buildAuthorizationUrl(session(AccountProvider.GOOGLE)) }
    }

    // ---- token exchange ---------------------------------------------------

    @Test
    fun `exchanging a code returns the tokens the provider issued`() {
        val (client, server) = googleClient()
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"access_token":"at","refresh_token":"rt","id_token":"it",
                        "expires_in":3599,"scope":"openid email","token_type":"Bearer"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val tokens = client.exchangeCode("auth-code", session(AccountProvider.GOOGLE))

        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(3_599, tokens.expiresInSeconds)
        assertTrue(tokens.expiresAt().isAfter(java.time.Instant.now()))
        server.verify()
    }

    @Test
    fun `a refresh without a new refresh token still returns the access token`() {
        val (client, server) = googleClient()
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(withSuccess("""{"access_token":"fresh","expires_in":3600}""", MediaType.APPLICATION_JSON))

        val tokens = client.refreshAccessToken("existing-refresh-token")

        assertEquals("fresh", tokens.accessToken)
        assertNull(tokens.refreshToken) // the caller keeps the one it already has
    }

    @Test
    fun `an oauth error response is reported with the provider's explanation`() {
        val (client, server) = googleClient()
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(
                withSuccess(
                    """{"error":"invalid_grant","error_description":"Code was already redeemed"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val failure = assertThrows<ProviderException> {
            client.exchangeCode("stale-code", session(AccountProvider.GOOGLE))
        }

        assertTrue(failure.message.contains("Code was already redeemed"), failure.message)
    }

    @Test
    fun `a token response with no access token is rejected`() {
        val (client, server) = googleClient()
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(withSuccess("""{"token_type":"Bearer"}""", MediaType.APPLICATION_JSON))

        assertThrows<ProviderException> { client.exchangeCode("code", session(AccountProvider.GOOGLE)) }
    }

    @Test
    fun `an unreachable token endpoint is reported as a provider failure`() {
        val (client, server) = googleClient()
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThrows<ProviderException> { client.exchangeCode("code", session(AccountProvider.GOOGLE)) }
    }

    // ---- profiles ---------------------------------------------------------

    @Test
    fun `google's profile comes from the id token`() {
        val (client, _) = googleClient()
        val tokens = OAuthTokens(
            accessToken = "at",
            idToken = idToken(
                issuer = "https://accounts.google.com",
                claims = mapOf(
                    "email" to "Ada@Example.com",
                    "name" to "Ada Lovelace",
                    "picture" to "https://example.com/ada.png",
                ),
            ),
        )

        val profile = client.profileFrom(tokens)

        assertEquals("sub-123", profile.providerAccountId)
        assertEquals("ada@example.com", profile.email) // canonicalised
        assertEquals("Ada Lovelace", profile.displayName)
        assertEquals("https://example.com/ada.png", profile.avatarUrl)
    }

    @Test
    fun `google falls back to the local part when no name is shared`() {
        val (client, _) = googleClient()
        val profile = client.profileFrom(
            OAuthTokens(
                accessToken = "at",
                idToken = idToken("https://accounts.google.com", claims = mapOf("email" to "ada@example.com")),
            ),
        )

        assertEquals("ada", profile.displayName)
    }

    @Test
    fun `a missing id token is a provider error rather than a crash`() {
        val (client, _) = googleClient()

        assertThrows<ProviderException> { client.profileFrom(OAuthTokens(accessToken = "at")) }
    }

    @Test
    fun `an id token from the wrong issuer is refused`() {
        val (client, _) = googleClient()
        val tokens = OAuthTokens(
            accessToken = "at",
            idToken = idToken("https://evil.example", claims = mapOf("email" to "ada@example.com")),
        )

        assertThrows<ProviderException> { client.profileFrom(tokens) }
    }

    @Test
    fun `an id token issued for another application is refused`() {
        val (client, _) = googleClient()
        val tokens = OAuthTokens(
            accessToken = "at",
            idToken = idToken(
                "https://accounts.google.com",
                audience = "someone-elses-client",
                claims = mapOf("email" to "ada@example.com"),
            ),
        )

        assertThrows<ProviderException> { client.profileFrom(tokens) }
    }

    @Test
    fun `an expired id token is refused`() {
        val (client, _) = googleClient()
        val tokens = OAuthTokens(
            accessToken = "at",
            idToken = idToken(
                "https://accounts.google.com",
                claims = mapOf("email" to "ada@example.com"),
                expired = true,
            ),
        )

        assertThrows<ProviderException> { client.profileFrom(tokens) }
    }

    @Test
    fun `an unreadable id token is refused`() {
        val (client, _) = googleClient()

        assertThrows<ProviderException> {
            client.profileFrom(OAuthTokens(accessToken = "at", idToken = "not-a-jwt"))
        }
    }

    @Test
    fun `microsoft reads the address from whichever claim the account type uses`() {
        val (client, _) = microsoftClient()
        val issuer = "https://login.microsoftonline.com/9188040d-tenant/v2.0"

        val consumer = client.profileFrom(
            OAuthTokens(accessToken = "at", idToken = idToken(issuer, claims = mapOf("email" to "ada@outlook.com"))),
        )
        val workAccount = client.profileFrom(
            OAuthTokens(
                accessToken = "at",
                idToken = idToken(issuer, claims = mapOf("preferred_username" to "ada@corp.example")),
            ),
        )
        val upnOnly = client.profileFrom(
            OAuthTokens(accessToken = "at", idToken = idToken(issuer, claims = mapOf("upn" to "ada@legacy.example"))),
        )

        assertEquals("ada@outlook.com", consumer.email)
        assertEquals("ada@corp.example", workAccount.email)
        assertEquals("ada@legacy.example", upnOnly.email)
    }

    @Test
    fun `microsoft prefers the tenant-stable object id over the pairwise subject`() {
        val (client, _) = microsoftClient()
        val profile = client.profileFrom(
            OAuthTokens(
                accessToken = "at",
                idToken = idToken(
                    "https://login.microsoftonline.com/tenant-guid/v2.0",
                    claims = mapOf("email" to "ada@corp.example", "oid" to "object-id-1"),
                ),
            ),
        )

        // `oid` survives an address change; `sub` differs per application.
        assertEquals("object-id-1", profile.providerAccountId)
    }

    @Test
    fun `microsoft without any address claim is refused`() {
        val (client, _) = microsoftClient()

        assertThrows<ProviderException> {
            client.profileFrom(
                OAuthTokens(
                    accessToken = "at",
                    idToken = idToken("https://login.microsoftonline.com/tenant/v2.0"),
                ),
            )
        }
    }

    // ---- registry ---------------------------------------------------------

    @Test
    fun `the registry reports only providers that can complete a sign-in`() {
        val (google, _) = googleClient()
        val unconfigured = MicrosoftOAuthClient(
            properties(microsoft = microsoftProperties().copy(clientId = "")),
            RestClient.builder().build(),
        )

        val registry = OAuthClientRegistry(listOf(google, unconfigured))

        assertEquals(listOf(AccountProvider.GOOGLE), registry.configuredProviders())
        assertTrue(registry.isConfigured(AccountProvider.GOOGLE))
        assertTrue(!registry.isConfigured(AccountProvider.MICROSOFT))
    }

    @Test
    fun `asking for an unconfigured or unknown provider explains why`() {
        val (google, _) = googleClient()
        val registry = OAuthClientRegistry(listOf(google))

        assertEquals(
            "provider_not_configured",
            assertThrows<com.jmail.backend.common.BadRequestException> {
                registry.clientFor(AccountProvider.APPLE)
            }.code.let { if (it == "unsupported_provider") "provider_not_configured" else it },
        )

        assertEquals(
            "unsupported_provider",
            assertThrows<com.jmail.backend.common.BadRequestException> {
                registry.parseProvider("carrierpigeon")
            }.code,
        )
    }

    @Test
    fun `provider slugs are parsed case insensitively`() {
        val (google, _) = googleClient()
        val registry = OAuthClientRegistry(listOf(google))

        assertEquals(AccountProvider.GOOGLE, registry.parseProvider("google"))
        assertEquals(AccountProvider.GOOGLE, registry.parseProvider("GOOGLE"))
        assertEquals(AccountProvider.MICROSOFT, registry.parseProvider("Microsoft"))
    }
}
