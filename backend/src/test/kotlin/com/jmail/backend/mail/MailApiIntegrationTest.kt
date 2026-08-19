package com.jmail.backend.mail

import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.AbstractIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mailbox API against the seeded demo mailbox and a real database.
 *
 * This is where the pieces meet: Flyway's schema, the seeded messages, the categorisation
 * rules from V2, PostgreSQL full-text search, and the ownership scoping that keeps one
 * user's mail away from another's.
 */
class MailApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var accessToken: String

    @BeforeEach
    fun signIn() {
        val response = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString
        accessToken = objectMapper.readTree(response).path("accessToken").asText()
    }

    /** GET as the signed-in demo user. */
    private fun authGet(path: String) = mockMvc.get(path) {
        header("Authorization", "Bearer $accessToken")
    }

    /** POST as the signed-in demo user, with no body. */
    private fun authPost(path: String) = mockMvc.post(path) {
        header("Authorization", "Bearer $accessToken")
    }

    @Test
    fun `the seeded inbox is returned newest first`() {
        val response = authGet("/api/v1/messages")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items") { isArray() } }
            .andReturn().response.contentAsString

        val items = objectMapper.readTree(response).path("items")
        assertTrue(items.size() > 0, "the demo mailbox should not be empty")

        val timestamps = items.map { it.path("receivedAt").asText() }
        assertEquals(timestamps.sortedDescending(), timestamps, "messages must be newest first")
    }

    @Test
    fun `messages carry the fields the list needs to render`() {
        authGet("/api/v1/messages?size=1")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items[0].id") { exists() } }
            .andExpect { jsonPath("$.items[0].subject") { exists() } }
            .andExpect { jsonPath("$.items[0].snippet") { exists() } }
            .andExpect { jsonPath("$.items[0].from.address") { exists() } }
            .andExpect { jsonPath("$.items[0].receivedAt") { exists() } }
    }

    @Test
    fun `paging is bounded and reports whether more exists`() {
        authGet("/api/v1/messages?page=0&size=2")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items.length()") { value(2) } }
            .andExpect { jsonPath("$.hasMore") { value(true) } }
            .andExpect { jsonPath("$.page") { value(0) } }
    }

    @Test
    fun `an oversized page size is rejected rather than silently honoured`() {
        authGet("/api/v1/messages?size=5000")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `the seeded mail is classified into the built-in categories`() {
        val response = authGet("/api/v1/categories")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val categories = objectMapper.readTree(response)
        val keys = categories.map { it.path("key").asText() }

        assertTrue(keys.contains("primary"), keys.toString())
        assertTrue(keys.contains("promotions"), keys.toString())
        assertTrue(keys.contains("receipts"), keys.toString())

        // The seeded mailbox deliberately includes travel, finance and promotional mail, so
        // a working classifier must have filed something outside Primary.
        val classifiedOutsidePrimary = categories
            .filter { it.path("key").asText() != "primary" }
            .sumOf { it.path("total").asLong() }
        assertTrue(classifiedOutsidePrimary > 0, "the classifier filed nothing outside Primary")
    }

    @Test
    fun `filtering by category returns only that category's mail`() {
        val categories = objectMapper.readTree(
            authGet("/api/v1/categories").andReturn().response.contentAsString,
        )
        val receipts = categories.first { it.path("key").asText() == "receipts" }
        val categoryId = receipts.path("id").asText()

        val response = authGet("/api/v1/messages?categoryId=$categoryId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        objectMapper.readTree(response).path("items").forEach { message ->
            assertEquals(categoryId, message.path("categoryId").asText())
        }
    }

    @Test
    fun `full-text search finds a message by a word from its body`() {
        authGet("/api/v1/messages/search?q=boarding")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items.length()") { value(org.hamcrest.Matchers.greaterThan(0)) } }
    }

    @Test
    fun `search also matches a partial sender address, which stemming alone would miss`() {
        authGet("/api/v1/messages/search?q=meridian")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.items.length()") { value(org.hamcrest.Matchers.greaterThan(0)) } }
    }

    @Test
    fun `a one-character search is refused with an explanation`() {
        authGet("/api/v1/messages/search?q=a")
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("search_query_too_short") } }
    }

    @Test
    fun `unread filtering matches the badge counts`() {
        val counts = objectMapper.readTree(
            authGet("/api/v1/messages/counts").andReturn().response.contentAsString,
        )
        val totalUnread = counts.path("totalUnread").asLong()

        val unreadPage = objectMapper.readTree(
            authGet("/api/v1/messages?unreadOnly=true&size=200")
                .andReturn().response.contentAsString,
        )

        assertEquals(totalUnread, unreadPage.path("totalElements").asLong())
    }

    @Test
    fun `marking a message read updates it and the counts`() {
        val firstUnread = objectMapper.readTree(
            authGet("/api/v1/messages?unreadOnly=true&size=1")
                .andReturn().response.contentAsString,
        ).path("items").first()

        val messageId = firstUnread.path("id").asText()
        val unreadBefore = objectMapper.readTree(
            authGet("/api/v1/messages/counts").andReturn().response.contentAsString,
        ).path("totalUnread").asLong()

        mockMvc.post("/api/v1/messages/actions") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"messageIds":["$messageId"],"isRead":true}"""
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.affected") { value(1) } }

        authGet("/api/v1/messages/$messageId")
            .andExpect { jsonPath("$.isRead") { value(true) } }

        val unreadAfter = objectMapper.readTree(
            authGet("/api/v1/messages/counts").andReturn().response.contentAsString,
        ).path("totalUnread").asLong()

        assertEquals(unreadBefore - 1, unreadAfter)
    }

    @Test
    fun `a message opens with its body and attachment metadata`() {
        val listed = objectMapper.readTree(
            authGet("/api/v1/messages?withAttachmentsOnly=true&size=1")
                .andReturn().response.contentAsString,
        ).path("items")

        assertTrue(listed.size() > 0, "the demo mailbox should include an attachment")
        val messageId = listed.first().path("id").asText()

        authGet("/api/v1/messages/$messageId")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.bodyText") { exists() } }
            .andExpect { jsonPath("$.attachments[0].filename") { exists() } }
            .andExpect { jsonPath("$.attachments[0].sizeBytes") { exists() } }
    }

    @Test
    fun `a conversation is returned oldest first`() {
        val threadId = objectMapper.readTree(
            authGet("/api/v1/messages?size=50").andReturn().response.contentAsString,
        ).path("items").first { it.path("threadId").asText() == "thread-design-review" }
            .path("threadId").asText()

        val response = authGet("/api/v1/messages/threads/$threadId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val thread = objectMapper.readTree(response)
        assertTrue(thread.path("messageCount").asInt() > 1)

        val received = thread.path("messages").map { it.path("receivedAt").asText() }
        assertEquals(received.sorted(), received, "a thread reads oldest first")
    }

    @Test
    fun `filing a message by hand pins it against reclassification`() {
        val categories = objectMapper.readTree(
            authGet("/api/v1/categories").andReturn().response.contentAsString,
        )
        val travelId = categories.first { it.path("key").asText() == "travel" }.path("id").asText()

        val messageId = objectMapper.readTree(
            authGet("/api/v1/messages?size=1").andReturn().response.contentAsString,
        ).path("items").first().path("id").asText()

        mockMvc.post("/api/v1/messages/categorize") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"messageIds":["$messageId"],"categoryId":"$travelId"}"""
        }.andExpect { status { isOk() } }

        authGet("/api/v1/messages/$messageId")
            .andExpect { jsonPath("$.categoryId") { value(travelId) } }
            .andExpect { jsonPath("$.categoryConfidence") { value(1.0) } }

        // Re-running classification must respect the human decision.
        authPost("/api/v1/categories/reclassify")
            .andExpect { status { isOk() } }

        authGet("/api/v1/messages/$messageId")
            .andExpect { jsonPath("$.categoryId") { value(travelId) } }
    }

    @Test
    fun `sending a message stores it in Sent`() {
        mockMvc.post("/api/v1/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"to":[{"address":"tom@example.com","name":"Tom"}],
                 "subject":"Integration test message","bodyText":"Hello from the test suite."}
            """.trimIndent()
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.subject") { value("Integration test message") } }
            .andExpect { jsonPath("$.isRead") { value(true) } }

        authGet("/api/v1/messages?folderType=SENT")
            .andExpect { status { isOk() } }
            .andExpect {
                jsonPath("$.items[?(@.subject == 'Integration test message')]") {
                    value(org.hamcrest.Matchers.hasSize<Any>(1))
                }
            }
    }

    @Test
    fun `sending without a recipient is refused with a field error`() {
        mockMvc.post("/api/v1/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"to":[],"subject":"No recipients","bodyText":"Hello"}"""
        }
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("validation_failed") } }
    }

    @Test
    fun `a draft is kept out of the inbox`() {
        mockMvc.post("/api/v1/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"to":[{"address":"tom@example.com"}],"subject":"A draft",
                 "bodyText":"Unfinished","saveAsDraft":true}
            """.trimIndent()
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.isDraft") { value(true) } }

        authGet("/api/v1/messages?folderType=DRAFTS")
            .andExpect {
                jsonPath("$.items[?(@.subject == 'A draft')]") {
                    value(org.hamcrest.Matchers.hasSize<Any>(1))
                }
            }
    }

    @Test
    fun `another user's message is not found rather than forbidden`() {
        // A random id must look identical to one belonging to somebody else.
        authGet("/api/v1/messages/00000000-0000-4000-8000-000000000999")
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("message_not_found") } }
    }

    @Test
    fun `every mailbox endpoint refuses an anonymous caller`() {
        listOf(
            "/api/v1/messages",
            "/api/v1/messages/counts",
            "/api/v1/messages/folders",
            "/api/v1/categories",
        ).forEach { path ->
            mockMvc.get(path).andExpect { status { isUnauthorized() } }
        }
    }

    @Test
    fun `folders are exposed for the sidebar`() {
        authGet("/api/v1/messages/folders")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$[?(@.type == 'INBOX')]") { value(org.hamcrest.Matchers.hasSize<Any>(1)) } }
    }

    @Test
    fun `a user can create, use and delete their own category`() {
        val created = mockMvc.post("/api/v1/categories") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Work / Clients","color":"#123456","icon":"work"}"""
        }
            .andExpect { status { isCreated() } }
            .andExpect { jsonPath("$.key") { value("work-clients") } }
            .andExpect { jsonPath("$.isSystem") { value(false) } }
            .andReturn().response.contentAsString

        val categoryId = objectMapper.readTree(created).path("id").asText()

        mockMvc.post("/api/v1/categories/$categoryId/rules") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"field":"SENDER_DOMAIN","operation":"ENDS_WITH","value":"northwind.example","weight":70}"""
        }.andExpect { status { isCreated() } }

        authPost("/api/v1/categories/reclassify")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.reclassified") { value(org.hamcrest.Matchers.greaterThan(0)) } }

        authGet("/api/v1/messages?categoryId=$categoryId")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.totalElements") { value(org.hamcrest.Matchers.greaterThan(0)) } }

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/categories/$categoryId")
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent)
    }

    @Test
    fun `a built-in category cannot be edited or deleted`() {
        val primaryId = objectMapper.readTree(
            authGet("/api/v1/categories").andReturn().response.contentAsString,
        ).first { it.path("key").asText() == "primary" }.path("id").asText()

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/categories/$primaryId")
                .header("Authorization", "Bearer $accessToken"),
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden)
    }

    @Test
    fun `an invalid rule is rejected with a usable message`() {
        val created = objectMapper.readTree(
            mockMvc.post("/api/v1/categories") {
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Regex test"}"""
            }.andReturn().response.contentAsString,
        ).path("id").asText()

        mockMvc.post("/api/v1/categories/$created/rules") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"field":"SUBJECT","operation":"REGEX","value":"invoice [","weight":40}"""
        }
            .andExpect { status { isBadRequest() } }
            .andExpect { jsonPath("$.code") { value("invalid_regex") } }
    }

    // ---- syncing -----------------------------------------------------------

    @Test
    fun `syncing every account reports an outcome for each one`() {
        val response = authPost("/api/v1/messages/sync")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val outcomes = objectMapper.readTree(response)
        assertTrue(outcomes.isArray)
        assertTrue(outcomes.size() >= 1, "the demo user has one account")
        outcomes.forEach { outcome ->
            assertTrue(outcome.path("accountId").asText().isNotBlank())
            assertTrue(outcome.path("status").asText().isNotBlank())
        }
    }

    @Test
    fun `syncing one account by id reports only that one`() {
        val accountId = objectMapper.readTree(
            authGet("/api/v1/users/me").andReturn().response.contentAsString,
        ).path("accounts").first().path("id").asText()

        val response = authPost("/api/v1/messages/sync?accountId=$accountId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val outcomes = objectMapper.readTree(response)
        assertEquals(1, outcomes.size())
        assertEquals(accountId, outcomes.first().path("accountId").asText())
    }

    @Test
    fun `syncing an account that is not yours is a not-found, not someone else's sync`() {
        authPost("/api/v1/messages/sync?accountId=${java.util.UUID.randomUUID()}")
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("account_not_found") } }
    }

    @Test
    fun `reclassifying reports how many messages it looked at`() {
        authPost("/api/v1/categories/reclassify")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.reclassified") { exists() } }
    }

    // ---- attachments -------------------------------------------------------

    @Test
    fun `an attachment with no remote content is a not-found rather than an empty file`() {
        // The demo mailbox is seeded locally, so its attachments have metadata but nothing
        // to fetch from a provider. Returning zero bytes with a 200 would look like a
        // corrupt download; the client needs to be told there is nothing there.
        val message = objectMapper.readTree(
            authGet("/api/v1/messages?withAttachmentsOnly=true&size=1")
                .andReturn().response.contentAsString,
        ).path("items").first()

        val detail = objectMapper.readTree(
            authGet("/api/v1/messages/${message.path("id").asText()}")
                .andReturn().response.contentAsString,
        )
        val attachmentId = detail.path("attachments").first().path("id").asText()

        authGet("/api/v1/messages/${message.path("id").asText()}/attachments/$attachmentId")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `an attachment id from another message is not found`() {
        val messageId = objectMapper.readTree(
            authGet("/api/v1/messages?size=1").andReturn().response.contentAsString,
        ).path("items").first().path("id").asText()

        authGet("/api/v1/messages/$messageId/attachments/${java.util.UUID.randomUUID()}")
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("attachment_not_found") } }
    }

    // ---- composing ---------------------------------------------------------

    @Test
    fun `composing from an account that is not yours is refused`() {
        mockMvc.post("/api/v1/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"accountId":"${java.util.UUID.randomUUID()}",
                 "to":[{"address":"someone@example.com"}],
                 "subject":"Hello","bodyText":"Hi"}
            """.trimIndent()
        }
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("account_not_found") } }
    }

    @Test
    fun `a reply keeps the threading headers that tie it to its conversation`() {
        val original = objectMapper.readTree(
            authGet("/api/v1/messages?size=50").andReturn().response.contentAsString,
        ).path("items").first { it.path("threadId").asText() == "thread-design-review" }

        val response = mockMvc.post("/api/v1/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"to":[{"address":"tom@northwind.example"}],
                 "subject":"Re: Thursday's design review",
                 "bodyText":"Agreed.",
                 "threadId":"${original.path("threadId").asText()}",
                 "inReplyToMessageId":"${original.path("id").asText()}"}
            """.trimIndent()
        }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // The reply has to land in the same conversation, or the thread splits in two.
        assertEquals(
            original.path("threadId").asText(),
            objectMapper.readTree(response).path("threadId").asText(),
        )
    }

    @Test
    fun `html in a composed message is sanitised before it is stored`() {
        val response = mockMvc.post("/api/v1/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"to":[{"address":"someone@example.com"}],
                 "subject":"With markup",
                 "bodyText":"Hello",
                 "bodyHtml":"<p>Hello</p><script>alert(1)</script>"}
            """.trimIndent()
        }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val stored = objectMapper.readTree(response).path("bodyHtml").asText()
        assertTrue(stored.contains("Hello"), stored)
        assertFalse(stored.contains("script"), "a script tag must not survive into storage")
    }

    // ---- categories: editing, rules and ownership --------------------------

    private fun createCategory(name: String): String = objectMapper.readTree(
        mockMvc.post("/api/v1/categories") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        }.andReturn().response.contentAsString,
    ).path("id").asText()

    @Test
    fun `editing a category changes only the fields that were sent`() {
        val id = createCategory("Editable")

        mockMvc.patch("/api/v1/categories/$id") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"  Renamed  ","color":"#123456"}"""
        }
            .andExpect { status { isOk() } }
            // Whitespace a user pasted in must not survive into the sidebar.
            .andExpect { jsonPath("$.name") { value("Renamed") } }
            .andExpect { jsonPath("$.color") { value("#123456") } }
            // Not sent, so not touched.
            .andExpect { jsonPath("$.isEnabled") { value(true) } }
    }

    @Test
    fun `a category can be switched off without being deleted`() {
        val id = createCategory("Mutable")

        mockMvc.patch("/api/v1/categories/$id") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"isEnabled":false,"description":"Off for now","icon":"archive"}"""
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.isEnabled") { value(false) } }
            .andExpect { jsonPath("$.description") { value("Off for now") } }
            .andExpect { jsonPath("$.icon") { value("archive") } }
    }

    @Test
    fun `editing a category that does not exist is a not-found`() {
        mockMvc.patch("/api/v1/categories/${java.util.UUID.randomUUID()}") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Nope"}"""
        }
            .andExpect { status { isNotFound() } }
            .andExpect { jsonPath("$.code") { value("category_not_found") } }
    }

    @Test
    fun `rules can be listed, edited and deleted`() {
        val categoryId = createCategory("With rules")

        val ruleId = objectMapper.readTree(
            mockMvc.post("/api/v1/categories/$categoryId/rules") {
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"field":"SENDER","operation":"CONTAINS","value":"  bank.example  ","weight":50}"""
            }.andReturn().response.contentAsString,
        ).path("id").asText()

        authGet("/api/v1/categories/$categoryId/rules")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$[0].id") { value(ruleId) } }
            // Trimmed on the way in, or the rule silently never matches.
            .andExpect { jsonPath("$[0].value") { value("bank.example") } }

        mockMvc.patch("/api/v1/categories/$categoryId/rules/$ruleId") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"weight":90,"isEnabled":false}"""
        }
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.weight") { value(90) } }
            .andExpect { jsonPath("$.isEnabled") { value(false) } }

        mockMvc.delete("/api/v1/categories/$categoryId/rules/$ruleId") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect { status { isNoContent() } }

        authGet("/api/v1/categories/$categoryId/rules")
            .andExpect { jsonPath("$") { isEmpty() } }
    }

    @Test
    fun `a rule id from another category cannot be deleted through it`() {
        // Otherwise knowing a rule id is enough to delete it from any category you own.
        val first = createCategory("First")
        val second = createCategory("Second")

        val ruleId = objectMapper.readTree(
            mockMvc.post("/api/v1/categories/$first/rules") {
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"field":"SUBJECT","operation":"CONTAINS","value":"invoice","weight":10}"""
            }.andReturn().response.contentAsString,
        ).path("id").asText()

        mockMvc.delete("/api/v1/categories/$second/rules/$ruleId") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect { status { isNotFound() } }

        // Still there, under the category that actually owns it.
        authGet("/api/v1/categories/$first/rules")
            .andExpect { jsonPath("$[0].id") { value(ruleId) } }
    }

    @Test
    fun `the rules of a built-in category can be read but not added to`() {
        val builtInId = objectMapper.readTree(
            authGet("/api/v1/categories").andReturn().response.contentAsString,
        ).first { it.path("isSystem").asBoolean() }.path("id").asText()

        authGet("/api/v1/categories/$builtInId/rules")
            .andExpect { status { isOk() } }

        mockMvc.post("/api/v1/categories/$builtInId/rules") {
            header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"field":"SUBJECT","operation":"CONTAINS","value":"x","weight":10}"""
        }.andExpect { status { isForbidden() } }
    }
}
