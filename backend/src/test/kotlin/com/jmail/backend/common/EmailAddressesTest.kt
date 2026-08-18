package com.jmail.backend.common

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Address parsing is where mail clients quietly break: a display name containing a comma, a
 * quoted local part, or a header a provider encoded slightly differently. These cases are
 * all drawn from shapes that real servers emit.
 */
class EmailAddressesTest {

    @Nested
    @DisplayName("canonical")
    inner class Canonical {

        @Test
        fun `lowercases and trims`() {
            assertThat(EmailAddresses.canonical("  Ada@Example.COM ")).isEqualTo("ada@example.com")
        }

        @Test
        fun `leaves an already canonical address untouched`() {
            assertThat(EmailAddresses.canonical("ada@example.com")).isEqualTo("ada@example.com")
        }
    }

    @Nested
    @DisplayName("isValid")
    inner class Validity {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "ada@example.com",
                "ada.lovelace+jmail@example.co.uk",
                "a@b.io",
                "first_last@sub.domain.example",
            ],
        )
        fun `accepts addresses real servers deliver to`(address: String) {
            assertThat(EmailAddresses.isValid(address)).isTrue()
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "not-an-address",
                "@example.com",
                "ada@",
                "ada@example",
                "ada example@test.com",
                "",
            ],
        )
        fun `rejects malformed addresses`(address: String) {
            assertThat(EmailAddresses.isValid(address)).isFalse()
        }
    }

    @Nested
    @DisplayName("parse")
    inner class Parse {

        @Test
        fun `reads a bare address`() {
            val parsed = EmailAddresses.parse("Ada@Example.com")

            assertThat(parsed?.address).isEqualTo("ada@example.com")
            assertThat(parsed?.name).isNull()
        }

        @Test
        fun `reads a display name in angle brackets`() {
            val parsed = EmailAddresses.parse("Ada Lovelace <Ada@Example.com>")

            assertThat(parsed?.address).isEqualTo("ada@example.com")
            assertThat(parsed?.name).isEqualTo("Ada Lovelace")
        }

        @Test
        fun `strips the quotes around a quoted display name`() {
            val parsed = EmailAddresses.parse("\"Lovelace, Ada\" <ada@example.com>")

            assertThat(parsed?.name).isEqualTo("Lovelace, Ada")
        }

        @Test
        fun `returns null when no address can be recovered`() {
            assertThat(EmailAddresses.parse("Ada Lovelace")).isNull()
            assertThat(EmailAddresses.parse("   ")).isNull()
        }
    }

    @Nested
    @DisplayName("parseList")
    inner class ParseList {

        @Test
        fun `splits a plain comma separated header`() {
            val parsed = EmailAddresses.parseList("ada@example.com, tom@example.com")

            assertThat(parsed.map(EmailAddress::address))
                .containsExactly("ada@example.com", "tom@example.com")
        }

        @Test
        fun `does not split on a comma inside a quoted display name`() {
            val parsed = EmailAddresses.parseList(
                "\"Lovelace, Ada\" <ada@example.com>, \"Okafor, Tom\" <tom@example.com>",
            )

            assertThat(parsed.map(EmailAddress::name)).containsExactly("Lovelace, Ada", "Okafor, Tom")
            assertThat(parsed.map(EmailAddress::address))
                .containsExactly("ada@example.com", "tom@example.com")
        }

        @Test
        fun `skips entries that cannot be parsed rather than failing the header`() {
            val parsed = EmailAddresses.parseList("ada@example.com, undisclosed-recipients:;, tom@example.com")

            assertThat(parsed.map(EmailAddress::address))
                .containsExactly("ada@example.com", "tom@example.com")
        }

        @Test
        fun `returns empty for a null or blank header`() {
            assertThat(EmailAddresses.parseList(null)).isEmpty()
            assertThat(EmailAddresses.parseList("  ")).isEmpty()
        }
    }

    @Nested
    @DisplayName("domainOf")
    inner class DomainOf {

        @Test
        fun `returns the lowercased domain`() {
            assertThat(EmailAddresses.domainOf("Ada@Example.COM")).isEqualTo("example.com")
        }

        @Test
        fun `returns empty when there is no domain`() {
            assertThat(EmailAddresses.domainOf("not-an-address")).isEqualTo("")
        }

        @Test
        fun `uses the last at sign, which is the delimiter per RFC 5321`() {
            assertThat(EmailAddresses.domainOf("weird@local@example.com")).isEqualTo("example.com")
        }
    }

    @Test
    fun `format renders a name when present and the bare address otherwise`() {
        assertThat(EmailAddress("ada@example.com", "Ada Lovelace").format())
            .isEqualTo("Ada Lovelace <ada@example.com>")
        assertThat(EmailAddress("ada@example.com").format()).isEqualTo("ada@example.com")
        assertThat(EmailAddress("ada@example.com", "  ").format()).isEqualTo("ada@example.com")
    }
}
