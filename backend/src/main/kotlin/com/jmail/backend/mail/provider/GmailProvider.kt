package com.jmail.backend.mail.provider

import com.fasterxml.jackson.databind.JsonNode
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.ProviderException
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.mail.FolderType
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.Base64

/**
 * Gmail, over the Gmail REST API v1.
 *
 * Gmail's model is labels rather than folders, and a message can carry several at once.
 * JMail maps the system labels onto its own folder vocabulary and keeps the remaining
 * labels on the message, so a Gmail user's organisation survives the round trip.
 */
@Component
class GmailProvider(
    private val restClient: RestClient,
    private val tokenService: ProviderTokenService,
) : MailProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider = AccountProvider.GOOGLE

    override fun listFolders(account: MailAccount): List<RemoteFolder> {
        val response = get(account, "$BASE_URL/users/me/labels")

        return response.path("labels")
            .filter { label -> label.path("labelListVisibility").asText("labelShow") != "labelHide" }
            .map { label ->
                val name = label.path("name").asText()
                RemoteFolder(
                    remoteId = label.path("id").asText(),
                    name = name.substringAfterLast('/'),
                    path = name,
                    type = systemLabelType(label.path("id").asText()) ?: FolderType.fromRemoteName(name),
                )
            }
    }

    override fun fetchMessages(
        account: MailAccount,
        folder: RemoteFolder,
        since: Instant?,
        cursor: String?,
        limit: Int,
    ): MessagePage {
        val uri = StringBuilder("$BASE_URL/users/me/messages?maxResults=$limit&labelIds=${folder.remoteId}")
        cursor?.let { uri.append("&pageToken=").append(it) }
        // Gmail's `after:` takes whole seconds; asking for one second earlier avoids losing a
        // message that arrived within the same second as the previous run's high-water mark.
        since?.let { uri.append("&q=after:").append(it.minusSeconds(1).epochSecond) }

        val listing = get(account, uri.toString())
        val ids = listing.path("messages").map { it.path("id").asText() }

        // Gmail's list endpoint returns identifiers only; each message needs its own fetch.
        // Failures on individual messages are logged and skipped rather than failing the page,
        // so one unreadable message cannot stall an account's sync forever.
        val messages = ids.mapNotNull { id ->
            runCatching { toRemoteMessage(get(account, "$BASE_URL/users/me/messages/$id?format=full"), folder) }
                .onFailure { log.warn("Skipping Gmail message {}: {}", id, it.message) }
                .getOrNull()
        }

        return MessagePage(messages, listing.path("nextPageToken").asText(null))
    }

    override fun sendMessage(account: MailAccount, message: OutgoingMessage): String {
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(MimeBuilder.build(account, message))

        val body = mutableMapOf<String, Any>("raw" to raw)
        message.threadRemoteId?.let { body["threadId"] = it }

        val response = post(account, "$BASE_URL/users/me/messages/send", body)
        return response.path("id").asText()
    }

    override fun applyFlags(account: MailAccount, remoteMessageId: String, flags: FlagUpdate) {
        val add = mutableListOf<String>()
        val remove = mutableListOf<String>()

        flags.isRead?.let { if (it) remove += "UNREAD" else add += "UNREAD" }
        flags.isStarred?.let { if (it) add += "STARRED" else remove += "STARRED" }
        flags.isSpam?.let { if (it) add += "SPAM" else remove += "SPAM" }
        flags.isTrashed?.let { if (it) add += "TRASH" else remove += "TRASH" }
        // Archiving in Gmail means removing INBOX, not moving anything.
        flags.isArchived?.let { if (it) remove += "INBOX" else add += "INBOX" }

        if (add.isEmpty() && remove.isEmpty()) return

        post(
            account,
            "$BASE_URL/users/me/messages/$remoteMessageId/modify",
            mapOf("addLabelIds" to add, "removeLabelIds" to remove),
        )
    }

    override fun downloadAttachment(
        account: MailAccount,
        remoteMessageId: String,
        remoteAttachmentId: String,
    ): ByteArray? {
        val response = get(
            account,
            "$BASE_URL/users/me/messages/$remoteMessageId/attachments/$remoteAttachmentId",
        )
        val data = response.path("data").asText(null) ?: return null
        return Base64.getUrlDecoder().decode(data)
    }

    // ---- mapping ----------------------------------------------------------

    internal fun toRemoteMessage(node: JsonNode, folder: RemoteFolder): RemoteMessage {
        val payload = node.path("payload")
        val headers = payload.path("headers").associate { header ->
            header.path("name").asText().lowercase() to header.path("value").asText()
        }

        val labels = node.path("labelIds").map { it.asText() }
        val bodies = MimeParts.collect(payload)
        val internalDate = node.path("internalDate").asLong(0L)
        val receivedAt = if (internalDate > 0) Instant.ofEpochMilli(internalDate) else Instant.now()

        val from = EmailAddresses.parse(headers["from"].orEmpty())
            ?: EmailAddress("unknown@invalid", headers["from"])

        return RemoteMessage(
            remoteId = node.path("id").asText(),
            threadId = node.path("threadId").asText(node.path("id").asText()),
            folderRemoteId = folder.remoteId,
            subject = headers["subject"].orEmpty(),
            from = from,
            to = EmailAddresses.parseList(headers["to"]),
            cc = EmailAddresses.parseList(headers["cc"]),
            bcc = EmailAddresses.parseList(headers["bcc"]),
            replyTo = headers["reply-to"],
            bodyHtml = bodies.html,
            bodyText = bodies.text,
            sentAt = headers["date"]?.let(MimeParts::parseDate) ?: receivedAt,
            receivedAt = receivedAt,
            isRead = "UNREAD" !in labels,
            isStarred = "STARRED" in labels,
            isDraft = "DRAFT" in labels,
            sizeBytes = node.path("sizeEstimate").asLong(0),
            messageIdHeader = headers["message-id"],
            inReplyTo = headers["in-reply-to"],
            listId = headers["list-id"],
            labels = labels.filterNot { it in SYSTEM_LABELS },
            headers = headers,
            attachments = bodies.attachments,
        )
    }

    private fun systemLabelType(labelId: String): FolderType? = when (labelId) {
        "INBOX" -> FolderType.INBOX
        "SENT" -> FolderType.SENT
        "DRAFT" -> FolderType.DRAFTS
        "TRASH" -> FolderType.TRASH
        "SPAM" -> FolderType.SPAM
        else -> null
    }

    // ---- transport --------------------------------------------------------

    private fun get(account: MailAccount, uri: String): JsonNode =
        execute(account) {
            restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenService.accessTokenFor(account)}")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, response -> raise(response.statusCode) }
                .body(JsonNode::class.java)
        }

    private fun post(account: MailAccount, uri: String, body: Any): JsonNode =
        execute(account) {
            restClient.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenService.accessTokenFor(account)}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, response -> raise(response.statusCode) }
                .body(JsonNode::class.java)
        }

    private fun execute(account: MailAccount, call: () -> JsonNode?): JsonNode =
        runCatching(call).getOrElse { failure ->
            when (failure) {
                is ReauthenticationRequiredException -> throw failure
                is ProviderException -> throw failure
                else -> throw ProviderException("Gmail", "Gmail could not be reached", failure)
            }
        } ?: throw ProviderException("Gmail", "Gmail returned an empty response")

    private fun raise(status: HttpStatusCode): Nothing = when (status.value()) {
        401, 403 -> throw ReauthenticationRequiredException("Google")
        429 -> throw ProviderException("Gmail", "Gmail is rate limiting this account; sync will retry")
        else -> throw ProviderException("Gmail", "Gmail returned ${status.value()}")
    }

    private companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1"
        val SYSTEM_LABELS = setOf(
            "INBOX", "SENT", "DRAFT", "TRASH", "SPAM", "UNREAD", "STARRED", "IMPORTANT",
            "CATEGORY_PERSONAL", "CATEGORY_SOCIAL", "CATEGORY_PROMOTIONS", "CATEGORY_UPDATES",
            "CATEGORY_FORUMS",
        )
    }
}
