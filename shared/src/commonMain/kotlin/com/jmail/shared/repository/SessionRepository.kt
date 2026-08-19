package com.jmail.shared.repository

import com.jmail.shared.model.AuthTokens
import com.jmail.shared.model.ProviderSummary
import com.jmail.shared.model.UpdatePreferencesRequest
import com.jmail.shared.model.User
import com.jmail.shared.network.ApiError
import com.jmail.shared.network.ApiResult
import com.jmail.shared.network.JMailApiClient
import com.jmail.shared.network.TokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the app is in its sign-in lifecycle. */
sealed interface SessionState {

    /** Deciding whether a stored token is still good; the splash screen state. */
    data object Restoring : SessionState

    data class SignedOut(val reason: String? = null) : SessionState

    data class SignedIn(val user: User) : SessionState
}

/**
 * Owns the signed-in session: restoring it on launch, every way of establishing one, and
 * ending it.
 *
 * Session state is exposed as a [StateFlow] so the UI can render it declaratively and so a
 * token expiring mid-session propagates to every screen at once rather than surfacing as an
 * error on whichever screen happens to make the next request.
 */
class SessionRepository(
    private val apiClient: JMailApiClient,
    private val tokenStorage: TokenStorage,
) {

    private val state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val sessionState: StateFlow<SessionState> = state.asStateFlow()

    val currentUser: User? get() = (state.value as? SessionState.SignedIn)?.user

    /**
     * Called on launch. A stored token that the server rejects signs the user out quietly —
     * an expired session is a normal end state, not an error worth showing.
     */
    suspend fun restore() {
        if (tokenStorage.readAccessToken() == null && tokenStorage.readRefreshToken() == null) {
            state.value = SessionState.SignedOut()
            return
        }

        when (val result = apiClient.currentUser()) {
            is ApiResult.Success -> state.value = SessionState.SignedIn(result.value)
            is ApiResult.Failure -> {
                if (result.error.kind == ApiError.Kind.NETWORK) {
                    // Offline with a stored token: stay on the splash and let the caller
                    // retry rather than throwing away a session that is probably still valid.
                    state.value = SessionState.SignedOut("Could not reach JMail. Check your connection.")
                } else {
                    tokenStorage.clear()
                    state.value = SessionState.SignedOut()
                }
            }
        }
    }

    suspend fun availableProviders(): ApiResult<List<ProviderSummary>> = apiClient.providers()

    /** Returns the URL the client should open in a browser for an OAuth sign-in. */
    suspend fun beginOAuthSignIn(provider: String, target: String): ApiResult<String> =
        apiClient.startAuthorization(provider, target).map { it.authorizationUrl }

    /** Completes an OAuth sign-in with the handoff code carried back by the redirect. */
    suspend fun completeOAuthSignIn(handoffCode: String): ApiResult<User> =
        apiClient.exchangeHandoff(handoffCode).map(::adopt)

    suspend fun signInAsDemo(): ApiResult<User> = apiClient.signInAsDemo().map(::adopt)

    suspend fun updatePreferences(request: UpdatePreferencesRequest): ApiResult<User> =
        apiClient.updatePreferences(request).onSuccess { user ->
            state.value = SessionState.SignedIn(user)
        }

    suspend fun refreshUser(): ApiResult<User> = apiClient.currentUser().onSuccess { user ->
        state.value = SessionState.SignedIn(user)
    }

    suspend fun unlinkAccount(accountId: String): ApiResult<Unit> =
        apiClient.unlinkAccount(accountId).also { result ->
            if (result.isSuccess) refreshUser()
        }

    suspend fun signOut(allSessions: Boolean = false) {
        apiClient.logout(allSessions)
        state.value = SessionState.SignedOut()
    }

    /** Called when the API client discovers the session is gone underneath us. */
    fun onSessionExpired() {
        tokenStorage.clear()
        state.value = SessionState.SignedOut("Your session expired. Sign in again.")
    }

    private fun adopt(tokens: AuthTokens): User {
        state.value = SessionState.SignedIn(tokens.user)
        return tokens.user
    }
}
