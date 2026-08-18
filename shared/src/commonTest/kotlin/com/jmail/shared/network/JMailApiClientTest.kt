package com.jmail.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API client's job is to turn HTTP into [ApiResult] and to keep a session alive across
 * an expiring access token. The refresh path is the part worth testing hardest: getting it
 * wrong signs people out for no reason, and getting it wrong in the other direction loops
 * forever.
 */
class JMailApiClientTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun client(
        storage: TokenStorage = InMemoryTokenStorage(),
        onSessionExpired: () -> Unit = {},
        handler: MockEngine.Companion.() -> MockEngine,
    ): Pair<JMailApiClient, TokenStorage> {
        val engine = MockEngine.handler()
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }
        return JMailApiClient(
            baseUrl = "https://api.test",
            tokenStorage = storage,
            httpClient = httpClient,
            onSessionExpired = onSessionExpired,
        ) to storage
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun userJson(email: String = "ada@example.com") = """
        {"id":"11111111-1111-4111-8111-111111111111","email":"$email","displayName":"Ada",
         "locale":"en","timezone":"UTC","theme":"SYSTEM","density":"COMFORTABLE","accounts":[]}
    """.trimIndent()

    private fun tokensJson(access: String = "access-1", refresh: String = "refresh-1") = """
        {"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer",
         "expiresIn":1800,"user":${userJson()}}
    """.trimIndent()

    @Test
    fun successful_calls_return_the_parsed_body() = runTest {
        val (api, _) = client {
            MockEngine { respond(userJson(), HttpStatusCode.OK, jsonHeaders) }
        }

        val result = api.currentUser()

        assertTrue(result.isSuccess)
        assertEquals("ada@example.com", result.getOrNull()?.email)
    }

    @Test
    fun sign_in_stores_the_tokens_it_receives() = runTest {
        val (api, storage) = client {
            MockEngine { respond(tokensJson(), HttpStatusCode.OK, jsonHeaders) }
        }

        api.signInAsDemo()

        assertEquals("access-1", storage.readAccessToken())
        assertEquals("refresh-1", storage.readRefreshToken())
    }

    @Test
    fun the_bearer_token_is_attached_to_authenticated_calls() = runTest {
        val storage = InMemoryTokenStorage().apply { save("access-1", "refresh-1") }
        var seenAuthorization: String? = null

        val (api, _) = client(storage) {
            MockEngine { request ->
                seenAuthorization = request.headers[HttpHeaders.Authorization]
                respond(userJson(), HttpStatusCode.OK, jsonHeaders)
            }
        }

        api.currentUser()

        assertEquals("Bearer access-1", seenAuthorization)
    }

    @Test
    fun an_expired_access_token_is_refreshed_and_the_call_retried_once() = runTest {
        val storage = InMemoryTokenStorage().apply { save("stale-token", "refresh-1") }
        var callCount = 0

        val (api, _) = client(storage) {
            MockEngine { request ->
                callCount++
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") ->
                        respond(tokensJson(access = "fresh-token", refresh = "refresh-2"), HttpStatusCode.OK, jsonHeaders)

                    request.headers[HttpHeaders.Authorization] == "Bearer stale-token" ->
                        respond("""{"code":"invalid_token","message":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)

                    else -> respond(userJson(), HttpStatusCode.OK, jsonHeaders)
                }
            }
        }

        val result = api.currentUser()

        assertTrue(result.isSuccess)
        assertEquals(3, callCount) // original, refresh, retry
        assertEquals("fresh-token", storage.readAccessToken())
        assertEquals("refresh-2", storage.readRefreshToken()) // rotation was stored
    }

    @Test
    fun a_failed_refresh_signs_the_user_out_and_does_not_loop() = runTest {
        val storage = InMemoryTokenStorage().apply { save("stale-token", "dead-refresh") }
        var sessionExpiredCalls = 0
        var callCount = 0

        val (api, _) = client(storage, onSessionExpired = { sessionExpiredCalls++ }) {
            MockEngine { request ->
                callCount++
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond("""{"code":"invalid_refresh_token","message":"gone"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                } else {
                    respond("""{"code":"invalid_token","message":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                }
            }
        }

        val result = api.currentUser()

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Kind.UNAUTHENTICATED, result.errorOrNull()?.kind)
        assertEquals(1, sessionExpiredCalls)
        assertEquals(2, callCount) // the original call and one refresh attempt; no loop
        assertNull(storage.readAccessToken()) // local session cleared
    }

    @Test
    fun with_no_refresh_token_a_401_fails_immediately() = runTest {
        var callCount = 0
        val (api, _) = client {
            MockEngine {
                callCount++
                respond("""{"code":"unauthorized","message":"no"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }

        val result = api.currentUser()

        assertTrue(result is ApiResult.Failure)
        assertEquals(1, callCount)
    }

    @Test
    fun server_error_bodies_are_surfaced_with_their_code_and_message() = runTest {
        val (api, _) = client {
            MockEngine {
                respond(
                    """{"code":"message_not_found","message":"Message 42 was not found","details":{"id":"42"}}""",
                    HttpStatusCode.NotFound,
                    jsonHeaders,
                )
            }
        }

        val error = api.message("42").errorOrNull()

        assertEquals("message_not_found", error?.code)
        assertEquals("Message 42 was not found", error?.userMessage)
        assertEquals("42", error?.details?.get("id"))
        assertEquals(ApiError.Kind.CLIENT, error?.kind)
    }

    @Test
    fun a_transport_failure_is_reported_as_a_network_error_not_a_crash() = runTest {
        val (api, _) = client {
            MockEngine { throw kotlinx.io.IOException("connection reset") }
        }

        val error = api.currentUser().errorOrNull()

        assertEquals(ApiError.Kind.NETWORK, error?.kind)
        assertTrue(error?.isRetryable == true)
    }

    @Test
    fun a_malformed_success_body_is_an_error_rather_than_an_exception() = runTest {
        val (api, _) = client {
            MockEngine { respond("this is not json", HttpStatusCode.OK, jsonHeaders) }
        }

        val result = api.currentUser()

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Kind.SERVER, result.errorOrNull()?.kind)
    }

    @Test
    fun logout_clears_the_local_session_even_when_the_server_call_fails() = runTest {
        val storage = InMemoryTokenStorage().apply { save("access-1", "refresh-1") }

        val (api, _) = client(storage) {
            MockEngine { respond("", HttpStatusCode.InternalServerError) }
        }

        api.logout()

        assertNull(storage.readAccessToken())
        assertNull(storage.readRefreshToken())
    }

    @Test
    fun rate_limiting_is_classified_so_the_ui_can_offer_a_retry() = runTest {
        val (api, _) = client {
            MockEngine {
                respond(
                    """{"code":"rate_limited","message":"slow down","details":{"retryAfterSeconds":"30"}}""",
                    HttpStatusCode.TooManyRequests,
                    jsonHeaders,
                )
            }
        }

        val error = api.counts().errorOrNull()

        assertEquals(ApiError.Kind.RATE_LIMITED, error?.kind)
        assertEquals(30L, error?.retryAfterSeconds)
        assertTrue(error?.isRetryable == true)
    }

    @Test
    fun a_revoked_mailbox_is_distinguished_from_a_dead_session() = runTest {
        val storage = InMemoryTokenStorage().apply { save("access-1", "refresh-1") }
        val (api, _) = client(storage) {
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond(tokensJson(), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(
                        """{"code":"reauthentication_required","message":"Reconnect Google","details":{"provider":"Google"}}""",
                        HttpStatusCode.Unauthorized,
                        jsonHeaders,
                    )
                }
            }
        }

        val error = api.sync().errorOrNull()

        // The JMail session is fine; it is the linked mailbox that needs attention.
        assertEquals(ApiError.Kind.REAUTHENTICATION_REQUIRED, error?.kind)
    }

    @Test
    fun list_queries_only_include_the_filters_that_are_set() = runTest {
        var seenUrl = ""
        val (api, _) = client {
            MockEngine { request ->
                seenUrl = request.url.toString()
                respond("""{"items":[],"page":0,"size":50,"totalElements":0,"totalPages":0,"hasMore":false}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        api.messages(categoryId = "cat-1", unreadOnly = true, page = 2, size = 25)

        assertTrue(seenUrl.contains("categoryId=cat-1"), seenUrl)
        assertTrue(seenUrl.contains("unreadOnly=true"), seenUrl)
        assertTrue(seenUrl.contains("page=2"), seenUrl)
        assertTrue(seenUrl.contains("size=25"), seenUrl)
        assertTrue(!seenUrl.contains("starredOnly"), seenUrl) // unset filters are omitted
    }

    @Test
    fun search_terms_are_encoded_so_punctuation_does_not_break_the_query() = runTest {
        var seenUrl = ""
        val (api, _) = client {
            MockEngine { request ->
                seenUrl = request.url.toString()
                respond("""{"items":[],"page":0,"size":50,"totalElements":0,"totalPages":0,"hasMore":false}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        api.search("order #123 & shipping")

        assertTrue(!seenUrl.contains(" "), seenUrl)
        assertTrue(seenUrl.contains("%23") || seenUrl.contains("%2523"), seenUrl)
    }
}
