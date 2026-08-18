package com.jmail.backend.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import com.jmail.backend.common.UnauthorizedException
import com.jmail.backend.config.JmailProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

/**
 * Exchange sign-in is verified against a real IMAP server rather than a mock.
 *
 * The whole point of this code path is that it proves credentials work *before* storing
 * them, and a mocked `Store.connect` would prove nothing about whether it does.
 */
class ExchangeAuthenticatorTest {

    // A dynamic port rather than GreenMail's default 3143: the local Docker stack also
    // publishes an IMAP server on that port, and tests must not depend on it being down.
    @JvmField
    @RegisterExtension
    val greenMail: GreenMailExtension = GreenMailExtension(ServerSetupTest.IMAP.dynamicPort())
        .withConfiguration(
            com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig()
                .withUser("ada@example.com", "ada@example.com", "correct-password"),
        )

    private val authenticator = ExchangeAuthenticator(
        JmailProperties(
            exchange = JmailProperties.ExchangeProperties(
                connectTimeout = Duration.ofSeconds(5),
                readTimeout = Duration.ofSeconds(5),
            ),
        ),
    )

    private fun credentials(
        password: String = "correct-password",
        host: String = "127.0.0.1",
        port: Int = greenMail.imap.port,
    ) = ExchangeCredentials(
        email = "ada@example.com",
        password = password,
        imapHost = host,
        imapPort = port,
        smtpHost = host,
        smtpPort = port,
        useTls = false, // the in-process test server speaks plain IMAP
    )

    @Test
    fun `accepts credentials the server accepts`() {
        assertDoesNotThrow { authenticator.verify(credentials()) }
    }

    @Test
    fun `rejects a wrong password with a message the user can act on`() {
        val failure = assertThrows<UnauthorizedException> {
            authenticator.verify(credentials(password = "wrong-password"))
        }

        assertThat(failure.code).isEqualTo("exchange_authentication_failed")
        // The message names the server so a user with several accounts knows which failed.
        assertThat(failure.message.contains("127.0.0.1")).isTrue()
    }

    @Test
    fun `reports an unreachable server separately from a rejected password`() {
        val failure = assertThrows<UnauthorizedException> {
            authenticator.verify(credentials(port = 1)) // nothing is listening here
        }

        assertThat(failure.code).isEqualTo("exchange_unreachable")
    }

    @Test
    fun `suggests exact settings for a recognised service`() {
        val suggestion = authenticator.suggestSettings("someone@outlook.com")

        assertThat(suggestion.imapHost).isEqualTo("outlook.office365.com")
        assertThat(suggestion.smtpHost).isEqualTo("smtp-mail.outlook.com")
        assertThat(suggestion.imapPort).isEqualTo(993)
        assertThat(suggestion.confident).isTrue()
        // The app-password warning travels with the suggestion, so the form can show it
        // before the user types the wrong password.
        assertThat(suggestion.provider?.requiresAppPassword).isEqualTo(true)
    }

    @Test
    fun `suggests Gmail's servers for a Gmail address`() {
        val suggestion = authenticator.suggestSettings("someone@gmail.com")

        assertThat(suggestion.imapHost).isEqualTo("imap.gmail.com")
        assertThat(suggestion.smtpHost).isEqualTo("smtp.gmail.com")
        assertThat(suggestion.confident).isTrue()
    }

    @Test
    fun `offers every known service for the picker`() {
        val ids = authenticator.knownProviders().map { it.id }

        assertThat(ids.contains("gmail")).isTrue()
        assertThat(ids.contains("icloud")).isTrue()
        assertThat(ids.contains("other")).isTrue()
    }

    @Test
    fun `falls back to the naming convention for unknown domains, flagged as a guess`() {
        val suggestion = authenticator.suggestSettings("someone@acme-corp.example")

        assertThat(suggestion.imapHost).isEqualTo("imap.acme-corp.example")
        assertThat(suggestion.smtpHost).isEqualTo("smtp.acme-corp.example")
        // The UI uses this to say "we guessed" rather than presenting it as fact.
        assertThat(suggestion.confident).isFalse()
    }

    @Test
    fun `a configured default host overrides the guess for a self-hosted deployment`() {
        val configured = ExchangeAuthenticator(
            JmailProperties(
                exchange = JmailProperties.ExchangeProperties(
                    defaultHost = "mail.internal.example",
                    defaultImapPort = 1993,
                    defaultSmtpPort = 1587,
                ),
            ),
        )

        val suggestion = configured.suggestSettings("someone@anything.example")

        assertThat(suggestion.imapHost).isEqualTo("mail.internal.example")
        assertThat(suggestion.imapPort).isEqualTo(1993)
        assertThat(suggestion.smtpPort).isEqualTo(1587)
        assertThat(suggestion.confident).isTrue()
    }

