package com.jmail.shared

import com.jmail.shared.network.InMemoryTokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The hand-wired object graph.
 *
 * Small, but two things here are worth pinning down: every store must share one repository
 * and one token store (two of either means a sign-in that only half takes effect), and
 * `dispose` must actually cancel the scope, since the desktop window calls it on close and
 * a surviving scope keeps polling a backend nobody is listening to.
 */
class JMailContainerTest {

    private fun container(
        baseUrl: String = "http://localhost:8090",
        clientTarget: String = "APP",
        scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    ) = JMailContainer(
        baseUrl = baseUrl,
        openUrl = {},
        clientTarget = clientTarget,
        tokenStorage = InMemoryTokenStorage(),
        applicationScope = scope,
    )

    @Test
    fun the_repositories_are_shared_rather_than_rebuilt_per_store() {
        val container = container()

        // Two MailRepository instances would mean the reader and the list holding separate
        // caches, and a message marked read in one not updating in the other.
        assertSame(container.mailRepository, container.mailRepository)
        assertSame(container.sessionRepository, container.sessionRepository)
        assertSame(container.apiClient, container.apiClient)
    }

    @Test
    fun each_call_builds_a_fresh_store() {
        val container = container()

        // Stores hold screen state, so a second screen must not inherit the first's.
        assertNotSame(container.createMailboxStore(), container.createMailboxStore())
        assertNotSame(container.createReaderStore(), container.createReaderStore())
        assertNotSame(container.createComposeStore(), container.createComposeStore())
        assertNotSame(container.createSignInStore(), container.createSignInStore())
    }

    @Test
    fun the_default_base_url_points_at_the_local_backend() {
        assertEquals("http://localhost:8090", JMailContainer.DEFAULT_BASE_URL)
    }

    @Test
    fun disposing_cancels_the_application_scope() {
        val job = SupervisorJob()
        val container = container(scope = CoroutineScope(job))

        assertTrue(job.isActive)
        container.dispose()

        assertFalse(job.isActive)
        assertFalse(container.applicationScope.isActive)
    }

    @Test
    fun stores_can_be_given_their_own_scope_so_a_screen_can_be_torn_down_alone() {
        val container = container()
        val screenJob: Job = SupervisorJob()

        container.createMailboxStore(CoroutineScope(screenJob))
        screenJob.cancel()

        // Cancelling one screen must not take the application down with it.
        assertFalse(screenJob.isActive)
        assertTrue(container.applicationScope.isActive)

        container.dispose()
    }
}
