package com.jmail.backend.mail.provider

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.mail.FolderType
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

/**
 * Microsoft Graph differs from Gmail in ways that are easy to get subtly wrong: the address
 * lives in a nested object, "starred" is a flag status string rather than a label, and
 * archiving is a move rather than a flag change.
 */
class MicrosoftGraphProviderTest {

    private val tokenService: ProviderTokenService = mockk()
    private lateinit var server: MockRestServiceServer
    private lateinit var provider: MicrosoftGraphProvider

    private val account = MailAccount(
        id = UUID.randomUUID(),
        provider = AccountProvider.MICROSOFT,
        email = "ada@example.com",
    )

    private val inbox = RemoteFolder(remoteId = "inbox-id", name = "Inbox", type = FolderType.INBOX)

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        provider = MicrosoftGraphProvider(builder.build(), tokenService)

        every { tokenService.accessTokenFor(account) } returns "graph-access-token"
    }

    @Test
    fun `maps well-known folder names, including the ones Outlook names differently`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/mailFolders?\$top=100"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {"value":[
                      {"id":"1","displayName":"Inbox","unreadItemCount":4,"totalItemCount":40},
                      {"id":"2","displayName":"Sent Items","unreadItemCount":0,"totalItemCount":12},
                      {"id":"3","displayName":"Deleted Items","unreadItemCount":0,"totalItemCount":3},
                      {"id":"4","displayName":"Junk Email","unreadItemCount":1,"totalItemCount":1},
                      {"id":"5","displayName":"Project Falcon","unreadItemCount":2,"totalItemCount":9}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val folders = provider.listFolders(account)

        assertThat(folders.map(RemoteFolder::type)).containsExactly(
            FolderType.INBOX,
            FolderType.SENT,
            FolderType.TRASH,
            FolderType.SPAM,
            FolderType.CUSTOM,
        )
        assertThat(folders.first().unreadCount).isEqualTo(4)
    }

    @Test
    fun `parses a message including its nested sender and recipients`() {
        val node = ObjectMapper().readTree(
            """
            {
              "id": "AAMkAGI2",
              "conversationId": "AAQkAGI2",
              "subject": "Quarterly review",
              "from": {"emailAddress": {"name": "Priya Raman", "address": "Priya@Example.com"}},
              "toRecipients": [{"emailAddress": {"name": "Ada", "address": "ada@example.com"}}],
              "ccRecipients": [{"emailAddress": {"name": "Tom", "address": "tom@example.com"}}],
              "body": {"contentType": "html", "content": "<p>Agenda attached</p>"},
              "bodyPreview": "Agenda attached",
              "receivedDateTime": "2024-04-24T21:46:40Z",
              "sentDateTime": "2024-04-24T21:46:00Z",
              "isRead": false,
              "isDraft": false,
              "flag": {"flagStatus": "flagged"},
              "hasAttachments": true,
              "internetMessageId": "<review@example.com>"
            }
            """.trimIndent(),
        )

        val message = provider.toRemoteMessage(node, inbox)

        assertThat(message.remoteId).isEqualTo("AAMkAGI2")
        assertThat(message.threadId).isEqualTo("AAQkAGI2")
        assertThat(message.from.address).isEqualTo("priya@example.com") // canonicalised
        assertThat(message.from.name).isEqualTo("Priya Raman")
        assertThat(message.to.map { it.address }).containsExactly("ada@example.com")
        assertThat(message.cc.map { it.address }).containsExactly("tom@example.com")
        assertThat(message.bodyHtml).isEqualTo("<p>Agenda attached</p>")
        // Graph's bodyPreview stands in for plain text when the body is HTML, which is what
        // gives the list snippet and the search index something to work with.
        assertThat(message.bodyText).isEqualTo("Agenda attached")
        assertThat(message.isRead).isFalse()
        assertThat(message.isStarred).isTrue() // flagStatus, not a label
        assertThat(message.receivedAt).isEqualTo(Instant.parse("2024-04-24T21:46:40Z"))
        assertThat(message.attachments.size).isEqualTo(1)
    }

    @Test
    fun `treats a plain text body as text rather than html`() {
        val node = ObjectMapper().readTree(
            """
            {"id":"1","conversationId":"1","subject":"Plain",
             "from":{"emailAddress":{"address":"a@b.example"}},
             "body":{"contentType":"text","content":"Just text"},
             "receivedDateTime":"2024-04-24T21:46:40Z"}
            """.trimIndent(),
        )

        val message = provider.toRemoteMessage(node, inbox)

        assertThat(message.bodyText).isEqualTo("Just text")
        assertThat(message.bodyHtml).isNull()
    }

    @Test
    fun `falls back to the message id when there is no conversation`() {
        val node = ObjectMapper().readTree(
            """
            {"id":"only-id","subject":"No thread",
             "from":{"emailAddress":{"address":"a@b.example"}},
             "receivedDateTime":"2024-04-24T21:46:40Z"}
            """.trimIndent(),
        )

        assertThat(provider.toRemoteMessage(node, inbox).threadId).isEqualTo("only-id")
    }

    @Test
    fun `a missing timestamp does not break the mapping`() {
        val node = ObjectMapper().readTree(
            """{"id":"1","subject":"x","from":{"emailAddress":{"address":"a@b.example"}}}""",
        )

        assertThat(provider.toRemoteMessage(node, inbox).receivedAt).isNotNull()
    }

    @Test
    fun `read and flag changes are a PATCH`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc"))
            .andExpect(method(HttpMethod.PATCH))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        provider.applyFlags(account, "abc", FlagUpdate(isRead = true, isStarred = true))

        server.verify()
    }

    @Test
    fun `archiving is a move, because Graph has no archive flag`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc/move"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        provider.applyFlags(account, "abc", FlagUpdate(isArchived = true))

        server.verify()
    }

    @Test
    fun `sending posts to sendMail and keeps a copy in Sent Items`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/sendMail"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.ACCEPTED))

        val id = provider.sendMessage(
            account,
            OutgoingMessage(
                to = listOf(com.jmail.backend.common.EmailAddress("tom@example.com")),
                subject = "Hello",
                bodyText = "Hi Tom",
            ),
        )

        assertThat(id).contains("graph-sent")
        server.verify()
    }

    @Test
    fun `a revoked grant asks for reconnection`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/mailFolders?\$top=100"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN))

        assertThrows<ReauthenticationRequiredException> { provider.listFolders(account) }
    }

    // ---- listing a page ----------------------------------------------------

    private fun graphMessage(id: String, subject: String) = """
        {"id":"$id","conversationId":"c-$id","subject":"$subject",
         "receivedDateTime":"2026-01-02T03:04:05Z","sentDateTime":"2026-01-02T03:04:00Z",
         "isRead":false,"flag":{"flagStatus":"notFlagged"},"hasAttachments":false,
         "from":{"emailAddress":{"address":"grace@example.com","name":"Grace"}},
         "toRecipients":[{"emailAddress":{"address":"ada@example.com"}}],
         "body":{"contentType":"text","content":"Body of $id"}}
    """.trimIndent()

    @Test
    fun `a page is listed in one request, unlike Gmail`() {
        // Graph returns whole messages in the listing, so a page is one round trip. The
        // $select list is what keeps it that way; dropping `body` silently empties every
        // message, which reads as a sync bug rather than a query one.
        var requestedUri: String? = null
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/mailFolders/inbox-id/messages")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer graph-access-token"))
            .andExpect { request -> requestedUri = request.uri.toString() }
            .andRespond(
                withSuccess(
                    """{"value":[${graphMessage("m1", "First")},${graphMessage("m2", "Second")}],
                        "@odata.nextLink":"https://graph.microsoft.com/next"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val page = provider.fetchMessages(account, inbox, limit = 2)

        assertThat(page.messages.map { it.subject }).containsExactly("First", "Second")
        assertThat(page.nextCursor).isEqualTo("https://graph.microsoft.com/next")
        assertThat(requestedUri!!).contains("\$top=2")
        assertThat(requestedUri!!).contains("body")
        // A single %20, not %2520: the URI is pre-encoded and must not be encoded again,
        // or $orderby arrives as literal "receivedDateTime%20desc" and Graph rejects it.
        assertThat(requestedUri!!).contains("\$orderby=receivedDateTime%20desc")
        server.verify()
    }

    @Test
    fun `an incremental sync filters on the timestamp rather than re-reading everything`() {
        var requestedUri: String? = null
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages")))
            .andExpect { request -> requestedUri = request.uri.toString() }
            .andRespond(withSuccess("""{"value":[]}""", MediaType.APPLICATION_JSON))

        provider.fetchMessages(account, inbox, since = Instant.parse("2026-01-01T00:00:00Z"), limit = 5)

        assertThat(requestedUri!!).contains("2026-01-01T00:00:00Z")
        server.verify()
    }

    @Test
    fun `a cursor is followed verbatim, because Graph signs its own next link`() {
        // Rebuilding the query instead of following @odata.nextLink loses the skip token
        // and re-reads page one forever.
        server.expect(requestTo("https://graph.microsoft.com/next-page-token"))
            .andRespond(withSuccess("""{"value":[]}""", MediaType.APPLICATION_JSON))

        val page = provider.fetchMessages(
            account,
            inbox,
            cursor = "https://graph.microsoft.com/next-page-token",
            limit = 10,
        )

        assertThat(page.messages).isEmpty()
        assertThat(page.nextCursor).isNull()
        server.verify()
    }

    @Test
    fun `one unreadable message does not stall the page`() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/messages?")))
            .andRespond(
                withSuccess(
                    """{"value":[${graphMessage("m1", "Fine")},{"id":"broken","body":12345}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val page = provider.fetchMessages(account, inbox, limit = 2)

        assertThat(page.messages.map { it.subject }).contains("Fine")
    }

    // ---- flags and moves ---------------------------------------------------

    @Test
    fun `un-archiving moves the message back to the inbox`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc/move"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("inbox")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        provider.applyFlags(account, "abc", FlagUpdate(isArchived = false))

        server.verify()
    }

    @Test
    fun `trashing and un-junking are both moves, to different folders`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc/move"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("deleteditems")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc/move"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("inbox")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        provider.applyFlags(account, "abc", FlagUpdate(isTrashed = true))
        provider.applyFlags(account, "abc", FlagUpdate(isSpam = false))

        server.verify()
    }

    @Test
    fun `marking as junk is a move to the junk folder`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc/move"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("junkemail")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        provider.applyFlags(account, "abc", FlagUpdate(isSpam = true))

        server.verify()
    }

    @Test
    fun `un-starring sets the flag status rather than clearing the field`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/abc"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("notFlagged")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        provider.applyFlags(account, "abc", FlagUpdate(isStarred = false))

        server.verify()
    }

    @Test
    fun `nothing to change means no request at all`() {
        provider.applyFlags(account, "abc", FlagUpdate())

        server.verify()
    }

    // ---- attachments -------------------------------------------------------

    @Test
    fun `an attachment is standard-base64 decoded, unlike Gmail's url-safe form`() {
        val content = "spreadsheet bytes"
        server.expect(
            requestTo("https://graph.microsoft.com/v1.0/me/messages/m1/attachments/a1"),
        ).andRespond(
            withSuccess(
                """{"contentBytes":"${java.util.Base64.getEncoder().encodeToString(content.toByteArray())}"}""",
                MediaType.APPLICATION_JSON,
            ),
        )

        assertThat(provider.downloadAttachment(account, "m1", "a1")?.decodeToString()).isEqualTo(content)
    }

    @Test
    fun `an attachment with no content is null rather than an empty file`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/m1/attachments/a1"))
            .andRespond(withSuccess("""{"name":"empty.txt"}""", MediaType.APPLICATION_JSON))

        assertThat(provider.downloadAttachment(account, "m1", "a1")).isNull()
    }

    @Test
    fun `undecodable attachment content is null rather than a crash`() {
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/messages/m1/attachments/a1"))
            .andRespond(withSuccess("""{"contentBytes":"not!valid!base64"}""", MediaType.APPLICATION_JSON))

        assertThat(provider.downloadAttachment(account, "m1", "a1")).isNull()
    }

    @Test
    fun `graph reports itself as able to send`() {
        assertThat(provider.supportsSending).isTrue()
    }
}
