package com.jmail.shared

import com.jmail.shared.network.JMailApiClient
import com.jmail.shared.network.TokenStorage
import com.jmail.shared.network.createTokenStorage
import com.jmail.shared.repository.MailRepository
import com.jmail.shared.repository.SessionRepository
import com.jmail.shared.state.ComposeStore
import com.jmail.shared.state.MailboxStore
import com.jmail.shared.state.ReaderStore
import com.jmail.shared.state.SignInStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Wires the object graph by hand.
 *
 * A dependency-injection framework would buy very little here — the graph is a dozen objects
 * deep and entirely known at compile time — and would cost a multiplatform-compatible
 * runtime, annotation processing on four targets, and a layer of indirection between a
 * crash and its cause.
 */
class JMailContainer(
    baseUrl: String,
    /** Opens a URL in the platform's browser; supplied per platform. */
    val openUrl: (String) -> Unit,
    /**
     * How this build receives the OAuth callback.
     *
     * "WEB" means the provider redirects back to a URL in this browser; "APP" means it
     * redirects to the `jmail://` scheme the installed app registered. Getting this wrong
     * strands the user: a browser cannot open `jmail://`, and a desktop build has no page
     * to be redirected to.
     */
    private val clientTarget: String = "APP",
    tokenStorage: TokenStorage = createTokenStorage(),
    /** Injected in tests to control coroutine execution. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {

    val apiClient: JMailApiClient = JMailApiClient(
        baseUrl = baseUrl.trimEnd('/'),
        tokenStorage = tokenStorage,
        onSessionExpired = { sessionRepository.onSessionExpired() },
    )

    val sessionRepository: SessionRepository = SessionRepository(apiClient, tokenStorage)

    val mailRepository: MailRepository = MailRepository(apiClient)

    fun createSignInStore(scope: CoroutineScope = applicationScope): SignInStore =
        SignInStore(sessionRepository, scope, openUrl, clientTarget)

    fun createMailboxStore(scope: CoroutineScope = applicationScope): MailboxStore =
        MailboxStore(mailRepository, scope)

    fun createReaderStore(scope: CoroutineScope = applicationScope): ReaderStore =
        ReaderStore(mailRepository, scope)

    fun createComposeStore(scope: CoroutineScope = applicationScope): ComposeStore =
        ComposeStore(mailRepository, scope)

    /** Releases the application scope. Desktop calls this on window close. */
    fun dispose() {
        applicationScope.cancel()
    }

    companion object {
        /**
         * Where the client looks for the backend by default.
         *
         * The browser build talks to the same origin it was served from, because nginx
         * proxies `/api` to the backend — which also means no CORS preflight in production.
         */
        const val DEFAULT_BASE_URL = "http://localhost:8090"
    }
}
