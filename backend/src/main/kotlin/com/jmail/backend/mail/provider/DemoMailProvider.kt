package com.jmail.backend.mail.provider

import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The demo mailbox.
 *
 * Its messages are seeded straight into the database by DemoMailboxSeeder, so there is no
 * remote system to talk to: sync is a no-op and sends succeed without leaving the machine.
 * Having it implement [MailProvider] rather than being special-cased means the demo account
 * exercises exactly the same service, controller and UI code paths as a real one.
 */
@Component
class DemoMailProvider : MailProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider = AccountProvider.DEMO

    /** Composing is allowed and lands in Sent; nothing is transmitted anywhere. */
    override val supportsSending = true

    override fun listFolders(account: MailAccount): List<RemoteFolder> = emptyList()

    override fun fetchMessages(
        account: MailAccount,
        folder: RemoteFolder,
        since: Instant?,
        cursor: String?,
        limit: Int,
    ): MessagePage = MessagePage(emptyList())

    override fun sendMessage(account: MailAccount, message: OutgoingMessage): String {
        log.info("Demo account {}: pretending to send \"{}\"", account.id, message.subject)
        return "demo-sent-${Instant.now().toEpochMilli()}"
    }

    override fun applyFlags(account: MailAccount, remoteMessageId: String, flags: FlagUpdate) = Unit

    override fun downloadAttachment(
        account: MailAccount,
        remoteMessageId: String,
        remoteAttachmentId: String,
    ): ByteArray? = null
}
