package com.jmail.shared.state

import com.jmail.shared.authTokensJson
import com.jmail.shared.awaitUntil
import com.jmail.shared.fakeApiClient
import com.jmail.shared.messageDetailJson
import com.jmail.shared.model.AccountProvider
import com.jmail.shared.model.ProviderSummary
import com.jmail.shared.model.SignInKind
import com.jmail.shared.network.InMemoryTokenStorage
import com.jmail.shared.repository.MailRepository
import com.jmail.shared.repository.SessionRepository
import com.jmail.shared.repository.SessionState
import com.jmail.shared.settle
import com.jmail.shared.userJson
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInStoreTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // The store's callbacks run on whichever thread the HTTP client dispatched on, so
    // recorded values are replaced atomically rather than appended in place. Iterating a
    // plain MutableList while it grows throws ConcurrentModificationException, which showed
    // up as a spurious "timed out waiting for condition" on a loaded CI machine.
    private val opened = MutableStateFlow(emptyList<String>())
    private val openedUrls: List<String> get() = opened.value

    @AfterTest
    fun tearDown() = scope.cancel()

    private val providersJson = """
        [{"id":"GOOGLE","displayName":"Continue with Google","kind":"OAUTH","icon":"google"},
         {"id":"MICROSOFT","displayName":"Continue with Microsoft","kind":"OAUTH","icon":"microsoft"},
         {"id":"DEMO","displayName":"Explore the demo mailbox","kind":"DEMO","icon":"demo"}]
    """.trimIndent()

    private fun store(
        routes: Map<String, Pair<String, HttpStatusCode>> = mapOf(
            "/auth/providers" to (providersJson to HttpStatusCode.OK),
        ),
        clientTarget: String = "APP",
        onRequest: (String) -> Unit = {},
    ): SignInStore {
        val storage = InMemoryTokenStorage()
        val api = fakeApiClient(routes, tokenStorage = storage, onRequest = onRequest)
        return SignInStore(
            SessionRepository(api, storage),
            scope,
            openUrl = { url -> opened.update { it + url } },
            clientTarget = clientTarget,
        )
    }

    @Test
    fun only_the_methods_the_server_can_complete_are_offered() = runTest {
        val store = store()

        store.start()
        awaitUntil(describe = { "providers to load" }) { store.state.value.providers.isNotEmpty() }

        assertEquals(3, store.state.value.providers.size)
        assertTrue(store.state.value.hasOAuthProviders)
        assertNotNull(store.state.value.demoProvider)
        assertFalse(store.state.value.isLoadingProviders)
    }

    @Test
    fun choosing_an_oauth_provider_opens_the_authorization_url() = runTest {
        val store = store(
            mapOf(
                "/auth/providers" to (providersJson to HttpStatusCode.OK),
                "/start" to (
                    """{"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?x=1",
                        "state":"abc","expiresInSeconds":600}""" to HttpStatusCode.OK
                    ),
            ),
        )
        store.start()
        awaitUntil { store.state.value.providers.isNotEmpty() }

        store.chooseProvider(
            ProviderSummary(AccountProvider.GOOGLE, "Continue with Google", SignInKind.OAUTH, "google"),
        )
        awaitUntil(describe = { "the browser to be opened" }) { openedUrls.isNotEmpty() }

        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?x=1", openedUrls.single())
        assertEquals(SignInStep.AWAITING_PROVIDER, store.state.value.step)
    }

    @Test
    fun a_browser_build_asks_for_a_callback_it_can_actually_receive() = runTest {
        val requests = MutableStateFlow(emptyList<String>())
        val store = store(
            mapOf(
                "/auth/providers" to (providersJson to HttpStatusCode.OK),
                "/start" to (
                    """{"authorizationUrl":"https://accounts.google.com/x","state":"s","expiresInSeconds":600}"""
                        to HttpStatusCode.OK
                    ),
            ),
            clientTarget = "WEB",
            onRequest = { request -> requests.update { it + request } },
        )
        store.start()
        awaitUntil { store.state.value.providers.isNotEmpty() }

        store.chooseProvider(
            ProviderSummary(AccountProvider.GOOGLE, "Continue with Google", SignInKind.OAUTH, "google"),
        )
        awaitUntil(describe = { "the authorization request" }) {
            requests.value.any { it.contains("/start") }
        }

        // Asking for the APP target from a browser strands the user at a jmail:// link the
        // browser cannot open — which is exactly what used to happen.
        val startRequest = requests.value.first { it.contains("/start") }
        assertTrue(startRequest.contains("target=WEB"), startRequest)
    }

    @Test
    fun an_installed_build_asks_for_the_deep_link_callback() = runTest {
        val requests = MutableStateFlow(emptyList<String>())
        val store = store(
            mapOf(
                "/auth/providers" to (providersJson to HttpStatusCode.OK),
                "/start" to (
                    """{"authorizationUrl":"https://accounts.google.com/x","state":"s","expiresInSeconds":600}"""
                        to HttpStatusCode.OK
                    ),
            ),
            clientTarget = "APP",
            onRequest = { request -> requests.update { it + request } },
        )
        store.start()
        awaitUntil { store.state.value.providers.isNotEmpty() }

        store.chooseProvider(
            ProviderSummary(AccountProvider.GOOGLE, "Continue with Google", SignInKind.OAUTH, "google"),
        )
        awaitUntil { requests.value.any { it.contains("/start") } }

        assertTrue(requests.value.first { it.contains("/start") }.contains("target=APP"))
    }

    @Test
    fun cancelling_returns_to_the_provider_list_with_an_explanation() = runTest {
        val store = store()
        store.start()
        awaitUntil { store.state.value.providers.isNotEmpty() }

        store.cancelOAuthSignIn("access_denied")

        assertEquals(SignInStep.CHOOSE_PROVIDER, store.state.value.step)
        assertEquals("access_denied", store.state.value.error?.code)
    }

    @Test
    fun the_demo_provider_signs_in_directly() = runTest {
        val storage = InMemoryTokenStorage()
        val api = fakeApiClient(
            mapOf(
                "/auth/providers" to (providersJson to HttpStatusCode.OK),
                "/auth/demo" to (authTokensJson() to HttpStatusCode.OK),
            ),
            tokenStorage = storage,
        )
        val session = SessionRepository(api, storage)
        val store = SignInStore(session, scope, openUrl = { url -> opened.update { it + url } })

        store.signInAsDemo()
        awaitUntil(describe = { "the demo session" }) { session.sessionState.value is SessionState.SignedIn }

        assertEquals("ada@example.com", session.currentUser?.email)
    }
}

