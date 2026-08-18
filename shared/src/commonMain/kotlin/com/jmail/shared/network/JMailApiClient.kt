package com.jmail.shared.network

import com.jmail.shared.model.ApiErrorBody
import com.jmail.shared.model.AssignCategoryRequest
import com.jmail.shared.model.AuthTokens
import com.jmail.shared.model.BulkActionResult
import com.jmail.shared.model.Category
import com.jmail.shared.model.ComposeRequest
import com.jmail.shared.model.CreateCategoryRequest
import com.jmail.shared.model.ExchangeSignInRequest
import com.jmail.shared.model.ExchangeSuggestion
import com.jmail.shared.model.HandoffExchangeRequest
import com.jmail.shared.model.LogoutRequest
import com.jmail.shared.model.MailFolder
import com.jmail.shared.model.MailProviderOption
import com.jmail.shared.model.MailThread
import com.jmail.shared.model.MailboxCounts
import com.jmail.shared.model.MessageActionRequest
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.model.Page
import com.jmail.shared.model.ProviderSummary
import com.jmail.shared.model.RefreshTokenRequest
import com.jmail.shared.model.StartAuthorization
import com.jmail.shared.model.SyncResult
import com.jmail.shared.model.UpdateCategoryRequest
import com.jmail.shared.model.UpdatePreferencesRequest
import com.jmail.shared.model.User
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The single point of contact with the JMail backend.
 *
 * Responsibilities kept deliberately narrow: build the request, attach the bearer token,
 * refresh it once when the server says it expired, and turn everything into [ApiResult].
 * No caching, no state — that belongs to the repositories above it.
 */
