package com.jmail.backend.auth

import com.jmail.backend.config.JmailProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts provider credentials (OAuth refresh tokens, IMAP passwords) before they are
 * written to the database.
 *
 * AES-256-GCM with a random 96-bit nonce per value: GCM authenticates the ciphertext, so a
 * tampered row fails to decrypt rather than silently yielding a different credential. The
 * nonce is prefixed to the ciphertext, and the whole thing is stored base64-encoded.
 */
@Component
class CredentialCipher(properties: JmailProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val key: SecretKeySpec

    init {
        val configured = properties.security.encryptionKey
        val material = if (configured.isBlank()) {
            log.warn(
                "jmail.security.encryption-key is not set. Generating an ephemeral key: linked " +
                    "accounts will need to be reconnected after a restart. Set JMAIL_ENCRYPTION_KEY " +
                    "to a stable 32-byte secret in any environment you care about.",
            )
            ByteArray(32).also(random::nextBytes)
        } else {
            // The configured value is any-length text; SHA-256 turns it into exactly 32 bytes
            // without requiring operators to produce base64 key material by hand.
            MessageDigest.getInstance("SHA-256").digest(configured.toByteArray())
        }
        key = SecretKeySpec(material, "AES")
    }

    fun encrypt(plaintext: String?): String? {
        if (plaintext == null) return null

        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray())

        return Base64.getEncoder().encodeToString(nonce + ciphertext)
    }

    /**
     * Returns null when the value cannot be decrypted — which happens legitimately when the
     * ephemeral development key is regenerated. Callers treat that as "credentials gone",
     * prompting a reconnect, rather than failing the whole request.
     */
    fun decrypt(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null

        return runCatching {
            val raw = Base64.getDecoder().decode(encrypted)
            require(raw.size > NONCE_BYTES) { "Ciphertext is too short to contain a nonce" }

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES),
                )
            }
            String(cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES))
        }.onFailure {
            log.warn("Stored credential could not be decrypted; the account must be reconnected")
        }.getOrNull()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
