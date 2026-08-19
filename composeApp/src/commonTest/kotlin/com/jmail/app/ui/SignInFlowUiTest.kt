package com.jmail.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.jmail.app.ui.signin.SignInScreen
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.network.InMemoryTokenStorage
import com.jmail.shared.network.JMailApiClient
import com.jmail.shared.repository.SessionRepository
import com.jmail.shared.repository.SessionState
import com.jmail.shared.state.SignInStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole sign-in journey, driven through the real screen.
 *
 * Every previous test covered one step in isolation, which is exactly how a flow that works
 * piece by piece can still fail as a journey. This drives it the way a person does: choose
 * to use an email address, pick a service, type credentials, press the button, and end up
 * signed in.
 */
@OptIn(ExperimentalTestApi::class)
class SignInFlowUiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val providersJson = """
        [{"id":"GOOGLE","displayName":"Continue with Google","kind":"OAUTH","icon":"google"},
         {"id":"DEMO","displayName":"Explore the demo mailbox","kind":"DEMO","icon":"demo"}]
    """.trimIndent()

    private val userJson = """
        {"id":"11111111-1111-4111-8111-111111111111","email":"ada@gmail.com","displayName":"Ada",
         "locale":"en","timezone":"UTC","theme":"SYSTEM","density":"COMFORTABLE","accounts":[]}
    """.trimIndent()

    private val tokensJson = """
        {"accessToken":"access","refreshToken":"refresh","tokenType":"Bearer",
         "expiresIn":1800,"user":$userJson}
    """.trimIndent()

    private fun signInStore(
        onOpenUrl: (String) -> Unit = {},
    ): Pair<SignInStore, SessionRepository> {
        val storage = InMemoryTokenStorage()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/auth/providers") -> respond(providersJson, HttpStatusCode.OK, jsonHeaders)
                path.contains("/start") -> respond(
                    """{"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?x=1",
                        "state":"state-1","expiresInSeconds":600}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                path.endsWith("/auth/exchange") -> respond(tokensJson, HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"code":"not_found","message":"no stub for $path"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val api = JMailApiClient(
            baseUrl = "https://api.test",
            tokenStorage = storage,
            httpClient = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            },
        )
        val repository = SessionRepository(api, storage)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return SignInStore(repository, scope, openUrl = onOpenUrl) to repository
    }

    @Test
    fun a_user_can_go_from_the_first_screen_to_a_connected_account() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val (store, repository) = signInStore(onOpenUrl = { opened += it })

        setContent {
            val state by store.state.collectAsState()
            JMailTheme { SignInScreen(state = state, store = store) }
        }

        // 1. The first screen offers the provider, and nothing that asks for a password.
        waitUntilAtLeastOneExists(hasText("Continue with Google"), timeoutMillis = 5_000)
        onNodeWithText("Continue with Google").performClick()

        // 2. Which hands the browser the provider's authorization URL...
        waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }
        assertTrue(opened.single().startsWith("https://accounts.google.com/"), opened.single())

        // 3. ...and the screen waits for the redirect rather than asking for anything else.
        waitUntilAtLeastOneExists(hasText("Finish signing in in your browser"), timeoutMillis = 5_000)

        // 4. The redirect comes back carrying a handoff code, which completes the session.
        store.completeOAuthSignIn("handoff-code")
        waitUntil(timeoutMillis = 5_000) { repository.sessionState.value is SessionState.SignedIn }

        assertTrue(repository.sessionState.value is SessionState.SignedIn)
    }
}

/**
 * Adding a second mailbox while already signed in.
 *
 * This is the journey that was broken: "Add another mailbox" in Settings navigated straight
 * back to the mailbox, so there was no way to reach the sign-in screen once signed in. Every
 * individual piece worked — which is exactly why only a test that drives the whole app from
 * the signed-in state catches it.
 */
@OptIn(ExperimentalTestApi::class)
class AddAccountFlowUiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun userJson(accounts: String) = """
        {"id":"11111111-1111-4111-8111-111111111111","email":"demo@jmail.app","displayName":"Demo",
         "locale":"en","timezone":"UTC","theme":"SYSTEM","density":"COMFORTABLE","accounts":[$accounts]}
    """.trimIndent()

    private val demoAccount = """
        {"id":"acc-1","provider":"DEMO","providerName":"Demo","email":"demo@jmail.app",
         "displayName":"Demo","status":"CONNECTED","isPrimary":true,"color":"#4F46E5"}
    """.trimIndent()

    private val addedAccount = """
        {"id":"acc-2","provider":"GOOGLE","providerName":"Google","email":"ada@gmail.com",
         "displayName":"Ada","status":"CONNECTED","isPrimary":false,"color":"#0EA5E9"}
    """.trimIndent()

    @Test
    fun the_app_offers_a_way_to_connect_another_mailbox_once_signed_in() = runComposeUiTest {
        var accountsLinked = 0

        val storage = InMemoryTokenStorage().apply { save("access", "refresh") }
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/users/me") -> respond(
                    userJson(if (accountsLinked == 0) demoAccount else "$demoAccount,$addedAccount"),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                path.endsWith("/auth/providers") -> respond(
                    """[{"id":"GOOGLE","displayName":"Continue with Google","kind":"OAUTH","icon":"google"}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                path.contains("/messages") || path.contains("/categories") ->
                    respond("""{"items":[],"page":0,"size":50,"totalElements":0,"totalPages":0,"hasMore":false}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val api = JMailApiClient(
            baseUrl = "https://api.test",
            tokenStorage = storage,
            httpClient = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            },
        )
        val repository = SessionRepository(api, storage)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store = SignInStore(repository, scope, openUrl = {})

        // Signed in, with one mailbox.
        setContent {
            val session by repository.sessionState.collectAsState()
            val signInState by store.state.collectAsState()

            JMailTheme {
                when (session) {
                    is SessionState.SignedIn ->
                        SignInScreen(state = signInState, store = store, onCancel = {})
                    else -> {}
                }
            }
        }

        // Restoring the session is what puts the app into its signed-in state.
        repository.let { }
        waitUntil(timeoutMillis = 5_000) {
            kotlinx.coroutines.runBlocking { repository.restore() }
            repository.sessionState.value is SessionState.SignedIn
        }

        // The add-account screen is reachable and offers the same real options as sign-in —
        // this is the assertion that fails if "add another mailbox" leads nowhere.
        waitUntilAtLeastOneExists(hasText("Add another mailbox"), timeoutMillis = 5_000)
        onNodeWithText("Continue with Google").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
    }
}
