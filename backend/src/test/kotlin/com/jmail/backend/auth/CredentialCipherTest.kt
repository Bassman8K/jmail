package com.jmail.backend.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.jmail.backend.config.JmailProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Provider credentials are the most sensitive thing JMail stores. These tests pin the
 * properties that make the encryption worth having: it is authenticated, it is not
 * deterministic, and a wrong key fails closed instead of returning garbage.
 */
class CredentialCipherTest {

    private fun cipher(key: String = "test-encryption-key-material") =
        CredentialCipher(JmailProperties(security = JmailProperties.SecurityProperties(encryptionKey = key)))

    @ParameterizedTest
    @ValueSource(
        strings = [
            "simple-token",
            "ya29.a0AfH6SMBx-very-long-google-refresh-token-value-here",
            "password with spaces and symbols !@#\$%^&*()",
            "unicode: ünïcödé 密码 🔐",
            "",
        ],
    )
    fun `round trips any credential a provider might issue`(plaintext: String) {
        val subject = cipher()

        val decrypted = subject.decrypt(subject.encrypt(plaintext))

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `encrypting the same value twice produces different ciphertext`() {
        val subject = cipher()

        val first = subject.encrypt("refresh-token")
        val second = subject.encrypt("refresh-token")

        // A fresh nonce per encryption; identical output would leak that two accounts share
        // a credential and would be catastrophic for GCM's security guarantees.
        assertThat(first).isNotEqualTo(second)
        assertThat(subject.decrypt(first)).isEqualTo(subject.decrypt(second))
    }

    @Test
    fun `a different key cannot read the ciphertext`() {
        val encrypted = cipher(key = "the-original-key").encrypt("secret-token")

        val decrypted = cipher(key = "a-different-key").decrypt(encrypted)

        assertThat(decrypted).isNull()
    }

    @Test
    fun `tampered ciphertext is rejected rather than silently decrypted`() {
        val subject = cipher()
        val encrypted = subject.encrypt("secret-token")!!

        // Flip the final character: GCM's authentication tag must catch it.
        val tampered = encrypted.dropLast(1) + if (encrypted.last() == 'A') 'B' else 'A'

        assertThat(subject.decrypt(tampered)).isNull()
    }

    @Test
    fun `null in, null out, so an unset credential stays unset`() {
        val subject = cipher()

        assertThat(subject.encrypt(null)).isNull()
        assertThat(subject.decrypt(null)).isNull()
        assertThat(subject.decrypt("")).isNull()
    }

    @Test
    fun `garbage input decrypts to null instead of throwing`() {
        val subject = cipher()

        assertThat(subject.decrypt("this is not base64 at all!!")).isNull()
        assertThat(subject.decrypt("dG9vLXNob3J0")).isNull() // valid base64, too short for a nonce
    }

    @Test
    fun `a blank configured key still produces a working ephemeral cipher`() {
        val subject = cipher(key = "")

        val encrypted = subject.encrypt("token")

        assertThat(encrypted).isNotNull()
        assertThat(subject.decrypt(encrypted)).isEqualTo("token")
    }
}
