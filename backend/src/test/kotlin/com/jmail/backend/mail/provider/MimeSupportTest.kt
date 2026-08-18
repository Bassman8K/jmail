package com.jmail.backend.mail.provider

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.user.MailAccount
import jakarta.mail.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class MimeSupportTest {

    private val objectMapper = ObjectMapper()

    private fun encode(value: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    // ---- MimeParts --------------------------------------------------------

    @Test
    fun `walks a deeply nested tree, which is what real messages look like`() {
        val payload = objectMapper.readTree(
            """
            {
              "mimeType": "multipart/mixed",
              "parts": [
                {
                  "mimeType": "multipart/related",
                  "parts": [
                    {
                      "mimeType": "multipart/alternative",
                      "parts": [
                        {"mimeType":"text/plain","filename":"","body":{"data":"${encode("Plain body")}"}},
                        {"mimeType":"text/html","filename":"","body":{"data":"${encode("<p>HTML body</p>")}"}}
                      ]
                    },
                    {
                      "mimeType": "image/png",
                      "filename": "logo.png",
                      "headers": [
                        {"name":"Content-ID","value":"<logo@example>"},
                        {"name":"Content-Disposition","value":"inline; filename=logo.png"}
                      ],
                      "body": {"size": 1024, "attachmentId": "att-1"}
                    }
                  ]
                },
                {
                  "mimeType": "application/pdf",
                  "filename": "report.pdf",
                  "body": {"size": 2048, "attachmentId": "att-2"}
                }
              ]
            }
            """.trimIndent(),
        )

        val bodies = MimeParts.collect(payload)

        assertThat(bodies.text).isEqualTo("Plain body")
        assertThat(bodies.html).isEqualTo("<p>HTML body</p>")
        assertThat(bodies.attachments.size).isEqualTo(2)

        val inline = bodies.attachments.first { it.filename == "logo.png" }
        assertThat(inline.isInline).isTrue()
        assertThat(inline.contentId).isEqualTo("logo@example") // angle brackets stripped
        assertThat(bodies.attachments.first { it.filename == "report.pdf" }.isInline).isFalse()
    }

    @Test
    fun `returns nothing rather than empty strings when a message has no body`() {
        val bodies = MimeParts.collect(objectMapper.readTree("""{"mimeType":"text/plain"}"""))

        assertThat(bodies.text).isNull()
        assertThat(bodies.html).isNull()
        assertThat(bodies.attachments.size).isEqualTo(0)
    }

    @Test
    fun `undecodable body data degrades to null instead of throwing`() {
        val payload = objectMapper.readTree(
            """{"mimeType":"text/plain","filename":"","body":{"data":"!!!not-base64!!!"}}""",
        )

        assertThat(MimeParts.collect(payload).text).isNull()
    }

    @Test
    fun `parses the date header formats senders actually use`() {
        assertThat(MimeParts.parseDate("Wed, 24 Apr 2024 21:46:40 +0000"))
            .isEqualTo(Instant.parse("2024-04-24T21:46:40Z"))
        assertThat(MimeParts.parseDate("24 Apr 2024 21:46:40 +0000"))
            .isEqualTo(Instant.parse("2024-04-24T21:46:40Z"))
        assertThat(MimeParts.parseDate("Wed, 24 Apr 2024 21:46:40 +0000 (UTC)")).isNotNull()
    }

    @Test
    fun `an unparseable date returns null rather than failing the whole message`() {
        assertThat(MimeParts.parseDate("some time last Tuesday")).isNull()
        assertThat(MimeParts.parseDate("")).isNull()
    }

    // ---- MimeBuilder ------------------------------------------------------

    private val account = MailAccount(email = "ada@example.com", displayName = "Ada Lovelace")

    @Test
    fun `builds a message with the headers a recipient's client needs`() {
        val message = MimeBuilder.toMimeMessage(
            account,
            OutgoingMessage(
                to = listOf(EmailAddress("tom@example.com", "Tom Okafor")),
                cc = listOf(EmailAddress("priya@example.com")),
                subject = "Design review",
                bodyText = "Notes attached.",
                bodyHtml = "<p>Notes attached.</p>",
            ),
        )

        assertThat(message.subject).isEqualTo("Design review")
        assertThat(message.from.first().toString()).contains("ada@example.com")
        assertThat(message.getRecipients(Message.RecipientType.TO).first().toString())
            .contains("tom@example.com")
        assertThat(message.getRecipients(Message.RecipientType.CC).first().toString())
            .contains("priya@example.com")
        assertThat(message.contentType.lowercase()).contains("multipart/alternative")
    }

    @Test
    fun `sets the threading headers so a reply reads as a reply`() {
        val message = MimeBuilder.toMimeMessage(
            account,
            OutgoingMessage(
                to = listOf(EmailAddress("tom@example.com")),
                subject = "Re: Design review",
                bodyText = "Agreed.",
                inReplyToMessageId = "<original@example.com>",
            ),
        )

        assertThat(message.getHeader("In-Reply-To").first()).isEqualTo("<original@example.com>")
        assertThat(message.getHeader("References").first()).isEqualTo("<original@example.com>")
    }

    @Test
    fun `produces bytes that begin with real headers`() {
        val raw = String(
            MimeBuilder.build(
                account,
                OutgoingMessage(
                    to = listOf(EmailAddress("tom@example.com")),
                    subject = "Hello",
                    bodyText = "Hi",
                ),
            ),
        )

        assertThat(raw).contains("From:")
        assertThat(raw).contains("To:")
        assertThat(raw).contains("Subject: Hello")
        assertThat(raw).contains("MIME-Version: 1.0")
    }

    @Test
    fun `encodes a non-ascii subject rather than mangling it`() {
        val message = MimeBuilder.toMimeMessage(
            account,
            OutgoingMessage(
                to = listOf(EmailAddress("tom@example.com")),
                subject = "Réunion à Paris — 日程",
                bodyText = "Bonjour",
            ),
        )

        // Round-trips through the encoded header back to the original string.
        assertThat(message.subject).isEqualTo("Réunion à Paris — 日程")
    }
}
