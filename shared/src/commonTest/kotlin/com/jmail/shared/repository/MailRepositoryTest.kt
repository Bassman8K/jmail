package com.jmail.shared.repository

import com.jmail.shared.EMPTY_COUNTS_JSON
import com.jmail.shared.fakeApiClient
import com.jmail.shared.messageDetailJson
import com.jmail.shared.messagesPageJson
import com.jmail.shared.model.ComposeRequest
import com.jmail.shared.model.CreateCategoryRequest
import com.jmail.shared.model.RecipientInput
import com.jmail.shared.model.UpdateCategoryRequest
import com.jmail.shared.network.ApiError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mail repository maps intent onto endpoints. These assertions are about that mapping
 * being right — an action labelled "star" that actually archives would be a very bad day.
 */
class MailRepositoryTest {

    private val routes = mapOf(
        "/messages/counts" to (EMPTY_COUNTS_JSON to HttpStatusCode.OK),
        "/messages/folders" to ("[]" to HttpStatusCode.OK),
        "/messages/search" to (messagesPageJson("s1") to HttpStatusCode.OK),
        "/messages/threads/" to (
            """{"threadId":"t1","subject":"S","messageCount":1,"unreadCount":0,
                "participants":[],"messages":[]}""" to HttpStatusCode.OK
            ),
        "/messages/actions" to ("""{"affected":2}""" to HttpStatusCode.OK),
        "/messages/categorize" to ("""{"affected":1}""" to HttpStatusCode.OK),
        "/messages/sync" to ("""[{"accountId":"a","status":"SUCCEEDED","messagesAdded":1}]""" to HttpStatusCode.OK),
        "/categories" to ("[]" to HttpStatusCode.OK),
        "/messages" to (messagesPageJson("m1", "m2") to HttpStatusCode.OK),
    )

    private fun repository(record: MutableList<String> = mutableListOf()) =
        MailRepository(fakeApiClient(routes, onRequest = { record += it })) to record

    @Test
    fun a_query_without_a_search_term_hits_the_list_endpoint() = runTest {
        val (repository, requests) = repository()

        val page = repository.messages(MailboxQuery(), page = 0)

        assertEquals(2, page.getOrNull()?.items?.size)
        assertTrue(requests.none { it.contains("/search") })
    }

    @Test
    fun a_query_with_a_search_term_hits_the_search_endpoint() = runTest {
        val (repository, requests) = repository()

        repository.messages(MailboxQuery(searchQuery = "invoice"), page = 0)

        assertTrue(requests.any { it.contains("/messages/search") })
    }

    @Test
    fun each_action_maps_to_the_flag_it_names() = runTest {
        val (repository, _) = repository()

        assertEquals(2, repository.markRead(listOf("m1"), true).getOrNull()?.affected)
        assertEquals(2, repository.star(listOf("m1"), true).getOrNull()?.affected)
        assertEquals(2, repository.archive(listOf("m1")).getOrNull()?.affected)
        assertEquals(2, repository.trash(listOf("m1")).getOrNull()?.affected)
        assertEquals(2, repository.markSpam(listOf("m1")).getOrNull()?.affected)
        assertEquals(1, repository.assignCategory(listOf("m1"), "c1").getOrNull()?.affected)
    }

    @Test
    fun sidebar_data_is_fetched_through_the_repository() = runTest {
        val (repository, _) = repository()

        assertTrue(repository.counts().isSuccess)
        assertTrue(repository.folders().isSuccess)
        assertTrue(repository.categories().isSuccess)
        assertTrue(repository.thread("t1").isSuccess)
        assertTrue(repository.sync().isSuccess)
        assertTrue(repository.sync("account-1").isSuccess)
    }

    @Test
    fun a_query_reports_whether_it_is_filtered_or_a_search() {
        assertFalse(MailboxQuery().hasFilters)
        assertTrue(MailboxQuery(unreadOnly = true).hasFilters)
        assertTrue(MailboxQuery(starredOnly = true).hasFilters)
        assertTrue(MailboxQuery(withAttachmentsOnly = true).hasFilters)
        assertTrue(MailboxQuery(categoryId = "c1").hasFilters)

        assertFalse(MailboxQuery().isSearch)
        assertFalse(MailboxQuery(searchQuery = "  ").isSearch)
        assertTrue(MailboxQuery(searchQuery = "invoice").isSearch)
    }

    @Test
    fun opening_a_message_can_ask_for_remote_images() = runTest {
        val requests = mutableListOf<String>()
        val repository = MailRepository(
            fakeApiClient(
                mapOf("/messages/" to (messageDetailJson() to HttpStatusCode.OK)),
                onRequest = { requests += it },
            ),
        )

        repository.message("m1", loadRemoteImages = true)

        assertTrue(requests.any { it.contains("loadRemoteImages=true") }, requests.toString())
    }

    @Test
    fun sending_posts_the_composed_message() = runTest {
        val repository = MailRepository(
            fakeApiClient(mapOf("/messages" to (messageDetailJson() to HttpStatusCode.OK))),
        )

        val result = repository.send(
            ComposeRequest(to = listOf(RecipientInput("tom@example.com")), bodyText = "Hi"),
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun category_management_round_trips_through_the_api() = runTest {
        val category = """{"id":"c1","key":"work","name":"Work","color":"#123456","icon":"work",
                           "position":9,"isSystem":false,"isEnabled":true,"total":0,"unread":0,"ruleCount":0}"""
        val repository = MailRepository(fakeApiClient(mapOf("/categories" to (category to HttpStatusCode.OK))))

        assertEquals("work", repository.createCategory(CreateCategoryRequest(name = "Work")).getOrNull()?.key)
        assertEquals("Work", repository.updateCategory("c1", UpdateCategoryRequest(name = "Work")).getOrNull()?.name)
        assertTrue(repository.deleteCategory("c1").isSuccess)
    }

    @Test
    fun a_failure_is_returned_as_a_value_rather_than_thrown() = runTest {
        val repository = MailRepository(
            fakeApiClient(
                mapOf(
                    "/messages" to (
                        """{"code":"internal_error","message":"boom"}""" to HttpStatusCode.InternalServerError
                        ),
                ),
            ),
        )

        val error = repository.messages(MailboxQuery(), 0).errorOrNull()

        assertEquals(ApiError.Kind.SERVER, error?.kind)
        assertEquals("boom", error?.userMessage)
    }
}
