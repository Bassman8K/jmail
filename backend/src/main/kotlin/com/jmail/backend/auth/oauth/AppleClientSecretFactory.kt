package com.jmail.backend.auth.oauth

import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/**
 * Apple does not issue a static client secret. Instead the client authenticates at the
 * token endpoint with a short-lived ES256 JWT signed by the `.p8` key downloaded from the
 * developer portal.
 *
 * The generated assertion is cached until shortly before it expires, because signing on
 * every token call is pure overhead and Apple caps the lifetime at six months anyway.
 */
@Component
class AppleClientSecretFactory(properties: JmailProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val apple = properties.providers.apple
    private val cached = AtomicReference<CachedSecret?>(null)

    fun isConfigured(): Boolean =
        apple.clientId.isNotBlank() &&
            apple.teamId.isNotBlank() &&
            apple.keyId.isNotBlank() &&
            apple.privateKey.isNotBlank()

    fun clientSecret(now: Instant = Instant.now()): String {
        val current = cached.get()
        if (current != null && current.expiresAt.isAfter(now.plus(REFRESH_MARGIN))) {
            return current.value
        }

        val secret = generate(now)
        cached.set(CachedSecret(secret, now.plus(LIFETIME)))
        return secret
    }

    private fun generate(now: Instant): String {
        if (!isConfigured()) {
            throw ProviderException(
                "Apple",
                "Apple sign-in is not configured: set APPLE_CLIENT_ID, APPLE_TEAM_ID, APPLE_KEY_ID and APPLE_PRIVATE_KEY",
            )
        }

        val claims = JWTClaimsSet.Builder()
            .issuer(apple.teamId)
            .subject(apple.clientId) // the Services ID, not the app's bundle ID
            .audience("https://appleid.apple.com")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(LIFETIME)))
            .build()

        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(apple.keyId)
                .type(JOSEObjectType.JWT)
                .build(),
            claims,
        )

        return runCatching {
            jwt.sign(ECDSASigner(parsePrivateKey(apple.privateKey)))
            jwt.serialize()
        }.getOrElse { failure ->
            log.error("Could not sign the Apple client secret", failure)
            throw ProviderException("Apple", "The configured Apple private key could not be used for signing", failure)
        }
    }

    /**
     * Reads the PKCS#8 key from a `.p8` file. Accepts the file verbatim, with or without
     * the PEM armour, and with literal `\n` sequences — which is how the key survives being
     * passed through an environment variable.
     */
    internal fun parsePrivateKey(pem: String): ECPrivateKey {
        val normalised = pem
            .replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot(Char::isWhitespace)

        val der = runCatching { Base64.getDecoder().decode(normalised) }.getOrElse { failure ->
            throw ProviderException("Apple", "APPLE_PRIVATE_KEY is not valid base64 PKCS#8 content", failure)
        }

        return runCatching {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
        }.getOrElse { failure ->
            throw ProviderException("Apple", "APPLE_PRIVATE_KEY is not an EC private key in PKCS#8 form", failure)
        }
    }

    private data class CachedSecret(val value: String, val expiresAt: Instant)

    private companion object {
        /** Apple permits up to six months; a day keeps the blast radius of a leak small. */
        val LIFETIME: Duration = Duration.ofHours(24)
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(30)
    }
}
