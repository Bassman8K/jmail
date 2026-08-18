package com.jmail.backend.mail.provider

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
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
}
