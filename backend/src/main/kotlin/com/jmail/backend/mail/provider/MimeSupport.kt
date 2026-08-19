package com.jmail.backend.mail.provider

import com.fasterxml.jackson.databind.JsonNode
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.user.MailAccount
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties

/** Everything recovered from walking a MIME tree. */
data class CollectedBodies(
    val text: String? = null,
    val html: String? = null,
    val attachments: List<RemoteAttachment> = emptyList(),
)

/**
 * Reads Gmail's JSON representation of a MIME tree.
 *
 * Real messages nest arbitrarily — `multipart/mixed` wrapping `multipart/alternative`
 * wrapping the actual bodies, with inline images alongside — so the tree is walked in full
 * rather than assuming the two-part shape that simple messages happen to have.
 */
object MimeParts {

    fun collect(payload: JsonNode): CollectedBodies {
        val text = StringBuilder()
        val html = StringBuilder()
        val attachments = mutableListOf<RemoteAttachment>()

        walk(payload, text, html, attachments)

        return CollectedBodies(
            text = text.toString().takeIf { it.isNotBlank() },
            html = html.toString().takeIf { it.isNotBlank() },
            attachments = attachments,
        )
    }

    private fun walk(
        node: JsonNode,
        text: StringBuilder,
        html: StringBuilder,
        attachments: MutableList<RemoteAttachment>,
    ) {
        val mimeType = node.path("mimeType").asText("")
        val filename = node.path("filename").asText("")
        val body = node.path("body")

        val headers = node.path("headers").associate { header ->
            header.path("name").asText().lowercase() to header.path("value").asText()
        }

        when {
            filename.isNotBlank() -> attachments += RemoteAttachment(
                remoteId = body.path("attachmentId").asText(null),
                filename = filename,
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                sizeBytes = body.path("size").asLong(0),
                contentId = headers["content-id"]?.trim('<', '>'),
                // An inline part is one the HTML body references by cid: rather than one the
                // reader should see listed as an attachment.
                isInline = headers["content-disposition"]?.startsWith("inline", ignoreCase = true) == true,
            )

            mimeType == "text/plain" -> decode(body)?.let { text.append(it) }
            mimeType == "text/html" -> decode(body)?.let { html.append(it) }
        }

        node.path("parts").forEach { part -> walk(part, text, html, attachments) }
    }

    private fun decode(body: JsonNode): String? {
        val data = body.path("data").asText(null) ?: return null
        return runCatching { String(Base64.getUrlDecoder().decode(data)) }.getOrNull()
    }

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        // Some senders omit the day name, which RFC_1123_DATE_TIME requires.
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z"),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z (zzz)"),
    )

    /** Parses a `Date:` header, falling back to null rather than throwing on odd formats. */
    fun parseDate(raw: String): Instant? {
        val value = raw.trim()
        for (format in DATE_FORMATS) {
            runCatching { return ZonedDateTime.parse(value, format).toInstant() }
        }
        return null
    }
}

/**
 * Builds an RFC 5322 message for providers that accept raw MIME (Gmail's `send`, SMTP).
 *
 * A `multipart/alternative` body is always produced: plain text first, HTML second, which is
 * the ordering readers use to pick the richest part they can render while text-only clients
 * still show something sensible.
 */
object MimeBuilder {

    fun build(account: MailAccount, outgoing: OutgoingMessage): ByteArray =
        ByteArrayOutputStream().use { output ->
            toMimeMessage(account, outgoing).writeTo(output)
            output.toByteArray()
        }

    /**
     * @param session the session the message is built against. It matters: `Transport.send`
     * resolves the transport from the *message's* session, so building against a default
     * one sends to localhost:25 no matter what the account says. Callers that only need the
     * bytes (Gmail's API, which posts raw RFC 822) can leave it defaulted.
     */
    fun toMimeMessage(
        account: MailAccount,
        outgoing: OutgoingMessage,
        session: Session = Session.getInstance(Properties()),
    ): MimeMessage {
        val message = MimeMessage(session)

        message.setFrom(InternetAddress(account.email, account.displayName, CHARSET))
        message.setRecipients(Message.RecipientType.TO, outgoing.to.toAddresses())
        if (outgoing.cc.isNotEmpty()) message.setRecipients(Message.RecipientType.CC, outgoing.cc.toAddresses())
        if (outgoing.bcc.isNotEmpty()) message.setRecipients(Message.RecipientType.BCC, outgoing.bcc.toAddresses())
        message.setSubject(outgoing.subject, CHARSET)
        message.sentDate = java.util.Date()

        // Threading headers: without these, replies start a new conversation in the
        // recipient's client even though they read as a reply.
        outgoing.inReplyToMessageId?.let { parent ->
            message.setHeader("In-Reply-To", parent)
            message.setHeader("References", parent)
        }

        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(
            MimeBodyPart().apply { setText(outgoing.bodyText, CHARSET, "plain") },
        )
        outgoing.bodyHtml?.let { html ->
            alternative.addBodyPart(MimeBodyPart().apply { setText(html, CHARSET, "html") })
        }

        message.setContent(alternative)
        message.saveChanges()
        return message
    }

    private fun List<EmailAddress>.toAddresses(): Array<InternetAddress> =
        map { InternetAddress(it.address, it.name, CHARSET) }.toTypedArray()

    private const val CHARSET = "UTF-8"
}