class JMailApiClient(
    private val baseUrl: String,
    private val tokenStorage: TokenStorage,
    httpClient: HttpClient? = null,
    private val onSessionExpired: () -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true // a backend that adds a field must not break older clients
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: HttpClient = httpClient ?: HttpClient {
        expectSuccess = false // error bodies are parsed, not thrown
        install(ContentNegotiation) { json(this@JMailApiClient.json) }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    /**
     * Serialises token refreshes. Without it, a screen firing five parallel requests on a
     * stale token would perform five refreshes, and four of them would be rejected as
     * re-used tokens — signing the user out for doing nothing wrong.
     */
    private val refreshMutex = Mutex()

    // ---- authentication ---------------------------------------------------

    suspend fun providers(): ApiResult<List<ProviderSummary>> =
        callFor(authenticated = false) { client.get("$baseUrl/api/v1/auth/providers") }

    suspend fun startAuthorization(provider: String, target: String): ApiResult<StartAuthorization> =
        callFor(authenticated = false) {
            client.post("$baseUrl/api/v1/auth/${provider.lowercase()}/start?target=$target")
        }

    suspend fun exchangeHandoff(code: String): ApiResult<AuthTokens> =
        callFor<AuthTokens>(authenticated = false) {
            client.post("$baseUrl/api/v1/auth/exchange") { jsonBody(HandoffExchangeRequest(code)) }
        }.onSuccess(::storeTokens)

    suspend fun signInWithExchangeServer(request: ExchangeSignInRequest): ApiResult<AuthTokens> =
        callFor<AuthTokens>(authenticated = false) {
            client.post("$baseUrl/api/v1/auth/exchange-server/sign-in") { jsonBody(request) }
        }.onSuccess(::storeTokens)

    suspend fun suggestExchangeSettings(email: String): ApiResult<ExchangeSuggestion> =
        callFor(authenticated = false) {
            client.get("$baseUrl/api/v1/auth/exchange-server/suggest?email=$email")
        }

    suspend fun mailProviders(): ApiResult<List<MailProviderOption>> =
        callFor(authenticated = false) { client.get("$baseUrl/api/v1/auth/mail-providers") }

    suspend fun signInAsDemo(): ApiResult<AuthTokens> =
        callFor<AuthTokens>(authenticated = false) { client.post("$baseUrl/api/v1/auth/demo") }
            .onSuccess(::storeTokens)

    suspend fun logout(allSessions: Boolean = false): ApiResult<Unit> {
        val refreshToken = tokenStorage.readRefreshToken()
        val result = callForUnit {
            client.post("$baseUrl/api/v1/auth/logout") {
                attachToken()
                jsonBody(LogoutRequest(refreshToken, allSessions))
            }
        }
        // The local session is cleared regardless: a failed round trip must never leave
        // someone signed in on a device they just signed out of.
        tokenStorage.clear()
        return result
    }

    // ---- user -------------------------------------------------------------

    suspend fun currentUser(): ApiResult<User> = callFor { client.get("$baseUrl/api/v1/users/me") { attachToken() } }

    suspend fun updatePreferences(request: UpdatePreferencesRequest): ApiResult<User> =
        callFor {
            client.patch("$baseUrl/api/v1/users/me") {
                attachToken()
                jsonBody(request)
            }
        }

    suspend fun unlinkAccount(accountId: String): ApiResult<Unit> =
        callForUnit { client.delete("$baseUrl/api/v1/users/me/accounts/$accountId") { attachToken() } }

    // ---- mail -------------------------------------------------------------

    suspend fun messages(
        accountId: String? = null,
        folderId: String? = null,
        folderType: String? = null,
        categoryId: String? = null,
        unreadOnly: Boolean = false,
        starredOnly: Boolean = false,
        withAttachmentsOnly: Boolean = false,
        page: Int = 0,
        size: Int = 50,
    ): ApiResult<Page<MessageSummary>> {
        val query = buildString {
            append("?page=$page&size=$size")
            accountId?.let { append("&accountId=$it") }
            folderId?.let { append("&folderId=$it") }
            folderType?.let { append("&folderType=$it") }
            categoryId?.let { append("&categoryId=$it") }
            if (unreadOnly) append("&unreadOnly=true")
            if (starredOnly) append("&starredOnly=true")
            if (withAttachmentsOnly) append("&withAttachmentsOnly=true")
        }
        return callFor { client.get("$baseUrl/api/v1/messages$query") { attachToken() } }
    }

    suspend fun search(query: String, page: Int = 0, size: Int = 50): ApiResult<Page<MessageSummary>> =
        callFor {
            client.get("$baseUrl/api/v1/messages/search?q=${query.encodeQuery()}&page=$page&size=$size") {
                attachToken()
            }
        }

    suspend fun message(messageId: String, loadRemoteImages: Boolean = false): ApiResult<MessageDetail> =
        callFor {
            client.get("$baseUrl/api/v1/messages/$messageId?loadRemoteImages=$loadRemoteImages") { attachToken() }
        }

    suspend fun thread(threadId: String): ApiResult<MailThread> =
        callFor { client.get("$baseUrl/api/v1/messages/threads/${threadId.encodeQuery()}") { attachToken() } }

    suspend fun counts(): ApiResult<MailboxCounts> =
        callFor { client.get("$baseUrl/api/v1/messages/counts") { attachToken() } }

    suspend fun folders(): ApiResult<List<MailFolder>> =
        callFor { client.get("$baseUrl/api/v1/messages/folders") { attachToken() } }

    suspend fun applyAction(request: MessageActionRequest): ApiResult<BulkActionResult> =
        callFor {
            client.post("$baseUrl/api/v1/messages/actions") {
                attachToken()
                jsonBody(request)
            }
        }

    suspend fun assignCategory(request: AssignCategoryRequest): ApiResult<BulkActionResult> =
        callFor {
            client.post("$baseUrl/api/v1/messages/categorize") {
                attachToken()
                jsonBody(request)
            }
        }

    suspend fun compose(request: ComposeRequest): ApiResult<MessageDetail> =
        callFor {
            client.post("$baseUrl/api/v1/messages") {
                attachToken()
                jsonBody(request)
            }
        }

    suspend fun sync(accountId: String? = null): ApiResult<List<SyncResult>> =
        callFor {
            val suffix = accountId?.let { "?accountId=$it" }.orEmpty()
            client.post("$baseUrl/api/v1/messages/sync$suffix") { attachToken() }
        }

    // ---- categories -------------------------------------------------------

    suspend fun categories(): ApiResult<List<Category>> =
        callFor { client.get("$baseUrl/api/v1/categories") { attachToken() } }

    suspend fun createCategory(request: CreateCategoryRequest): ApiResult<Category> =
        callFor {
            client.post("$baseUrl/api/v1/categories") {
                attachToken()
                jsonBody(request)
            }
        }

    suspend fun updateCategory(categoryId: String, request: UpdateCategoryRequest): ApiResult<Category> =
        callFor {
            client.patch("$baseUrl/api/v1/categories/$categoryId") {
                attachToken()
                jsonBody(request)
            }
        }

    suspend fun deleteCategory(categoryId: String): ApiResult<Unit> =
        callForUnit { client.delete("$baseUrl/api/v1/categories/$categoryId") { attachToken() } }

    // ---- plumbing ---------------------------------------------------------

    /** Sets the JSON content type alongside the body so serialisation never depends on
     *  how the injected [HttpClient] happens to be configured. */
    private inline fun <reified T : Any> HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(value)
    }

    private fun HttpRequestBuilder.attachToken() {
        tokenStorage.readAccessToken()?.let { token ->
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private fun storeTokens(tokens: AuthTokens) {
        tokenStorage.save(tokens.accessToken, tokens.refreshToken)
    }

    private suspend inline fun <reified T> callFor(
        authenticated: Boolean = true,
        noinline call: suspend () -> HttpResponse,
    ): ApiResult<T> = execute(authenticated, call) { response -> response.body<T>() }

    private suspend fun callForUnit(
        call: suspend () -> HttpResponse,
    ): ApiResult<Unit> = execute(authenticated = true, call = call) { }

    /**
     * Performs the call, retrying once after a token refresh when the server reports the
     * access token has expired. One retry only: if the refreshed token is also rejected,
     * the session is genuinely gone and the user is signed out.
     */
    private suspend fun <T> execute(
        authenticated: Boolean,
        call: suspend () -> HttpResponse,
        parse: suspend (HttpResponse) -> T,
    ): ApiResult<T> {
        val first = runCatching { call() }.getOrElse { return ApiResult.Failure(ApiError.network(it)) }

        if (first.status.isSuccess()) {
            return runCatching { ApiResult.Success(parse(first)) }
                .getOrElse { ApiResult.Failure(ApiError.unexpected(it)) }
        }

        if (first.status.value == UNAUTHORIZED && authenticated) {
            if (!refreshSession()) {
                onSessionExpired()
                return ApiResult.Failure(ApiError.fromResponse(UNAUTHORIZED, errorBodyOf(first)))
            }

            val retried = runCatching { call() }.getOrElse { return ApiResult.Failure(ApiError.network(it)) }
            if (retried.status.isSuccess()) {
                return runCatching { ApiResult.Success(parse(retried)) }
                    .getOrElse { ApiResult.Failure(ApiError.unexpected(it)) }
            }
            if (retried.status.value == UNAUTHORIZED) onSessionExpired()
            return ApiResult.Failure(ApiError.fromResponse(retried.status.value, errorBodyOf(retried)))
        }

        return ApiResult.Failure(ApiError.fromResponse(first.status.value, errorBodyOf(first)))
    }

    /** @return true when a new access token was obtained. */
    private suspend fun refreshSession(): Boolean = refreshMutex.withLock {
        val refreshToken = tokenStorage.readRefreshToken() ?: return false

        val response = runCatching {
            client.post("$baseUrl/api/v1/auth/refresh") { jsonBody(RefreshTokenRequest(refreshToken)) }
        }.getOrElse { return false }

        if (!response.status.isSuccess()) {
            tokenStorage.clear()
            return false
        }

        return runCatching {
            storeTokens(response.body<AuthTokens>())
            true
        }.getOrDefault(false)
    }

    private suspend fun errorBodyOf(response: HttpResponse): ApiErrorBody? = runCatching {
        json.decodeFromString(ApiErrorBody.serializer(), response.bodyAsText())
    }.getOrNull()

    private fun String.encodeQuery(): String = this
        .replace("%", "%25")
        .replace(" ", "%20")
        .replace("&", "%26")
        .replace("#", "%23")
        .replace("+", "%2B")
        .replace("?", "%3F")

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
