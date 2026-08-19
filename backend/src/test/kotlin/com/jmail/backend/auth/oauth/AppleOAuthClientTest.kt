package com.jmail.backend.auth.oauth

import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apple's sign-in is the odd one out, and every difference here is a place JMail got a bug
 * or would have:
 *
 *  * the client secret is a signed assertion, not a fixed string;
 *  * the callback is a form POST, because `name` and `email` scopes require `form_post`;
 *  * the display name arrives exactly once, in a JSON blob beside the code, and never again;
 *  * "Hide My Email" means the address may be a private relay, and may be absent entirely.
 */
class AppleOAuthClientTest {

    private val secret = "a-signing-secret-long-enough-for-hmac-sha256-in-tests"

    private fun appleProperties(
        clientId: String = "com.jmail.service",
        teamId: String = "TEAM123456",
        keyId: String = "KEY1234567",
        privateKey: String = "-----BEGIN PRIVATE KEY-----stub-----END PRIVATE KEY-----",
    ) = JmailProperties.OAuthProviderProperties(
        clientId = clientId,
        teamId = teamId,
        keyId = keyId,
        privateKey = privateKey,
        authorizationUri = "https://appleid.apple.com/auth/authorize",
        tokenUri = "https://appleid.apple.com/auth/token",
        issuer = "https://appleid.apple.com",
        scopes = listOf("openid", "name", "email"),
    )

    private fun client(
        configured: Boolean = true,
        apple: JmailProperties.OAuthProviderProperties = appleProperties(),
    ): AppleOAuthClient {
        val factory = mockk<AppleClientSecretFactory>()
        every { factory.isConfigured() } returns configured
        every { factory.clientSecret(any()) } returns "signed.client.assertion"

        val properties = JmailProperties(
            baseUrl = "http://localhost:8090",
            providers = JmailProperties.ProvidersProperties(apple = apple),
        )
        return AppleOAuthClient(properties, RestClient.builder().build(), factory)
    }

