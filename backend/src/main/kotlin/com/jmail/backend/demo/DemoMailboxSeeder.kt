package com.jmail.backend.demo

import com.jmail.backend.category.CategorizationEngine
import com.jmail.backend.category.ClassificationInput
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.mail.Attachment
import com.jmail.backend.mail.AttachmentRepository
import com.jmail.backend.mail.Folder
import com.jmail.backend.mail.FolderRepository
import com.jmail.backend.mail.FolderType
import com.jmail.backend.mail.Message
import com.jmail.backend.mail.MessageRepository
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.MailAccountRepository
import com.jmail.backend.user.UserAccount
import com.jmail.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Builds a realistic mailbox for the demo sign-in.
 *
 * This is what makes `./run.sh` genuinely usable: a contributor with no Google, Microsoft or
 * Apple credentials still gets a populated inbox that exercises every category, both read
 * states, threads, attachments and the search index.
 *
 * The content is deterministic — same messages, same order, same relative timestamps — so
 * screenshots and tests are reproducible.
 */
@Service
class DemoMailboxSeeder(
    private val properties: JmailProperties,
    private val userRepository: UserRepository,
    private val mailAccountRepository: MailAccountRepository,
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val categorizationEngine: CategorizationEngine,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val isEnabled: Boolean get() = properties.demo.enabled

    /**
     * Returns the demo user, creating and populating the mailbox the first time.
     * Idempotent: signing in as the demo user repeatedly does not duplicate messages.
     */
    @Transactional
    fun provisionDemoUser(): UserAccount {
        val email = properties.demo.email.lowercase()
        val existing = userRepository.findByEmail(email)
        if (existing != null) {
            val account = mailAccountRepository.findByUserIdAndProviderAndProviderAccountId(
                userId = existing.id,
                provider = AccountProvider.DEMO,
                providerAccountId = email,
            )
            if (account != null && messageRepository.countByAccountId(account.id) > 0) {
                return existing
            }
        }

        val now = Instant.now()
        val user = existing ?: userRepository.save(
            UserAccount(
                email = email,
                displayName = properties.demo.displayName,
                createdAt = now,
                updatedAt = now,
                lastLoginAt = now,
            ),
        )

        val account = mailAccountRepository.findByUserIdAndProviderAndProviderAccountId(
            userId = user.id,
            provider = AccountProvider.DEMO,
            providerAccountId = email,
        ) ?: mailAccountRepository.save(
            MailAccount(
                userId = user.id,
                provider = AccountProvider.DEMO,
                providerAccountId = email,
                email = email,
                displayName = properties.demo.displayName,
                isPrimary = true,
                color = "#4F46E5",
                status = AccountStatus.CONNECTED,
                lastSyncAt = now,
            ),
        )

        val folders = seedFolders(account.id, now)
        seedMessages(user.id, account.id, folders, now)

        log.info("Seeded the demo mailbox for {}", email)
        return user
    }

    private fun seedFolders(accountId: UUID, now: Instant): Map<FolderType, Folder> {
        val existing = folderRepository.findAllByAccountIdOrderByPositionAscNameAsc(accountId)
        if (existing.isNotEmpty()) return existing.associateBy(Folder::type)

        val definitions = listOf(
            FolderType.INBOX to "Inbox",
            FolderType.SENT to "Sent",
            FolderType.DRAFTS to "Drafts",
            FolderType.ARCHIVE to "Archive",
            FolderType.SPAM to "Spam",
            FolderType.TRASH to "Trash",
        )

        return definitions.mapIndexed { index, (type, name) ->
            type to folderRepository.save(
                Folder(
                    accountId = accountId,
                    remoteId = type.name.lowercase(),
                    name = name,
                    path = name,
                    type = type,
                    position = index,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.toMap()
    }

    private fun seedMessages(
        userId: UUID,
        accountId: UUID,
        folders: Map<FolderType, Folder>,
        now: Instant,
    ) {
        val inbox = folders.getValue(FolderType.INBOX)
        val sent = folders.getValue(FolderType.SENT)
        val ruleSet = categorizationEngine.ruleSetFor(userId)
        val me = EmailAddress(properties.demo.email.lowercase(), properties.demo.displayName)

        val messages = DemoContent.INBOX.mapIndexed { index, template ->
            val receivedAt = now.minus(template.age)
            val classification = categorizationEngine.classify(
                ruleSet,
                ClassificationInput(
                    fromAddress = template.fromAddress,
                    fromName = template.fromName,
                    subject = template.subject,
                    bodyText = template.body,
                    listId = template.listId,
                    recipients = listOf(me.address),
                    headers = template.headers,
                ),
            )

            Message(
                accountId = accountId,
                folderId = inbox.id,
                categoryId = classification.categoryId,
                categoryConfidence = classification.confidence,
                remoteId = "demo-inbox-$index",
                threadId = template.threadId ?: "demo-thread-$index",
                messageIdHeader = "<demo-$index@jmail.app>",
                listId = template.listId,
                subject = template.subject,
                snippet = template.body.lineSequence().first().take(200),
                bodyText = template.body,
                bodyHtml = DemoContent.toHtml(template.body),
                fromAddress = template.fromAddress,
                fromName = template.fromName,
                toRecipients = listOf(me),
                sentAt = receivedAt,
                receivedAt = receivedAt,
                isRead = template.read,
                isStarred = template.starred,
                isImportant = template.important,
                hasAttachments = template.attachment != null,
                sizeBytes = (template.body.length * 8L) + 2048,
                createdAt = now,
                updatedAt = now,
            )
        }

        val sentMessages = DemoContent.SENT.mapIndexed { index, template ->
            val receivedAt = now.minus(template.age)
            Message(
                accountId = accountId,
                folderId = sent.id,
                remoteId = "demo-sent-$index",
                threadId = template.threadId ?: "demo-sent-thread-$index",
                messageIdHeader = "<demo-sent-$index@jmail.app>",
                subject = template.subject,
                snippet = template.body.lineSequence().first().take(200),
                bodyText = template.body,
                bodyHtml = DemoContent.toHtml(template.body),
                fromAddress = me.address,
                fromName = me.name.orEmpty(),
                toRecipients = listOf(EmailAddress(template.fromAddress, template.fromName)),
                sentAt = receivedAt,
                receivedAt = receivedAt,
                isRead = true,
                createdAt = now,
                updatedAt = now,
            )
        }

        val saved = messageRepository.saveAll(messages + sentMessages)

        // Attachments are seeded as metadata only: the demo mailbox has no bytes to serve,
        // and the UI's attachment affordances are all driven by this metadata.
        val attachments = saved.zip(DemoContent.INBOX + DemoContent.SENT)
            .mapNotNull { (message, template) ->
                template.attachment?.let { attachment ->
                    Attachment(
                        messageId = message.id,
                        filename = attachment.filename,
                        mimeType = attachment.mimeType,
                        sizeBytes = attachment.sizeBytes,
                        createdAt = now,
                    )
                }
            }
        if (attachments.isNotEmpty()) attachmentRepository.saveAll(attachments)

        inbox.totalCount = messages.size
        inbox.unreadCount = messages.count { !it.isRead }
        sent.totalCount = sentMessages.size
        folderRepository.saveAll(listOf(inbox, sent))
    }
}

/** Demo message templates. Kept apart from the seeder so the content reads as content. */
internal object DemoContent {

    data class AttachmentTemplate(val filename: String, val mimeType: String, val sizeBytes: Long)

    data class Template(
        val fromAddress: String,
        val fromName: String,
        val subject: String,
        val body: String,
        val age: Duration,
        val read: Boolean = false,
        val starred: Boolean = false,
        val important: Boolean = false,
        val listId: String? = null,
        val threadId: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val attachment: AttachmentTemplate? = null,
    )

    fun toHtml(body: String): String =
        body.lineSequence()
            .joinToString("\n") { line ->
                if (line.isBlank()) "<p>&nbsp;</p>" else "<p>${line.escapeHtml()}</p>"
            }
            .let { "<div class=\"jmail-body\">$it</div>" }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    val INBOX: List<Template> = listOf(
        Template(
            fromAddress = "priya.raman@northwind.example",
            fromName = "Priya Raman",
            subject = "Re: Thursday's design review",
            body = """
                Thanks for sending the deck through — I read it on the train this morning.

                The two-pane reading layout is a real improvement. My only worry is the
                density toggle: three options felt like one too many when I tried it.

                Shall we cut "spacious" and keep compact/comfortable?

                Priya
            """.trimIndent(),
            age = Duration.ofMinutes(24),
            important = true,
            threadId = "thread-design-review",
        ),
        Template(
            fromAddress = "tom@northwind.example",
            fromName = "Tom Okafor",
            subject = "Re: Thursday's design review",
            body = """
                Agreed on cutting spacious. Compact is what I use on the laptop anyway.

                One more thing — can we get keyboard shortcuts documented somewhere the
                first-time user actually sees them?
            """.trimIndent(),
            age = Duration.ofMinutes(11),
            threadId = "thread-design-review",
        ),
        Template(
            fromAddress = "no-reply@accounts.example",
            fromName = "Account Security",
            subject = "Security alert: new sign-in from a Mac",
            body = """
                We noticed a new sign-in to your account from a Mac in Manchester.

                If this was you, no action is needed. If it wasn't, secure your account now.
            """.trimIndent(),
            age = Duration.ofHours(2),
            important = true,
        ),
        Template(
            fromAddress = "receipts@brightbooks.example",
            fromName = "Bright Books",
            subject = "Your order #48-2291 has shipped",
            body = """
                Good news — your order is on its way and should arrive on Thursday.

                1 × "The Design of Everyday Things" — £18.99
                Delivery — £0.00

                Track your parcel from your account.
            """.trimIndent(),
            age = Duration.ofHours(5),
            attachment = AttachmentTemplate("invoice-48-2291.pdf", "application/pdf", 84_213),
        ),
        Template(
            fromAddress = "statements@meridianbank.example",
            fromName = "Meridian Bank",
            subject = "Your April statement is ready",
            body = """
                Your statement for the period ending 30 April is now available.

                Closing balance: £4,182.55
                Payments in: £3,400.00
                Payments out: £2,911.20
            """.trimIndent(),
            age = Duration.ofHours(9),
            read = true,
            attachment = AttachmentTemplate("statement-april.pdf", "application/pdf", 152_880),
        ),
        Template(
            fromAddress = "bookings@skyward.example",
            fromName = "Skyward Airlines",
            subject = "Your boarding pass for LHR → LIS",
            body = """
                You're checked in. Here is your boarding pass.

                Flight SK 412 — Gate B14 — Boards 07:35
                Seat 14A, window
            """.trimIndent(),
            age = Duration.ofHours(14),
            starred = true,
            attachment = AttachmentTemplate("boarding-pass.pdf", "application/pdf", 41_002),
        ),
        Template(
            fromAddress = "marketing@wanderlust.example",
            fromName = "Wanderlust Travel",
            subject = "48 hours only: 30% off every city break",
            body = """
                Our biggest sale of the season starts now.

                30% off city breaks booked before Sunday. Use code SPRING30 at checkout.
            """.trimIndent(),
            age = Duration.ofHours(20),
            headers = mapOf("list-unsubscribe" to "<mailto:unsubscribe@wanderlust.example>"),
        ),
        Template(
            fromAddress = "notifications@linkedin.com",
            fromName = "LinkedIn",
            subject = "Sam Whitfield sent you a connection request",
            body = """
                Sam Whitfield, Principal Engineer at Lumen, would like to connect.

                You have 4 other invitations waiting.
            """.trimIndent(),
            age = Duration.ofDays(1),
            read = true,
        ),
        Template(
            fromAddress = "kotlin-announce@groups.google.com",
            fromName = "Kotlin Announcements",
            subject = "Re: [kotlin-announce] Multiplatform tooling update",
            body = """
                The tooling update is now stable across all supported targets.

                Full release notes and the migration guide are linked below.
            """.trimIndent(),
            age = Duration.ofDays(1).plusHours(3),
            listId = "kotlin-announce.groups.google.com",
            read = true,
        ),
        Template(
            fromAddress = "hana.lindqvist@northwind.example",
            fromName = "Hana Lindqvist",
            subject = "Notes from the customer call",
            body = """
                Quick summary while it's fresh:

                They love the unified inbox. The thing they asked for twice was a way to
                see everything from one sender across all their accounts.

                I said we'd look at it. No promises made.
            """.trimIndent(),
            age = Duration.ofDays(2),
            starred = true,
        ),
        Template(
            fromAddress = "billing@stripe.com",
            fromName = "Stripe",
            subject = "Your invoice for April is available",
            body = """
                Invoice INV-2291 for £240.00 has been paid.

                Thank you — no action is required.
            """.trimIndent(),
            age = Duration.ofDays(2).plusHours(6),
            read = true,
        ),
        Template(
            fromAddress = "no-reply@calendar.example",
            fromName = "Calendar",
            subject = "Reminder: Design review tomorrow at 10:00",
            body = """
                Design review — tomorrow, 10:00–11:00, Room 3 / video.

                Six people have accepted.
            """.trimIndent(),
            age = Duration.ofDays(3),
            read = true,
        ),
        Template(
            fromAddress = "promo@fitwell.example",
            fromName = "FitWell",
            subject = "Limited time: 50% off your first three months",
            body = """
                Start the season strong — half price for three months when you join today.
            """.trimIndent(),
            age = Duration.ofDays(3).plusHours(8),
            read = true,
            headers = mapOf("list-unsubscribe" to "<mailto:stop@fitwell.example>"),
        ),
        Template(
            fromAddress = "order-confirmation@greenleaf.example",
            fromName = "Greenleaf Grocers",
            subject = "Order confirmation — delivery Saturday 09:00–10:00",
            body = """
                Thanks for your order. We'll deliver on Saturday between 09:00 and 10:00.

                Total: £62.40 for 23 items.
            """.trimIndent(),
            age = Duration.ofDays(4),
            read = true,
        ),
        Template(
            fromAddress = "daniel.acosta@lumen.example",
            fromName = "Daniel Acosta",
            subject = "Coffee next week?",
            body = """
                I'm in town Tuesday and Wednesday. Any chance of a coffee?

                Would be good to hear how the new client is going.
            """.trimIndent(),
            age = Duration.ofDays(5),
        ),
        Template(
            fromAddress = "digest@devweekly.example",
            fromName = "Dev Weekly",
            subject = "Issue #412: the state of desktop apps",
            body = """
                This week: native versus web on the desktop, a deep dive into incremental
                compilation, and the tooling everyone quietly switched to.
            """.trimIndent(),
            age = Duration.ofDays(6),
            read = true,
            listId = "devweekly.example",
        ),
        Template(
            fromAddress = "reservations@thecopperpot.example",
            fromName = "The Copper Pot",
            subject = "Your reservation is confirmed for Friday 19:30",
            body = """
                Table for four, Friday at 19:30. We've noted the nut allergy.
            """.trimIndent(),
            age = Duration.ofDays(7),
            read = true,
        ),
        Template(
            fromAddress = "no-reply@parcelforce.example",
            fromName = "Parcel Tracking",
            subject = "Your parcel was delivered",
            body = """
                Your parcel was delivered and left in the porch at 14:22.
            """.trimIndent(),
            age = Duration.ofDays(8),
            read = true,
        ),
    )

    val SENT: List<Template> = listOf(
        Template(
            fromAddress = "priya.raman@northwind.example",
            fromName = "Priya Raman",
            subject = "Re: Thursday's design review",
            body = """
                Deck attached. The reading pane changes start on slide 6.

                Happy to cut "spacious" if it tests badly — I only added it for the
                large-type accessibility case.
            """.trimIndent(),
            age = Duration.ofHours(3),
            threadId = "thread-design-review",
            attachment = AttachmentTemplate("design-review.pdf", "application/pdf", 2_411_009),
        ),
        Template(
            fromAddress = "daniel.acosta@lumen.example",
            fromName = "Daniel Acosta",
            subject = "Re: Coffee next week?",
            body = """
                Tuesday works. 15:00 at the usual place?
            """.trimIndent(),
            age = Duration.ofDays(4),
        ),
    )
}
