package com.jmail.backend.auth.oauth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

/**
 * Apple's client secret is a signed assertion rather than a static string, which means a
 * bug here does not fail loudly — it fails at the token endpoint with an opaque error. These
 * tests verify the assertion against a real P-256 key, exactly as Apple would.
 */
class AppleClientSecretFactoryTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1")) // the curve ES256 requires
    }.generateKeyPair()

    private val privateKeyPem: String = Base64.getEncoder().encodeToString(keyPair.private.encoded)

    private fun factory(
        clientId: String = "com.jmail.service",
        teamId: String = "TEAM123456",
        keyId: String = "KEY7890123",
        privateKey: String = privateKeyPem,
    ) = AppleClientSecretFactory(
        JmailProperties(
            providers = JmailProperties.ProvidersProperties(
                apple = JmailProperties.OAuthProviderProperties(
                    clientId = clientId,
                    teamId = teamId,
                    keyId = keyId,
                    privateKey = privateKey,
                ),
            ),
        ),
    )

    @Test
    fun `produces an ES256 assertion Apple can verify`() {
        val secret = factory().clientSecret()

        val jwt = SignedJWT.parse(secret)

        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.ES256)
        assertThat(jwt.header.keyID).isEqualTo("KEY7890123")
        assertThat(jwt.verify(ECDSAVerifier(keyPair.public as ECPublicKey))).isTrue()
    }

    @Test
    fun `carries the claims Apple requires`() {
        val jwt = SignedJWT.parse(factory().clientSecret())
        val claims = jwt.jwtClaimsSet

        assertThat(claims.issuer).isEqualTo("TEAM123456") // the team, not the client
        assertThat(claims.subject).isEqualTo("com.jmail.service") // the Services ID
        assertThat(claims.audience).isEqualTo(listOf("https://appleid.apple.com"))
        assertThat(claims.expirationTime.toInstant().isAfter(Instant.now())).isTrue()
    }

    @Test
    fun `caches the assertion instead of re-signing on every call`() {
        val subject = factory()

        val first = subject.clientSecret()
        val second = subject.clientSecret()

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `re-signs once the cached assertion is close to expiry`() {
        val subject = factory()

        val fresh = subject.clientSecret()
        // Asking as if from a day ahead: the cached value is no longer good enough.
        val later = subject.clientSecret(Instant.now().plusSeconds(90_000))

        assertThat(fresh == later).isFalse()
    }

    @Test
    fun `accepts a key with PEM armour and escaped newlines, as delivered by an env var`() {
        val armoured = buildString {
            append("-----BEGIN PRIVATE KEY-----\\n")
            append(privateKeyPem.chunked(64).joinToString("\\n"))
            append("\\n-----END PRIVATE KEY-----\\n")
        }

        val secret = factory(privateKey = armoured).clientSecret()

        assertThat(SignedJWT.parse(secret).verify(ECDSAVerifier(keyPair.public as ECPublicKey))).isTrue()
    }

    @Test
    fun `isConfigured requires every part of the credential`() {
        assertThat(factory().isConfigured()).isTrue()
        assertThat(factory(clientId = "").isConfigured()).isFalse()
        assertThat(factory(teamId = "").isConfigured()).isFalse()
        assertThat(factory(keyId = "").isConfigured()).isFalse()
        assertThat(factory(privateKey = "").isConfigured()).isFalse()
    }

    @Test
    fun `an unconfigured provider explains what is missing rather than failing obscurely`() {
        val failure = assertThrows<ProviderException> { factory(privateKey = "").clientSecret() }

        assertThat(failure.message.contains("APPLE_PRIVATE_KEY")).isTrue()
    }

    @Test
    fun `a malformed key is reported clearly`() {
        assertThrows<ProviderException> { factory(privateKey = "not-base64!!").clientSecret() }
        assertThrows<ProviderException> {
            factory(privateKey = Base64.getEncoder().encodeToString("not a key".toByteArray())).clientSecret()
        }
    }

    @Test
    fun `an RSA key is rejected, since Apple requires an elliptic curve key`() {
        val rsaKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .private

        assertThrows<ProviderException> {
            factory(privateKey = Base64.getEncoder().encodeToString(rsaKey.encoded)).clientSecret()
        }
    }
}