/**
 * The reader loads a message and then its conversation. The behaviour worth pinning is that
 * a fast scroll through the list cannot leave an older response overwriting a newer one.
 */
class ReaderStoreTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private val threadJson = """
        {"threadId":"thread-m1","subject":"Design review","messageCount":2,"unreadCount":1,
         "participants":[{"address":"priya@example.com","name":"Priya"}],
         "messages":[${messageDetailJson("m1")},${messageDetailJson("m2")}]}
    """.trimIndent()

    private fun store(
        routes: Map<String, Pair<String, HttpStatusCode>> = mapOf(
            "/messages/threads/" to (threadJson to HttpStatusCode.OK),
            "/messages/" to (messageDetailJson() to HttpStatusCode.OK),
        ),
    ) = ReaderStore(MailRepository(fakeApiClient(routes)), scope)

    @Test
    fun opening_a_message_loads_it_and_its_thread() = runTest {
        val store = store()

        store.open("m1")
        awaitUntil(describe = { "the message and thread" }) {
            store.state.value.message != null && store.state.value.thread != null
        }

        val state = store.state.value
        assertEquals("m1", state.messageId)
        assertEquals("Design review", state.message?.subject)
        assertTrue(state.showAsThread)
        // The message you opened starts expanded; the rest of the conversation does not.
        assertTrue(state.expandedMessageIds.contains("m1"))
        assertFalse(state.isLoading)
    }

    @Test
    fun opening_the_same_message_twice_does_not_reload_it() = runTest {
        val store = store()
        store.open("m1")
        awaitUntil { store.state.value.message != null }

        store.open("m1")

        assertFalse(store.state.value.isLoading)
    }

    @Test
    fun a_failed_load_surfaces_an_error_the_reader_can_render() = runTest {
        val store = store(
            mapOf(
                "/messages/" to (
                    """{"code":"message_not_found","message":"Message m9 was not found"}"""
                        to HttpStatusCode.NotFound
                    ),
            ),
        )

        store.open("m9")
        awaitUntil(describe = { "the error" }) { store.state.value.error != null }

        assertEquals("message_not_found", store.state.value.error?.code)
        assertNull(store.state.value.message)
        assertFalse(store.state.value.isLoading)
    }

    @Test
    fun closing_clears_everything() = runTest {
        val store = store()
        store.open("m1")
        awaitUntil { store.state.value.message != null }

        store.close()

        assertFalse(store.state.value.isOpen)
        assertNull(store.state.value.message)
    }

    @Test
    fun thread_messages_can_be_expanded_and_collapsed() = runTest {
        val store = store()
        store.open("m1")
        awaitUntil { store.state.value.thread != null }

        store.toggleExpanded("m2")
        assertTrue(store.state.value.expandedMessageIds.contains("m2"))

        store.toggleExpanded("m2")
        assertFalse(store.state.value.expandedMessageIds.contains("m2"))
    }

    @Test
    fun a_single_message_conversation_is_not_rendered_as_a_thread() = runTest {
        val singleMessageThread = """
            {"threadId":"thread-m1","subject":"Design review","messageCount":1,"unreadCount":0,
             "participants":[],"messages":[${messageDetailJson("m1")}]}
        """.trimIndent()

        val store = store(
            mapOf(
                "/messages/threads/" to (singleMessageThread to HttpStatusCode.OK),
                "/messages/" to (messageDetailJson() to HttpStatusCode.OK),
            ),
        )

        store.open("m1")
        awaitUntil { store.state.value.thread != null }

        assertFalse(store.state.value.showAsThread)
    }
}
