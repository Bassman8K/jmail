package com.jmail.backend.mail.provider

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.common.EmailAddress
import com.jmail.backend.common.ProviderException
import com.jmail.backend.common.ReauthenticationRequiredException
import com.jmail.backend.mail.FolderType
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import java.util.Base64
import java.util.UUID

/**
 * Gmail's message format is a nested MIME tree encoded as JSON, and getting it wrong shows
 * up as blank message bodies rather than as an error. These tests use the real response
 * shape, including the multipart nesting that simple test fixtures usually skip.
 */
class GmailProviderTest {

    private val tokenService: ProviderTokenService = mockk()
    private lateinit var server: MockRestServiceServer
    private lateinit var provider: GmailProvider

    private val account = MailAccount(
        id = UUID.randomUUID(),
        provider = AccountProvider.GOOGLE,
        email = "ada@example.com",
    )

    private val inboxFolder = RemoteFolder(remoteId = "INBOX", name = "INBOX", type = FolderType.INBOX)

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        provider = GmailProvider(builder.build(), tokenService)

        every { tokenService.accessTokenFor(account) } returns "test-access-token"
    }

    @Test
    fun `maps labels onto JMail's folder vocabulary`() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/labels"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-access-token"))
            .andRespond(
                withSuccess(
                    """
                    {"labels":[
                      {"id":"INBOX","name":"INBOX","labelListVisibility":"labelShow"},
                      {"id":"SENT","name":"SENT","labelListVisibility":"labelShow"},
                      {"id":"TRASH","name":"TRASH","labelListVisibility":"labelShow"},
                      {"id":"Label_9","name":"Work/Clients","labelListVisibility":"labelShow"},
                      {"id":"Label_hidden","name":"Hidden","labelListVisibility":"labelHide"}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val folders = provider.listFolders(account)

        assertThat(folders.map(RemoteFolder::type))
            .containsExactly(FolderType.INBOX, FolderType.SENT, FolderType.TRASH, FolderType.CUSTOM)
        // Nested labels keep their full path but show the leaf as the name.
        assertThat(folders.last().name).isEqualTo("Clients")
        assertThat(folders.last().path).isEqualTo("Work/Clients")
    }

    @Test
    fun `parses a multipart message into text, html and attachments`() {
        val node = ObjectMapper().readTree(multipartMessageJson())

        val message = provider.toRemoteMessage(node, inboxFolder)

        assertThat(message.remoteId).isEqualTo("18c9f1e2a3b4c5d6")
        assertThat(message.threadId).isEqualTo("18c9f1e2a3b4c5d0")
        assertThat(message.subject).isEqualTo("Your order has shipped")
        assertThat(message.from).isEqualTo(EmailAddress("orders@shop.example", "Shop Orders"))
        assertThat(message.to.map(EmailAddress::address)).containsExactly("ada@example.com")
        assertThat(message.bodyText).isNotNull()
        assertThat(message.bodyText!!).contains("Your order is on its way")
        assertThat(message.bodyHtml!!).contains("<p>Your order is on its way</p>")
        assertThat(message.attachments.size).isEqualTo(1)
        assertThat(message.attachments.first().filename).isEqualTo("invoice.pdf")
        assertThat(message.attachments.first().mimeType).isEqualTo("application/pdf")
    }

    @Test
    fun `derives flags from Gmail's labels`() {
        val unread = provider.toRemoteMessage(
            ObjectMapper().readTree(minimalMessageJson(labels = """["INBOX","UNREAD","STARRED"]""")),
            inboxFolder,
        )
        val read = provider.toRemoteMessage(
            ObjectMapper().readTree(minimalMessageJson(labels = """["INBOX"]""")),
            inboxFolder,
        )

        assertThat(unread.isRead).isFalse()
        assertThat(unread.isStarred).isTrue()
        assertThat(read.isRead).isTrue()
        assertThat(read.isStarred).isFalse()
    }

    @Test
    fun `keeps user labels and drops Gmail's own system ones`() {
        val message = provider.toRemoteMessage(
            ObjectMapper().readTree(
                minimalMessageJson(labels = """["INBOX","UNREAD","CATEGORY_PROMOTIONS","Label_9"]"""),
            ),
            inboxFolder,
        )

        assertThat(message.labels).containsExactly("Label_9")
    }

    @Test
    fun `survives a message with no body parts at all`() {
        val message = provider.toRemoteMessage(
            ObjectMapper().readTree(
                """
                {"id":"abc","threadId":"abc","labelIds":["INBOX"],"internalDate":"1714000000000",
                 "payload":{"headers":[{"name":"From","value":"a@b.example"}]}}
                """.trimIndent(),
            ),
            inboxFolder,
        )

        assertThat(message.subject).isEqualTo("")
        assertThat(message.from.address).isEqualTo("a@b.example")
    }

    @Test
    fun `translates a flag change into the right label edits`() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/abc/modify"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        // Archiving in Gmail is the removal of INBOX, not a move.
        provider.applyFlags(account, "abc", FlagUpdate(isRead = true, isArchived = true))

        server.verify()
    }

    @Test
    fun `does not call Gmail when there is nothing to change`() {
        provider.applyFlags(account, "abc", FlagUpdate())

        server.verify() // no requests expected, and none were made
    }

    @Test
    fun `a rejected token asks the user to reconnect rather than reporting a generic failure`() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/labels"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertThrows<ReauthenticationRequiredException> { provider.listFolders(account) }
    }

    @Test
    fun `a server error surfaces as a provider failure`() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/labels"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThrows<ProviderException> { provider.listFolders(account) }
    }

    @Test
    fun `rate limiting is reported as retryable rather than as a broken account`() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/labels"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val failure = assertThrows<ProviderException> { provider.listFolders(account) }

        assertThat(failure.message).contains("rate limiting")
    }

    // ---- fixtures ---------------------------------------------------------

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun multipartMessageJson(): String = """
        {
          "id": "18c9f1e2a3b4c5d6",
          "threadId": "18c9f1e2a3b4c5d0",
          "labelIds": ["INBOX", "UNREAD"],
          "internalDate": "1714000000000",
          "sizeEstimate": 24680,
          "payload": {
            "mimeType": "multipart/mixed",
            "headers": [
              {"name": "From", "value": "Shop Orders <orders@shop.example>"},
              {"name": "To", "value": "ada@example.com"},
              {"name": "Subject", "value": "Your order has shipped"},
              {"name": "Message-ID", "value": "<order-123@shop.example>"},
              {"name": "Date", "value": "Wed, 24 Apr 2024 21:46:40 +0000"}
            ],
            "parts": [
              {
                "mimeType": "multipart/alternative",
                "parts": [
                  {
                    "mimeType": "text/plain",
                    "filename": "",
                    "body": {"size": 42, "data": "${encode("Your order is on its way\n")}"}
                  },
                  {
                    "mimeType": "text/html",
                    "filename": "",
                    "body": {"size": 64, "data": "${encode("<p>Your order is on its way</p>")}"}
                  }
                ]
              },
              {
                "mimeType": "application/pdf",
                "filename": "invoice.pdf",
                "headers": [{"name": "Content-Disposition", "value": "attachment; filename=invoice.pdf"}],
                "body": {"size": 84213, "attachmentId": "ANGjdJ_attachment_id"}
              }
            ]
          }
        }
    """.trimIndent()

    private fun minimalMessageJson(labels: String): String = """
        {
          "id": "abc",
          "threadId": "abc",
          "labelIds": $labels,
          "internalDate": "1714000000000",
          "payload": {
            "mimeType": "text/plain",
            "headers": [
              {"name": "From", "value": "someone@example.com"},
              {"name": "Subject", "value": "Hello"}
            ],
            "body": {"size": 5, "data": "${encode("Hello")}"}
          }
        }
    """.trimIndent()

    // ---- listing a page ----------------------------------------------------

    private fun expectList(query: String, body: String) {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages?$query"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun expectMessage(id: String, body: String) {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun simpleMessage(id: String, subject: String) = """
        {"id":"$id","threadId":"t-$id","labelIds":["INBOX"],
         "payload":{"mimeType":"text/plain",
           "headers":[{"name":"Subject","value":"$subject"},
                      {"name":"From","value":"Grace <grace@example.com>"}],
           "body":{"data":"${Base64.getUrlEncoder().withoutPadding().encodeToString("Body of $id".toByteArray())}"}}}
    """.trimIndent()

    @Test
    fun `a page is listed then fetched message by message`() {
        // Gmail's list endpoint returns identifiers only, so a page of n messages is n+1
        // requests. Getting that wrong shows up as an empty mailbox, not as an error.
        expectList("maxResults=2&labelIds=INBOX", """{"messages":[{"id":"m1"},{"id":"m2"}],"nextPageToken":"page-2"}""")
        expectMessage("m1", simpleMessage("m1", "First"))
        expectMessage("m2", simpleMessage("m2", "Second"))

        val page = provider.fetchMessages(account, inboxFolder, limit = 2)

        assertThat(page.messages.map { it.subject }).containsExactly("First", "Second")
        assertThat(page.nextCursor).isEqualTo("page-2")
        server.verify()
    }

    @Test
    fun `one unreadable message does not stall the whole account`() {
        // A single message Gmail will not return must not stop the sync: left unhandled it
        // fails the same page forever and the account never advances.
        expectList("maxResults=2&labelIds=INBOX", """{"messages":[{"id":"m1"},{"id":"bad"}]}""")
        expectMessage("m1", simpleMessage("m1", "Readable"))
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/bad?format=full"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val page = provider.fetchMessages(account, inboxFolder, limit = 2)

        assertThat(page.messages.map { it.subject }).containsExactly("Readable")
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `an empty folder returns an empty page rather than failing`() {
        expectList("maxResults=10&labelIds=INBOX", """{}""")

        val page = provider.fetchMessages(account, inboxFolder, limit = 10)

        assertThat(page.messages).isEmpty()
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `a cursor is passed through as Gmail's page token`() {
        expectList("maxResults=5&labelIds=INBOX&pageToken=abc", """{"messages":[]}""")

        provider.fetchMessages(account, inboxFolder, cursor = "abc", limit = 5)

        server.verify()
    }

    @Test
    fun `an incremental sync asks for one second before the high-water mark`() {
        // Gmail's after: takes whole seconds, so asking from exactly the last timestamp
        // drops any message that arrived inside the same second.
        val since = java.time.Instant.ofEpochSecond(1_700_000_000)
        expectList("maxResults=5&labelIds=INBOX&q=after:1699999999", """{"messages":[]}""")

        provider.fetchMessages(account, inboxFolder, since = since, limit = 5)

        server.verify()
    }

    // ---- flags -------------------------------------------------------------

    @Test
    fun `each flag maps to the label Gmail actually uses`() {
        val captured = mutableListOf<String>()
        repeat(4) {
            server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/abc/modify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request -> captured += request.body.toString() }
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        }

        provider.applyFlags(account, "abc", FlagUpdate(isRead = false))
        provider.applyFlags(account, "abc", FlagUpdate(isStarred = true))
        provider.applyFlags(account, "abc", FlagUpdate(isSpam = true, isTrashed = false))
        provider.applyFlags(account, "abc", FlagUpdate(isArchived = false))

        // Unread is the *presence* of UNREAD, so marking unread adds rather than removes.
        assertThat(captured[0]).contains(""""addLabelIds":["UNREAD"]""")
        assertThat(captured[1]).contains(""""addLabelIds":["STARRED"]""")
        assertThat(captured[2]).contains("SPAM")
        assertThat(captured[2]).contains("TRASH")
        // Un-archiving puts INBOX back.
        assertThat(captured[3]).contains(""""addLabelIds":["INBOX"]""")
        server.verify()
    }

    // ---- attachments -------------------------------------------------------

    @Test
    fun `an attachment is base64url-decoded`() {
        val content = "id,total\n1,42\n"
        server.expect(
            requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/m1/attachments/a1"),
        ).andRespond(
            withSuccess(
                """{"size":${content.length},"data":"${
                    Base64.getUrlEncoder().withoutPadding().encodeToString(content.toByteArray())
                }"}""",
                MediaType.APPLICATION_JSON,
            ),
        )

        val bytes = provider.downloadAttachment(account, "m1", "a1")

        assertThat(bytes?.decodeToString()).isEqualTo(content)
    }

    @Test
    fun `an attachment Gmail returns without data is null rather than an empty file`() {
        server.expect(
            requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/m1/attachments/a1"),
        ).andRespond(withSuccess("""{"size":0}""", MediaType.APPLICATION_JSON))

        assertThat(provider.downloadAttachment(account, "m1", "a1")).isNull()
    }

    // ---- sending -----------------------------------------------------------

    @Test
    fun `sending posts the message as base64url-encoded RFC 822`() {
        var raw: String? = null
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request -> raw = request.body.toString() }
            .andRespond(withSuccess("""{"id":"sent-1","threadId":"t1"}""", MediaType.APPLICATION_JSON))

        val id = provider.sendMessage(
            account,
            OutgoingMessage(
                to = listOf(EmailAddress("someone@example.com")),
                subject = "Hello",
                bodyText = "Hi there",
            ),
        )

        assertThat(id).isEqualTo("sent-1")
        val encoded = ObjectMapper().readTree(raw).path("raw").asText()
        val decoded = Base64.getUrlDecoder().decode(encoded).decodeToString()
        // Gmail rejects standard base64: + and / are not valid in the raw field.
        assertThat(encoded.contains('+') || encoded.contains('/')).isFalse()
        assertThat(decoded).contains("someone@example.com")
        assertThat(decoded).contains("Hello")
    }

    @Test
    fun `gmail reports itself as able to send`() {
        assertThat(provider.supportsSending).isTrue()
    }
}
