package com.jmail.shared.repository

import com.jmail.shared.authTokensJson
import com.jmail.shared.fakeApiClient
import com.jmail.shared.model.UiTheme
import com.jmail.shared.model.UpdatePreferencesRequest
import com.jmail.shared.network.InMemoryTokenStorage
import com.jmail.shared.userJson
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session lifecycle: restoring on launch, telling "offline" apart from "signed out",
 * and clearing local state on the way out.
 */
class SessionRepositoryTest {

    @Test
    fun restoring_with_no_stored_token_lands_signed_out() = runTest {
        val storage = InMemoryTokenStorage()
        val repository = SessionRepository(fakeApiClient(emptyMap(), tokenStorage = storage), storage)

        repository.restore()

        assertTrue(repository.sessionState.value is SessionState.SignedOut)
        assertNull(repository.currentUser)
    }

    @Test
    fun restoring_with_a_valid_token_signs_the_user_in() = runTest {
        val storage = InMemoryTokenStorage().apply { save("access", "refresh") }
        val repository = SessionRepository(
            fakeApiClient(mapOf("/users/me" to (userJson() to HttpStatusCode.OK)), tokenStorage = storage),
            storage,
        )

        repository.restore()

        val state = repository.sessionState.value
        assertTrue(state is SessionState.SignedIn)
        assertEquals("ada@example.com", state.user.email)
        assertNotNull(repository.currentUser)
    }

    @Test
    fun a_rejected_token_is_discarded_so_the_next_launch_is_clean() = runTest {
        val storage = InMemoryTokenStorage().apply { save("stale", "stale") }
        val repository = SessionRepository(
            fakeApiClient(
                mapOf(
                    "/users/me" to ("""{"code":"invalid_token","message":"expired"}""" to HttpStatusCode.Unauthorized),
                    "/auth/refresh" to ("""{"code":"invalid_refresh_token","message":"gone"}""" to HttpStatusCode.Unauthorized),
                ),
                tokenStorage = storage,
            ),
            storage,
        )

        repository.restore()

        assertTrue(repository.sessionState.value is SessionState.SignedOut)
        assertNull(storage.readAccessToken())
    }

    @Test
    fun demo_sign_in_adopts_the_returned_user_and_stores_its_tokens() = runTest {
        val storage = InMemoryTokenStorage()
        val repository = SessionRepository(
            fakeApiClient(mapOf("/auth/demo" to (authTokensJson() to HttpStatusCode.OK)), tokenStorage = storage),
            storage,
        )

        val result = repository.signInAsDemo()

        assertTrue(result.isSuccess)
        assertTrue(repository.sessionState.value is SessionState.SignedIn)
        assertEquals("access-1", storage.readAccessToken())
    }

    @Test
    fun starting_an_oauth_sign_in_returns_the_url_to_open() = runTest {
        val repository = SessionRepository(
            fakeApiClient(
                mapOf(
                    "/start" to (
                        """{"authorizationUrl":"https://accounts.google.com/x","state":"s","expiresInSeconds":600}"""
                            to HttpStatusCode.OK
                        ),
                ),
            ),
            InMemoryTokenStorage(),
        )

        assertEquals("https://accounts.google.com/x", repository.beginOAuthSignIn("GOOGLE", "APP").getOrNull())
    }

    @Test
    fun completing_the_handoff_signs_the_user_in() = runTest {
        val storage = InMemoryTokenStorage()
        val repository = SessionRepository(
            fakeApiClient(mapOf("/auth/exchange" to (authTokensJson() to HttpStatusCode.OK)), tokenStorage = storage),
            storage,
        )

        assertTrue(repository.completeOAuthSignIn("handoff-code").isSuccess)
        assertTrue(repository.sessionState.value is SessionState.SignedIn)
    }

    @Test
    fun updating_preferences_replaces_the_signed_in_user() = runTest {
        val storage = InMemoryTokenStorage().apply { save("a", "r") }
        val darkUser = userJson().replace("\"SYSTEM\"", "\"DARK\"")
        val repository = SessionRepository(
            fakeApiClient(mapOf("/users/me" to (darkUser to HttpStatusCode.OK)), tokenStorage = storage),
            storage,
        )

        val result = repository.updatePreferences(UpdatePreferencesRequest(theme = UiTheme.DARK))

        assertEquals(UiTheme.DARK, result.getOrNull()?.theme)
        assertEquals(UiTheme.DARK, repository.currentUser?.theme)
    }

    @Test
    fun signing_out_clears_the_session_even_if_the_server_errors() = runTest {
        val storage = InMemoryTokenStorage().apply { save("a", "r") }
        val repository = SessionRepository(
            fakeApiClient(mapOf("/auth/logout" to ("" to HttpStatusCode.InternalServerError)), tokenStorage = storage),
            storage,
        )

        repository.signOut()

        assertTrue(repository.sessionState.value is SessionState.SignedOut)
        assertNull(storage.readAccessToken())
    }

    @Test
    fun an_expired_session_discovered_mid_use_signs_the_user_out_with_a_reason() = runTest {
        val storage = InMemoryTokenStorage().apply { save("a", "r") }
        val repository = SessionRepository(fakeApiClient(emptyMap(), tokenStorage = storage), storage)

        repository.onSessionExpired()

        val state = repository.sessionState.value
        assertTrue(state is SessionState.SignedOut)
        assertEquals("Your session expired. Sign in again.", state.reason)
        assertNull(storage.readRefreshToken())
    }

    @Test
    fun unlinking_an_account_refreshes_the_user() = runTest {
        val storage = InMemoryTokenStorage().apply { save("a", "r") }
        val repository = SessionRepository(
            fakeApiClient(
                mapOf(
                    "/users/me/accounts/" to ("" to HttpStatusCode.NoContent),
                    "/users/me" to (userJson() to HttpStatusCode.OK),
                ),
                tokenStorage = storage,
            ),
            storage,
        )

        assertTrue(repository.unlinkAccount("account-1").isSuccess)
        assertNotNull(repository.currentUser)
    }

    @Test
    fun the_available_providers_are_fetched_for_the_sign_in_screen() = runTest {
        val repository = SessionRepository(
            fakeApiClient(
                mapOf(
                    "/auth/providers" to (
                        """[{"id":"DEMO","displayName":"Demo","kind":"DEMO","icon":"demo"}]""" to HttpStatusCode.OK
                        ),
                ),
            ),
            InMemoryTokenStorage(),
        )

        assertEquals(1, repository.availableProviders().getOrNull()?.size)
    }

}