    private fun idToken(
        subject: String = "001234.abcdef.0000",
        email: String? = "ada@privaterelay.appleid.com",
    ): String {
        val builder = JWTClaimsSet.Builder()
            .issuer("https://appleid.apple.com")
            .audience("com.jmail.service")
            .subject(subject)
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
        if (email != null) builder.claim("email", email)

        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build())
            .apply { sign(MACSigner(secret.toByteArray())) }
            .serialize()
    }

    private fun session(codeVerifier: String = Pkce.generateCodeVerifier()) = AuthSession(
        state = Pkce.generateState(),
        codeVerifier = codeVerifier,
        nonce = Pkce.generateNonce(),
        provider = AccountProvider.APPLE,
        target = ClientTarget.WEB,
        redirectUri = "http://localhost:8090",
    )

    private fun tokens(idToken: String?) = OAuthTokens(
        accessToken = "access",
        refreshToken = "refresh",
        idToken = idToken,
        expiresInSeconds = 3600,
    )

    // ---- configuration ------------------------------------------------------------

    @Test
    fun `apple is only configured once a signing key is present`() {
        assertTrue(client(configured = true).isConfigured)
        // Enabled in config but without a .p8 key, it must report itself unconfigured
        // rather than failing later with a signature error the user cannot act on.
        assertTrue(!client(configured = false).isConfigured)
    }

    @Test
    fun `the callback comes back as a form post, which apple requires for name and email`() {
        val url = client().buildAuthorizationUrl(session())

        assertTrue(url.startsWith("https://appleid.apple.com/auth/authorize"), url)
        assertTrue(url.contains("response_mode=form_post"), url)
        // Asking for these scopes is exactly what obliges Apple to use form_post.
        assertTrue(url.contains("name"), url)
        assertTrue(url.contains("email"), url)
    }

    @Test
    fun `the redirect uri is built from the configured base url`() {
        assertEquals("http://localhost:8090/api/v1/auth/apple/callback", client().redirectUri())
    }

    @Test
    fun `the token exchange sends the signed assertion as the client secret`() {
        // Apple has no static client secret: it is a short-lived ES256 assertion, and
        // sending anything else fails with an error that does not say why.
        val factory = mockk<AppleClientSecretFactory>()
        every { factory.isConfigured() } returns true
        every { factory.clientSecret(any()) } returns "signed.client.assertion"

        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val apple = AppleOAuthClient(
            JmailProperties(
                baseUrl = "http://localhost:8090",
                providers = JmailProperties.ProvidersProperties(apple = appleProperties()),
            ),
            builder.build(),
            factory,
        )

        server.expect(requestTo("https://appleid.apple.com/auth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formData(
                LinkedMultiValueMap<String, String>().apply {
                    add("grant_type", "authorization_code")
                    add("code", "the-code")
                    add("client_id", "com.jmail.service")
                    add("client_secret", "signed.client.assertion")
                    add("redirect_uri", "http://localhost:8090/api/v1/auth/apple/callback")
                    add("code_verifier", "verifier")
                },
            ))
            .andRespond(
                withSuccess(
                    """{"access_token":"a","refresh_token":"r","id_token":"${idToken()}","expires_in":3600}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = apple.exchangeCode("the-code", session(codeVerifier = "verifier"))

        assertEquals("a", result.accessToken)
        server.verify()
    }

    @Test
    fun `the provider identifies itself as apple`() {
        assertEquals(AccountProvider.APPLE, client().provider)
    }

    // ---- the profile in the ID token ----------------------------------------------

    @Test
    fun `a profile is read from the id token, with the address canonicalised`() {
        val profile = client().profileFrom(tokens(idToken(email = "Ada@PrivateRelay.AppleID.com")))

        assertEquals("001234.abcdef.0000", profile.providerAccountId)
        assertEquals("ada@privaterelay.appleid.com", profile.email)
        assertNull(profile.avatarUrl)
    }

    @Test
    fun `with no name available the local part stands in`() {
        // Apple never puts a name in the ID token; this is the fallback until the caller
        // merges in whatever the one-off callback payload carried.
        assertEquals("ada", client().profileFrom(tokens(idToken(email = "ada@example.com"))).displayName)
    }

    @Test
    fun `a missing id token is reported as a provider failure`() {
        val failure = assertThrows<ProviderException> { client().profileFrom(tokens(null)) }
        assertTrue(failure.message.contains("ID token"), failure.message)
    }

    @Test
    fun `hiding the email entirely is explained, with the fix, rather than failing obscurely`() {
        val failure = assertThrows<ProviderException> {
            client().profileFrom(tokens(idToken(email = null)))
        }
        // The user can only fix this by reconnecting and choosing to share, so say so.
        assertTrue(failure.message.contains("Share My Email"), failure.message)
    }

    // ---- the one-shot name payload ------------------------------------------------

    @Test
    fun `the name is taken from the payload apple posts on first consent`() {
        val name = client().nameFromCallback(
            """{"name":{"firstName":"Ada","lastName":"Lovelace"},"email":"ada@example.com"}""",
        )
        assertEquals("Ada Lovelace", name)
    }

    @Test
    fun `a first name on its own is enough`() {
        assertEquals("Ada", client().nameFromCallback("""{"name":{"firstName":"Ada"}}"""))
    }

    @Test
    fun `a last name on its own is enough`() {
        assertEquals("Lovelace", client().nameFromCallback("""{"name":{"lastName":"Lovelace"}}"""))
    }

    @Test
    fun `an absent payload is not an error - it is the normal case on every later sign-in`() {
        assertNull(client().nameFromCallback(null))
        assertNull(client().nameFromCallback(""))
        assertNull(client().nameFromCallback("   "))
    }

    @Test
    fun `a payload with no name object yields nothing`() {
        assertNull(client().nameFromCallback("""{"email":"ada@example.com"}"""))
    }

    @Test
    fun `an empty name object yields nothing rather than a blank display name`() {
        assertNull(client().nameFromCallback("""{"name":{}}"""))
        assertNull(client().nameFromCallback("""{"name":{"firstName":"","lastName":""}}"""))
    }

    @Test
    fun `unknown fields are ignored, so apple can add to the payload`() {
        val name = client().nameFromCallback(
            """{"name":{"firstName":"Ada","middleName":"Byron","lastName":"Lovelace"},"extra":1}""",
        )
        assertEquals("Ada Lovelace", name)
    }

    @Test
    fun `malformed json is swallowed rather than failing the sign-in`() {
        // The name is a nicety; the sign-in itself must not fail because of it.
        assertNull(client().nameFromCallback("not json at all"))
        assertNull(client().nameFromCallback("""{"name":"Ada"}"""))
        assertNull(client().nameFromCallback("""{"name":{"firstName":["Ada"]}}"""))
        assertNull(client().nameFromCallback("[1,2,3]"))
    }

    @Test
    fun `surrounding whitespace in the parts does not leak into the name`() {
        assertEquals(
            "Ada Lovelace",
            client().nameFromCallback("""{"name":{"firstName":"Ada","lastName":"Lovelace "}}"""),
        )
    }
}
