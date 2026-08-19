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
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Microsoft 365 and Outlook.com, over Microsoft Graph.
 *
 * Graph returns whole message bodies inline in list responses, so unlike Gmail a page of
 * messages costs exactly one request. `$select` is used aggressively — the default
 * projection is large enough that omitting it doubles sync time on a big mailbox.
 */
@Component
class MicrosoftGraphProvider(
    private val restClient: RestClient,
    private val tokenService: ProviderTokenService,
) : MailProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider = AccountProvider.MICROSOFT

    override fun listFolders(account: MailAccount): List<RemoteFolder> {
        val response = get(account, "$BASE_URL/me/mailFolders?\$top=100")

        return response.path("value").map { folder ->
            val name = folder.path("displayName").asText()
            RemoteFolder(
                remoteId = folder.path("id").asText(),
                name = name,
                path = name,
                type = wellKnownType(name),
                unreadCount = folder.path("unreadItemCount").asInt(0),
                totalCount = folder.path("totalItemCount").asInt(0),
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
        // Graph hands back a fully-formed URL for the next page; following it verbatim is
        // both required and cheaper than rebuilding the query.
        val uri = cursor ?: buildString {
            append("$BASE_URL/me/mailFolders/${folder.remoteId}/messages")
            append("?\$top=$limit")
            append("&\$orderby=receivedDateTime%20desc")
            append("&\$select=$SELECTED_FIELDS")
            since?.let { append("&\$filter=receivedDateTime%20ge%20").append(it.toString()) }
        }

        val response = get(account, uri)
        val messages = response.path("value").mapNotNull { node ->
            runCatching { toRemoteMessage(node, folder) }
                .onFailure { log.warn("Skipping Graph message {}: {}", node.path("id").asText(), it.message) }
                .getOrNull()
        }

        return MessagePage(messages, response.path("@odata.nextLink").asText(null))
    }

    override fun sendMessage(account: MailAccount, message: OutgoingMessage): String {
        val payload = mapOf(
            "message" to mapOf(
                "subject" to message.subject,
                "body" to mapOf(
                    "contentType" to if (message.bodyHtml != null) "HTML" else "Text",
                    "content" to (message.bodyHtml ?: message.bodyText),
                ),
                "toRecipients" to message.to.map(::recipient),
                "ccRecipients" to message.cc.map(::recipient),
                "bccRecipients" to message.bcc.map(::recipient),
            ),
            "saveToSentItems" to true,
        )

        post(account, "$BASE_URL/me/sendMail", payload)
        // sendMail returns 202 with no body; Graph assigns the identifier asynchronously.
        return "graph-sent-${Instant.now().toEpochMilli()}"
    }

    override fun applyFlags(account: MailAccount, remoteMessageId: String, flags: FlagUpdate) {
        val changes = mutableMapOf<String, Any>()
        flags.isRead?.let { changes["isRead"] = it }
        flags.isStarred?.let { starred ->
            changes["flag"] = mapOf("flagStatus" to if (starred) "flagged" else "notFlagged")
        }

        if (changes.isNotEmpty()) {
            patch(account, "$BASE_URL/me/messages/$remoteMessageId", changes)
        }

        // Archive, trash and junk are *moves* in Graph, not flags.
        val destination = when {
            flags.isTrashed == true -> "deleteditems"
            flags.isSpam == true -> "junkemail"
            flags.isArchived == true -> "archive"
            flags.isArchived == false || flags.isTrashed == false || flags.isSpam == false -> "inbox"
            else -> null
        }
        destination?.let {
            post(account, "$BASE_URL/me/messages/$remoteMessageId/move", mapOf("destinationId" to it))
        }
    }

    override fun downloadAttachment(
        account: MailAccount,
        remoteMessageId: String,
        remoteAttachmentId: String,
    ): ByteArray? {
        val response = get(account, "$BASE_URL/me/messages/$remoteMessageId/attachments/$remoteAttachmentId")
        val data = response.path("contentBytes").asText(null) ?: return null
        return runCatching { Base64.getDecoder().decode(data) }.getOrNull()
    }

    // ---- mapping ----------------------------------------------------------

    internal fun toRemoteMessage(node: JsonNode, folder: RemoteFolder): RemoteMessage {
        val from = node.path("from").path("emailAddress")
        val bodyType = node.path("body").path("contentType").asText("text")
        val bodyContent = node.path("body").path("content").asText(null)

        val receivedAt = parseTimestamp(node.path("receivedDateTime").asText(null))
        val sentAt = parseTimestamp(node.path("sentDateTime").asText(null)) ?: receivedAt

        return RemoteMessage(
            remoteId = node.path("id").asText(),
            threadId = node.path("conversationId").asText(node.path("id").asText()),
            folderRemoteId = folder.remoteId,
            subject = node.path("subject").asText(""),
            from = EmailAddress(
                address = EmailAddresses.canonical(from.path("address").asText("unknown@invalid")),
                name = from.path("name").asText(null),
            ),
            to = node.path("toRecipients").map(::toEmailAddress),
            cc = node.path("ccRecipients").map(::toEmailAddress),
            bcc = node.path("bccRecipients").map(::toEmailAddress),
            replyTo = node.path("replyTo").firstOrNull()?.path("emailAddress")?.path("address")?.asText(null),
            bodyHtml = bodyContent?.takeIf { bodyType.equals("html", ignoreCase = true) },
            bodyText = bodyContent?.takeIf { !bodyType.equals("html", ignoreCase = true) }
                ?: node.path("bodyPreview").asText(null),
            sentAt = sentAt ?: Instant.now(),
            receivedAt = receivedAt ?: Instant.now(),
            isRead = node.path("isRead").asBoolean(false),
            isStarred = node.path("flag").path("flagStatus").asText("notFlagged") == "flagged",
            isDraft = node.path("isDraft").asBoolean(false),
            sizeBytes = node.path("bodyPreview").asText("").length.toLong(),
            messageIdHeader = node.path("internetMessageId").asText(null),
            listId = null,
            attachments = if (node.path("hasAttachments").asBoolean(false)) {
                // Metadata only; bytes are fetched on demand by downloadAttachment.
                listOf(RemoteAttachment(null, "attachment", "application/octet-stream", 0))
            } else {
                emptyList()
            },
        )
    }

    private fun toEmailAddress(node: JsonNode): EmailAddress {
        val address = node.path("emailAddress")
        return EmailAddress(
            address = EmailAddresses.canonical(address.path("address").asText("")),
            name = address.path("name").asText(null),
        )
    }

    private fun recipient(address: EmailAddress) = mapOf(
        "emailAddress" to mapOf("address" to address.address, "name" to address.name),
    )

    private fun parseTimestamp(value: String?): Instant? =
        value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun wellKnownType(displayName: String): FolderType = when (displayName.lowercase()) {
        "inbox" -> FolderType.INBOX
        "sent items" -> FolderType.SENT
        "drafts" -> FolderType.DRAFTS
        "deleted items" -> FolderType.TRASH
        "junk email" -> FolderType.SPAM
        "archive" -> FolderType.ARCHIVE
        else -> FolderType.fromRemoteName(displayName)
    }

    // ---- transport --------------------------------------------------------

    // `uri(String)` treats its argument as a URI *template* and encodes it, so an already
    // encoded URL is encoded a second time: `%20` becomes `%2520`, and $orderby and $filter
    // reach Graph as literal nonsense. It also corrupts the @odata.nextLink Graph hands back
    // for paging, which must be followed byte for byte. `URI` is passed through untouched.
    private fun get(account: MailAccount, uri: String): JsonNode = execute {
        restClient.get()
            .uri(URI.create(uri))
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenService.accessTokenFor(account)}")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response -> raise(response.statusCode) }
            .body(JsonNode::class.java)
    }

    private fun post(account: MailAccount, uri: String, body: Any): JsonNode? = executeOrNull {
        restClient.post()
            .uri(URI.create(uri))
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenService.accessTokenFor(account)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response -> raise(response.statusCode) }
            .body(JsonNode::class.java)
    }

    private fun patch(account: MailAccount, uri: String, body: Any): JsonNode? = executeOrNull {
        restClient.patch()
            .uri(URI.create(uri))
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenService.accessTokenFor(account)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response -> raise(response.statusCode) }
            .body(JsonNode::class.java)
    }

    private fun execute(call: () -> JsonNode?): JsonNode =
        executeOrNull(call) ?: throw ProviderException("Microsoft", "Microsoft Graph returned an empty response")

    private fun executeOrNull(call: () -> JsonNode?): JsonNode? =
        runCatching(call).getOrElse { failure ->
            when (failure) {
                is ReauthenticationRequiredException, is ProviderException -> throw failure
                else -> throw ProviderException("Microsoft", "Microsoft Graph could not be reached", failure)
            }
        }

    private fun raise(status: HttpStatusCode): Nothing = when (status.value()) {
        401, 403 -> throw ReauthenticationRequiredException("Microsoft")
        429 -> throw ProviderException("Microsoft", "Microsoft Graph is throttling this account; sync will retry")
        else -> throw ProviderException("Microsoft", "Microsoft Graph returned ${status.value()}")
    }

    private companion object {
        const val BASE_URL = "https://graph.microsoft.com/v1.0"
        val SELECTED_FIELDS = listOf(
            "id", "conversationId", "subject", "from", "toRecipients", "ccRecipients", "bccRecipients",
            "replyTo", "body", "bodyPreview", "receivedDateTime", "sentDateTime", "isRead", "isDraft",
            "flag", "hasAttachments", "internetMessageId",
        ).joinToString(",")
    }
}
