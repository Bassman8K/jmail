package com.jmail.backend.mail.provider

import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.ProviderException
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.mail.FolderType
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Properties

/**
 * Microsoft Exchange (on-premises or hosted) and any other IMAP server.
 *
 * This is the path for mailboxes that have no OAuth endpoint of their own. It speaks plain
 * IMAP for reading and SMTP for sending, which every Exchange deployment supports even when
 * EWS or Graph access is locked down.
 *
 * Connections are opened per operation and closed in a `finally`. IMAP connections are
 * stateful and servers cap them aggressively — a leaked connection means the *next* sync
 * fails with "too many connections", long after the code that leaked it has moved on.
 */
@Component
class ImapMailProvider(
    private val tokenService: ProviderTokenService,
    private val properties: JmailProperties,
) : MailProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider = AccountProvider.EXCHANGE

    override fun listFolders(account: MailAccount): List<RemoteFolder> = withStore(account) { store ->
        store.defaultFolder.list("*")
            .filter { folder -> folder.type and Folder.HOLDS_MESSAGES != 0 }
            .map { folder ->
                RemoteFolder(
                    remoteId = folder.fullName,
                    name = folder.name,
                    path = folder.fullName,
                    type = FolderType.fromRemoteName(folder.fullName),
                )
            }
    }

    override fun fetchMessages(
        account: MailAccount,
        folder: RemoteFolder,
        since: Instant?,
        cursor: String?,
        limit: Int,
    ): MessagePage = withStore(account) { store ->
        val remoteFolder = store.getFolder(folder.remoteId)
        if (!remoteFolder.exists()) return@withStore MessagePage(emptyList())

        remoteFolder.open(Folder.READ_ONLY)
        try {
            val total = remoteFolder.messageCount
            if (total == 0) return@withStore MessagePage(emptyList())

            // IMAP numbers messages from 1 (oldest). The inbox is read newest-first, so the
            // window walks backwards from the end, and the cursor is the next lower bound.
            val upperBound = cursor?.toIntOrNull()?.coerceAtMost(total) ?: total
            val lowerBound = (upperBound - limit + 1).coerceAtLeast(1)
            if (upperBound < 1) return@withStore MessagePage(emptyList())

            val fetched = remoteFolder.getMessages(lowerBound, upperBound)
                .reversed()
                .mapNotNull { message ->
                    runCatching { toRemoteMessage(message as MimeMessage, folder) }
                        .onFailure { log.warn("Skipping IMAP message in {}: {}", folder.path, it.message) }
                        .getOrNull()
                }
                .filter { message -> since == null || message.receivedAt.isAfter(since) }

            val nextCursor = (lowerBound - 1).takeIf { it >= 1 }?.toString()
            MessagePage(fetched, nextCursor)
        } finally {
            runCatching { remoteFolder.close(false) }
        }
    }

    override fun sendMessage(account: MailAccount, message: OutgoingMessage): String {
        val password = tokenService.passwordFor(account)
        val host = account.smtpHost
            ?: throw ProviderException(provider.displayName, "No SMTP server is configured for this account")
        val port = account.smtpPort ?: properties.exchange.defaultSmtpPort

        val sessionProperties = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", properties.exchange.connectTimeout.toMillis().toString())
            put("mail.smtp.timeout", properties.exchange.readTimeout.toMillis().toString())
            // Port 465 is implicit TLS; everything else negotiates with STARTTLS.
            if (port == IMPLICIT_TLS_PORT) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", account.useTls.toString())
            }
        }

        val session = Session.getInstance(sessionProperties)
        val mimeMessage = MimeBuilder.toMimeMessage(account, message)

        return runCatching {
            Transport.send(mimeMessage, account.username ?: account.email, password)
            mimeMessage.messageID ?: "smtp-${Instant.now().toEpochMilli()}"
        }.getOrElse { failure ->
            if (failure is AuthenticationFailedException) {
                throw ReauthenticationRequiredException(provider.displayName)
            }
            throw ProviderException(provider.displayName, "The message could not be sent: ${failure.message}", failure)
        }.also { session.debug = false } // keep credentials out of stdout even if a caller enables debug
    }

    override fun applyFlags(account: MailAccount, remoteMessageId: String, flags: FlagUpdate) {
        // remoteMessageId is "<folder>::<uid>"; see remoteIdOf.
        val folderName = remoteMessageId.substringBefore(ID_SEPARATOR)
        val messageNumber = remoteMessageId.substringAfter(ID_SEPARATOR).toIntOrNull() ?: return

        withStore(account) { store ->
            val folder = store.getFolder(folderName)
            if (!folder.exists()) return@withStore
            folder.open(Folder.READ_WRITE)
            try {
                val message = folder.getMessage(messageNumber)
                flags.isRead?.let { message.setFlag(Flags.Flag.SEEN, it) }
                flags.isStarred?.let { message.setFlag(Flags.Flag.FLAGGED, it) }
                flags.isTrashed?.let { message.setFlag(Flags.Flag.DELETED, it) }
            } finally {
                runCatching { folder.close(true) } // expunge so deletions actually take effect
            }
        }
    }

    override fun downloadAttachment(
        account: MailAccount,
        remoteMessageId: String,
        remoteAttachmentId: String,
    ): ByteArray? {
        val folderName = remoteMessageId.substringBefore(ID_SEPARATOR)
        val messageNumber = remoteMessageId.substringAfter(ID_SEPARATOR).toIntOrNull() ?: return null

        return withStore(account) { store ->
            val folder = store.getFolder(folderName)
            if (!folder.exists()) return@withStore null
            folder.open(Folder.READ_ONLY)
            try {
                val content = folder.getMessage(messageNumber).content
                if (content !is Multipart) return@withStore null

                (0 until content.count)
                    .map(content::getBodyPart)
                    .firstOrNull { part -> part.fileName == remoteAttachmentId }
                    ?.inputStream
                    ?.readBytes()
            } finally {
                runCatching { folder.close(false) }
            }
        }
    }

    // ---- mapping ----------------------------------------------------------

    internal fun toRemoteMessage(message: MimeMessage, folder: RemoteFolder): RemoteMessage {
        // Flags are read *before* the body, because fetching content over IMAP causes the
        // server to set \Seen on the message. Reading them afterwards would mean every
        // message JMail syncs comes back marked as read.
        val isRead = message.isSet(Flags.Flag.SEEN)
        val isStarred = message.isSet(Flags.Flag.FLAGGED)
        val isDraft = message.isSet(Flags.Flag.DRAFT)

        val from = (message.from?.firstOrNull() as? InternetAddress)
        val bodies = extractBodies(message)
        val receivedAt = message.receivedDate?.toInstant() ?: message.sentDate?.toInstant() ?: Instant.now()

        return RemoteMessage(
            remoteId = remoteIdOf(folder.remoteId, message.messageNumber),
            // IMAP has no conversation identifier. References/In-Reply-To reconstruct the
            // thread where present; otherwise the message stands alone.
            threadId = message.getHeader("References")?.firstOrNull()?.trim()
                ?: message.getHeader("In-Reply-To")?.firstOrNull()?.trim()
                ?: message.messageID
                ?: remoteIdOf(folder.remoteId, message.messageNumber),
            folderRemoteId = folder.remoteId,
            subject = message.subject.orEmpty(),
            from = EmailAddress(
                address = EmailAddresses.canonical(from?.address ?: "unknown@invalid"),
                name = from?.personal,
            ),
            to = addressesOf(message, jakarta.mail.Message.RecipientType.TO),
            cc = addressesOf(message, jakarta.mail.Message.RecipientType.CC),
            replyTo = (message.replyTo?.firstOrNull() as? InternetAddress)?.address,
            bodyHtml = bodies.html,
            bodyText = bodies.text,
            sentAt = message.sentDate?.toInstant() ?: receivedAt,
            receivedAt = receivedAt,
            isRead = isRead,
            isStarred = isStarred,
            isDraft = isDraft,
            sizeBytes = message.size.coerceAtLeast(0).toLong(),
            messageIdHeader = message.messageID,
            inReplyTo = message.getHeader("In-Reply-To")?.firstOrNull(),
            listId = message.getHeader("List-Id")?.firstOrNull(),
            headers = listOfNotNull(
                message.getHeader("List-Unsubscribe")?.firstOrNull()?.let { "list-unsubscribe" to it },
                message.getHeader("Precedence")?.firstOrNull()?.let { "precedence" to it },
            ).toMap(),
            attachments = bodies.attachments,
        )
    }

    private fun addressesOf(
        message: MimeMessage,
        type: jakarta.mail.Message.RecipientType,
    ): List<EmailAddress> = message.getRecipients(type)
        ?.filterIsInstance<InternetAddress>()
        ?.map { EmailAddress(EmailAddresses.canonical(it.address), it.personal) }
        ?: emptyList()

    /** Walks the MIME tree, preferring the richest body part and listing attachments. */
    private fun extractBodies(part: Part): CollectedBodies {
        val text = StringBuilder()
        val html = StringBuilder()
        val attachments = mutableListOf<RemoteAttachment>()

        fun walk(current: Part) {
            val disposition = current.disposition
            val filename = runCatching { current.fileName }.getOrNull()

            when {
                filename != null && (disposition == null || !Part.INLINE.equals(disposition, ignoreCase = true)) ||
                    Part.ATTACHMENT.equals(disposition, ignoreCase = true) -> {
                    attachments += RemoteAttachment(
                        remoteId = filename,
                        filename = filename ?: "attachment",
                        mimeType = current.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream",
                        sizeBytes = current.size.coerceAtLeast(0).toLong(),
                        isInline = Part.INLINE.equals(disposition, ignoreCase = true),
                    )
                }

                current.isMimeType("text/plain") -> text.append(current.content?.toString().orEmpty())
                current.isMimeType("text/html") -> html.append(current.content?.toString().orEmpty())

                current.isMimeType("multipart/*") -> {
                    val multipart = current.content as? Multipart ?: return
                    (0 until multipart.count).forEach { index -> walk(multipart.getBodyPart(index)) }
                }
            }
        }

        runCatching { walk(part) }.onFailure { log.debug("Could not fully parse a message body: {}", it.message) }

        return CollectedBodies(
            text = text.toString().takeIf { it.isNotBlank() },
            html = html.toString().takeIf { it.isNotBlank() },
            attachments = attachments,
        )
    }

    /** IMAP identifies a message by folder plus sequence number, so both go in the id. */
    private fun remoteIdOf(folderName: String, messageNumber: Int) = "$folderName$ID_SEPARATOR$messageNumber"

    // ---- transport --------------------------------------------------------

    private fun <T> withStore(account: MailAccount, block: (Store) -> T): T {
        val password = tokenService.passwordFor(account)
        val host = account.imapHost
            ?: throw ProviderException(provider.displayName, "No IMAP server is configured for this account")
        val port = account.imapPort ?: properties.exchange.defaultImapPort
        val protocol = if (account.useTls) "imaps" else "imap"

        val sessionProperties = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", host)
            put("mail.$protocol.port", port.toString())
            put("mail.$protocol.connectiontimeout", properties.exchange.connectTimeout.toMillis().toString())
            put("mail.$protocol.timeout", properties.exchange.readTimeout.toMillis().toString())
            if (account.useTls) put("mail.imaps.ssl.enable", "true") else put("mail.imap.starttls.enable", "true")
        }

        val store = Session.getInstance(sessionProperties).getStore(protocol)
        try {
            store.connect(host, port, account.username ?: account.email, password)
            return block(store)
        } catch (failure: AuthenticationFailedException) {
            throw ReauthenticationRequiredException(provider.displayName)
        } catch (failure: Exception) {
            when (failure) {
                is ProviderException, is ReauthenticationRequiredException -> throw failure
                else -> throw ProviderException(provider.displayName, "$host could not be reached", failure)
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private companion object {
        const val ID_SEPARATOR = "::"
        const val IMPLICIT_TLS_PORT = 465
    }
}
