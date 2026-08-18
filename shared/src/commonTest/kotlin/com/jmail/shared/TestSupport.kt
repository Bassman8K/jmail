package com.jmail.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import com.jmail.shared.network.InMemoryTokenStorage
import com.jmail.shared.network.JMailApiClient
import com.jmail.shared.network.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Shared test plumbing.
 *
 * Note on timing: Ktor's client executes on its own dispatcher, not the test scheduler, so
 * `advanceUntilIdle()` returns before a request has actually completed. Everything here
 * therefore waits on real conditions with [awaitUntil] rather than on virtual time, and the
 * stores under test are given short, injected timings so the waits stay in milliseconds.
 */

val jsonTestHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

/**
 * Polls [condition] until it holds, on a real dispatcher so that `delay` is real time even
 * inside `runTest`.
 *
 * @throws kotlinx.coroutines.TimeoutCancellationException with the state description when
 *   the condition never becomes true, which makes a failure say what it was waiting for.
 */
suspend fun awaitUntil(
    timeoutMillis: Long = 5_000,
    describe: () -> String = { "condition" },
    condition: () -> Boolean,
) {
    withContext(Dispatchers.Default) {
        try {
            withTimeout(timeoutMillis) {
                while (!condition()) delay(5)
            }
        } catch (failure: Throwable) {
            throw AssertionError("Timed out waiting for ${describe()}", failure)
        }
    }
}

/** Lets in-flight work settle when there is no single condition worth waiting on. */
suspend fun settle(millis: Long = 150) {
    withContext(Dispatchers.Default) { delay(millis) }
}

/**
 * A [JMailApiClient] whose HTTP layer answers from [routes], matched by URL fragment.
 *
 * Routing on a fragment rather than an exact URL keeps the fixtures readable: a test says
 * `"/messages/counts"` and does not restate the host, version prefix and query string.
 */
fun fakeApiClient(
    routes: Map<String, Pair<String, HttpStatusCode>>,
    tokenStorage: TokenStorage = InMemoryTokenStorage().apply { save("access", "refresh") },
    onRequest: (String) -> Unit = {},
): JMailApiClient {
    val engine = MockEngine { request ->
        val path = request.url.encodedPath + "?" + request.url.encodedQuery
        onRequest(path)

        val match = routes.entries.firstOrNull { path.contains(it.key) }
        if (match == null) {
            respond(
                """{"code":"not_found","message":"No stub for $path"}""",
                HttpStatusCode.NotFound,
                jsonTestHeaders,
            )
        } else {
            respond(match.value.first, match.value.second, jsonTestHeaders)
        }
    }

    return JMailApiClient(
        baseUrl = "https://api.test",
        tokenStorage = tokenStorage,
        httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        },
    )
}

/** A page of message summaries, as the API would return it. */
fun messagesPageJson(vararg ids: String, hasMore: Boolean = false, unread: Boolean = true): String {
    val items = ids.joinToString(",") { id ->
        """
        {"id":"$id","accountId":"acc-1","folderId":"fol-1","threadId":"thread-$id",
         "subject":"Subject $id","snippet":"Snippet $id",
         "from":{"address":"sender@example.com","name":"Sender"},
         "to":[],"receivedAt":"2024-04-25T10:00:00Z","isRead":${!unread},"isStarred":false,
         "isImportant":false,"isDraft":false,"hasAttachments":false,
         "categoryId":null,"categoryConfidence":0.0,"labels":[],"sizeBytes":100}
        """.trimIndent()
    }
    return """{"items":[$items],"page":0,"size":50,"totalElements":${ids.size},
               "totalPages":1,"hasMore":$hasMore}"""
}

fun messageDetailJson(id: String = "m1", threadId: String = "thread-m1"): String = """
    {"id":"$id","accountId":"acc-1","folderId":"fol-1","threadId":"$threadId",
     "subject":"Design review","from":{"address":"priya@example.com","name":"Priya Raman"},
     "to":[{"address":"ada@example.com","name":"Ada"}],
     "cc":[{"address":"tom@example.com","name":"Tom"}],"bcc":[],
     "bodyHtml":null,"bodyText":"Body text here","sentAt":"2024-04-25T10:00:00Z",
     "receivedAt":"2024-04-25T10:00:00Z","isRead":false,"isStarred":false,"isImportant":false,
     "isDraft":false,"isArchived":false,"isTrashed":false,"isSpam":false,
     "categoryId":null,"categoryConfidence":0.0,"labels":[],"sizeBytes":100,
     "attachments":[],"hasBlockedImages":false}
""".trimIndent()

fun userJson(email: String = "ada@example.com"): String = """
    {"id":"11111111-1111-4111-8111-111111111111","email":"$email","displayName":"Ada",
     "locale":"en","timezone":"UTC","theme":"SYSTEM","density":"COMFORTABLE","accounts":[]}
""".trimIndent()

fun authTokensJson(access: String = "access-1", refresh: String = "refresh-1"): String = """
    {"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer",
     "expiresIn":1800,"user":${userJson()}}
""".trimIndent()

const val EMPTY_COUNTS_JSON = """{"categories":[],"folders":[],"totalUnread":0}"""