    @Test
    fun `an address with no domain produces an empty suggestion rather than nonsense`() {
        val suggestion = authenticator.suggestSettings("not-an-address")

        assertThat(suggestion.imapHost).isEqualTo("")
        assertThat(suggestion.confident).isFalse()
    }
}

/**
 * The directory of known mail services.
 *
 * These are the settings people would otherwise have to look up, and getting one wrong means
 * a sign-in that fails with no explanation — so each is asserted rather than trusted.
 */
class KnownMailProvidersTest {

    @Test
    fun `the big consumer services are recognised from the address`() {
        assertThat(KnownMailProviders.forEmail("ada@gmail.com")?.id).isEqualTo("gmail")
        assertThat(KnownMailProviders.forEmail("ada@googlemail.com")?.id).isEqualTo("gmail")
        assertThat(KnownMailProviders.forEmail("ada@outlook.com")?.id).isEqualTo("outlook")
        assertThat(KnownMailProviders.forEmail("ada@hotmail.com")?.id).isEqualTo("outlook")
        assertThat(KnownMailProviders.forEmail("ada@live.com")?.id).isEqualTo("outlook")
        assertThat(KnownMailProviders.forEmail("ada@icloud.com")?.id).isEqualTo("icloud")
        assertThat(KnownMailProviders.forEmail("ada@me.com")?.id).isEqualTo("icloud")
        assertThat(KnownMailProviders.forEmail("ada@yahoo.co.uk")?.id).isEqualTo("yahoo")
        assertThat(KnownMailProviders.forEmail("ada@aol.com")?.id).isEqualTo("aol")
        assertThat(KnownMailProviders.forEmail("ada@fastmail.com")?.id).isEqualTo("fastmail")
        assertThat(KnownMailProviders.forEmail("ada@proton.me")?.id).isEqualTo("proton")
        assertThat(KnownMailProviders.forEmail("ada@yandex.ru")?.id).isEqualTo("yandex")
    }

    @Test
    fun `recognition is case insensitive`() {
        assertThat(KnownMailProviders.forEmail("Ada@GMAIL.com")?.id).isEqualTo("gmail")
    }

    @Test
    fun `a Microsoft 365 tenant is recognised by its fallback domain`() {
        assertThat(KnownMailProviders.forEmail("ada@contoso.onmicrosoft.com")?.id).isEqualTo("office365")
    }

    @Test
    fun `an unknown domain returns nothing rather than a guess`() {
        // The caller distinguishes "we know this service" from "we are trying a convention".
        assertThat(KnownMailProviders.forEmail("ada@acme-corp.example")).isNull()
        assertThat(KnownMailProviders.forEmail("not-an-address")).isNull()
    }

    @Test
    fun `every service that needs an app password says where to get one`() {
        KnownMailProviders.all
            .filter(KnownMailProvider::requiresAppPassword)
            .forEach { provider ->
                assertThat(provider.appPasswordUrl)
                    .isNotNull()
                assertThat(provider.helpText).isNotNull()
            }
    }

    @Test
    fun `every service either knows its servers or asks the user for them`() {
        KnownMailProviders.all.forEach { provider ->
            val knowsServers = provider.imapHost.isNotBlank() && provider.smtpHost.isNotBlank()
            val asksTheUser = provider.imapHost.isBlank() && provider.smtpHost.isBlank()

            assertThat(knowsServers || asksTheUser).isTrue()
            assertThat(provider.imapPort in 1..65535).isTrue()
            assertThat(provider.smtpPort in 1..65535).isTrue()
        }
    }

    @Test
    fun `ids are unique, since the client keys its picker on them`() {
        val ids = KnownMailProviders.all.map(KnownMailProvider::id)

        assertThat(ids.size).isEqualTo(ids.distinct().size)
    }

    @Test
    fun `no domain is claimed by two services`() {
        val domains = KnownMailProviders.all.flatMap(KnownMailProvider::domains)

        assertThat(domains.size).isEqualTo(domains.distinct().size)
    }

    @Test
    fun `a service can be looked up by id for the picker`() {
        assertThat(KnownMailProviders.byId("gmail")?.displayName).isEqualTo("Gmail")
        assertThat(KnownMailProviders.byId("GMAIL")?.displayName).isEqualTo("Gmail")
        assertThat(KnownMailProviders.byId("nonexistent")).isNull()
    }

    @Test
    fun `Proton is configured for Bridge on the local machine`() {
        val proton = KnownMailProviders.PROTON

        // Proton is end-to-end encrypted, so IMAP only ever reaches Bridge on localhost.
        assertThat(proton.imapHost).isEqualTo("127.0.0.1")
        assertThat(proton.useTls).isFalse()
        assertThat(proton.imapPort).isEqualTo(1143)
    }
}
