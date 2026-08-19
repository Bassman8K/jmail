package com.jmail.shared.state

import com.jmail.shared.EMPTY_COUNTS_JSON
import com.jmail.shared.awaitUntil
import com.jmail.shared.fakeApiClient
import com.jmail.shared.messagesPageJson
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.repository.MailRepository
import com.jmail.shared.settle
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

/**
 * The mailbox store owns the behaviour a user actually feels: does archiving a message
 * remove it immediately, does a failed request put it back, does undo restore it, and does
 * typing quickly issue one search rather than eight.
 *
 * The store runs on a real dispatcher here with short injected timings — see TestSupport for
 * why virtual time cannot be used with Ktor's client.
 */
class MailboxStoreTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val defaultRoutes = mapOf(
        "/messages/counts" to (EMPTY_COUNTS_JSON to HttpStatusCode.OK),
        "/messages/folders" to ("[]" to HttpStatusCode.OK),
        "/categories" to ("[]" to HttpStatusCode.OK),
        "/messages?" to (messagesPageJson("m1", "m2", "m3") to HttpStatusCode.OK),
    )

    private fun store(
        routes: Map<String, Pair<String, HttpStatusCode>> = defaultRoutes,
        onRequest: (String) -> Unit = {},
    ) = MailboxStore(
        repository = MailRepository(fakeApiClient(routes, onRequest = onRequest)),
        scope = scope,
        searchDebounceMillis = 60,
        undoWindowMillis = 200,
    )

    private val okAction = "/messages/actions" to ("""{"affected":1}""" to HttpStatusCode.OK)

    private val failingAction = "/messages/actions" to (
        """{"code":"internal_error","message":"boom"}""" to HttpStatusCode.InternalServerError
        )

    @Test
    fun start_loads_the_sidebar_and_the_first_page() = runTest {
        val store = store()

        store.start()
        awaitUntil(describe = { "the first page to load" }) { store.state.value.messages.size == 3 }

        assertFalse(store.state.value.isLoading)
        assertNull(store.state.value.error)
    }

    @Test
    fun archiving_removes_the_row_immediately_and_offers_an_undo() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.archive(listOf("m2"))

        // The row is gone before the request completes: this is the optimistic update.
        assertEquals(listOf("m1", "m3"), store.state.value.messages.map(MessageSummary::id))
        assertNotNull(store.state.value.undo)
        assertEquals(UndoableAction.Kind.ARCHIVE, store.state.value.undo?.kind)
    }

    @Test
    fun a_rejected_action_puts_the_row_back_and_reports_the_failure() = runTest {
        val store = store(defaultRoutes + failingAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.archive(listOf("m2"))
        awaitUntil(describe = { "the rollback" }) { store.state.value.error != null }

        assertEquals(listOf("m1", "m2", "m3"), store.state.value.messages.map(MessageSummary::id))
        assertNull(store.state.value.undo)
    }

    @Test
    fun the_undo_window_lapses_on_its_own() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.archive(listOf("m2"))
        assertNotNull(store.state.value.undo)

        awaitUntil(describe = { "the undo window to lapse" }) { store.state.value.undo == null }
    }

    @Test
    fun marking_read_updates_the_row_optimistically() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.markRead(listOf("m1"), read = true)

        assertTrue(store.state.value.messages.first { it.id == "m1" }.isRead)
    }

    @Test
    fun opening_an_unread_message_selects_it_and_marks_it_read() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.openMessage("m1")

        assertEquals("m1", store.state.value.selectedMessageId)
        assertTrue(store.state.value.messages.first { it.id == "m1" }.isRead)
    }

    @Test
    fun starring_toggles_and_rolls_back_when_the_server_refuses() = runTest {
        val store = store(defaultRoutes + failingAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.toggleStar(store.state.value.messages.first())
        assertTrue(store.state.value.messages.first().isStarred)

        awaitUntil(describe = { "the star to roll back" }) {
            !store.state.value.messages.first().isStarred
        }
    }

    @Test
    fun search_is_debounced_so_a_fast_typist_issues_one_query() = runTest {
        val requested = mutableListOf<String>()
        val store = store(
            defaultRoutes + ("/messages/search" to (messagesPageJson("s1") to HttpStatusCode.OK)),
            onRequest = { requested += it },
        )
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        requested.clear()

        listOf("i", "in", "inv", "invo", "invoice").forEach(store::search)
        awaitUntil(describe = { "the debounced search to run" }) {
            store.state.value.query.searchQuery == "invoice"
        }
        settle()

        assertEquals(1, requested.count { it.contains("/messages/search") })
    }

    @Test
    fun a_single_character_is_ignored_rather_than_searched_for() = runTest {
        val requested = mutableListOf<String>()
        val store = store(onRequest = { requested += it })
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        requested.clear()

        store.search("a")
        settle()

        assertEquals(0, requested.count { it.contains("/messages/search") })
    }

    @Test
    fun clearing_the_search_returns_to_the_ordinary_list() = runTest {
        val store = store(
            defaultRoutes + ("/messages/search" to (messagesPageJson("s1") to HttpStatusCode.OK)),
        )
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.search("invoice")
        awaitUntil { store.state.value.query.isSearch }

        store.search("")
        awaitUntil(describe = { "the list to return" }) {
            !store.state.value.query.isSearch && store.state.value.messages.size == 3
        }
    }

    @Test
    fun load_more_does_nothing_when_there_is_no_further_page() = runTest {
        var requestedSecondPage = false
        val store = store(onRequest = { if (it.contains("page=1")) requestedSecondPage = true })
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.loadMore()
        settle()

        assertFalse(requestedSecondPage)
        assertEquals(3, store.state.value.messages.size)
    }

    @Test
    fun the_next_page_is_appended_rather_than_replacing_what_is_on_screen() = runTest {
        val store = store(
            defaultRoutes + ("/messages?" to (messagesPageJson("m1", "m2", "m3", hasMore = true) to HttpStatusCode.OK)),
        )
        store.start()
        awaitUntil { store.state.value.messages.isNotEmpty() }

        store.loadMore()
        awaitUntil(describe = { "the second page to append" }) { store.state.value.messages.size > 3 }

        // The stub returns the same three ids for every page, so appending shows as growth.
        assertTrue(store.state.value.messages.size >= 6)
        assertFalse(store.state.value.isLoadingMore)
    }

    @Test
    fun selection_accumulates_and_clears() = runTest {
        val store = store()
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.toggleChecked("m1")
        store.toggleChecked("m3")
        assertEquals(setOf("m1", "m3"), store.state.value.checkedIds)
        assertTrue(store.state.value.isSelectionMode)

        store.toggleChecked("m1")
        assertEquals(setOf("m3"), store.state.value.checkedIds)

        store.checkAll()
        assertEquals(3, store.state.value.checkedIds.size)

        store.clearChecked()
        assertFalse(store.state.value.isSelectionMode)
    }

    @Test
    fun changing_the_category_clears_the_selection_and_reloads() = runTest {
        val store = store()
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        store.toggleChecked("m1")

        store.selectCategory("cat-1")
        awaitUntil { store.state.value.query.categoryId == "cat-1" }

        assertTrue(store.state.value.checkedIds.isEmpty())
    }

    @Test
    fun a_failed_first_load_surfaces_an_error_state_the_ui_can_render() = runTest {
        val store = store(
            mapOf(
                "/messages" to (
                    """{"code":"internal_error","message":"boom"}""" to HttpStatusCode.InternalServerError
                    ),
            ),
        )

        store.start()
        awaitUntil(describe = { "the error state" }) { store.state.value.error != null }

        assertTrue(store.state.value.messages.isEmpty())
        assertFalse(store.state.value.isLoading)
    }

    @Test
    fun sync_reports_how_much_new_mail_arrived() = runTest {
        val store = store(
            defaultRoutes + (
                "/messages/sync" to (
                    """[{"accountId":"acc-1","status":"SUCCEEDED","messagesAdded":2,"messagesUpdated":0}]"""
                        to HttpStatusCode.OK
                    )
                ),
        )
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.syncNow()
        // syncNow() only launches the work, so waiting for `!isSyncing` on its own passes
        // instantly on the state that has not started syncing yet. Wait for the flag to go up
        // first; the summary is written in the same atomic update that clears it.
        awaitUntil(describe = { "the sync to start" }) { store.state.value.isSyncing }
        awaitUntil(describe = { "the sync summary" }) { !store.state.value.isSyncing }

        assertEquals("2 new messages", store.state.value.statusMessage)
    }

    @Test
    fun sync_with_nothing_new_says_so_rather_than_staying_silent() = runTest {
        val store = store(
            defaultRoutes + (
                "/messages/sync" to (
                    """[{"accountId":"acc-1","status":"SUCCEEDED","messagesAdded":0,"messagesUpdated":0}]"""
                        to HttpStatusCode.OK
                    )
                ),
        )
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.syncNow()
        awaitUntil(describe = { "the sync to start" }) { store.state.value.isSyncing }
        awaitUntil(describe = { "the sync to finish" }) { !store.state.value.isSyncing }

        assertEquals("No new mail", store.state.value.statusMessage)
    }

    @Test
    fun filters_can_be_cleared_in_one_action() = runTest {
        val store = store()
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.toggleUnreadOnly()
        awaitUntil { store.state.value.query.unreadOnly }
        assertTrue(store.state.value.query.hasFilters)

        store.clearFilters()
        awaitUntil(describe = { "filters to clear" }) { !store.state.value.query.hasFilters }
    }

    @Test
    fun assigning_a_category_by_hand_updates_the_rows_and_clears_the_selection() = runTest {
        val store = store(
            defaultRoutes + ("/messages/categorize" to ("""{"affected":2}""" to HttpStatusCode.OK)),
        )
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        store.toggleChecked("m1")

        store.assignCategory(listOf("m1", "m2"), "cat-9")

        assertEquals("cat-9", store.state.value.messages.first { it.id == "m1" }.categoryId)
        // Filing by hand is a certainty, not a guess.
        assertEquals(1f, store.state.value.messages.first { it.id == "m1" }.categoryConfidence)
        assertTrue(store.state.value.checkedIds.isEmpty())
    }

    @Test
    fun dismissing_transient_state_leaves_the_list_alone() = runTest {
        val store = store(defaultRoutes + failingAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.archive(listOf("m2"))
        awaitUntil { store.state.value.error != null }

        store.dismissError()
        assertNull(store.state.value.error)
        assertEquals(3, store.state.value.messages.size)

        store.dismissStatus()
        assertNull(store.state.value.statusMessage)
    }

    // ---- the other destructive actions, and undo ---------------------------
    //
    // Archive, Trash and Spam share one code path but mean different things to the user and
    // undo differently. A wrong label on the snackbar, or an undo that restores the wrong
    // way, is the kind of thing only a test for each one catches.

    @Test
    fun trashing_removes_the_row_and_says_so() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.trash(listOf("m2"))

        assertEquals(listOf("m1", "m3"), store.state.value.messages.map(MessageSummary::id))
        assertEquals(UndoableAction.Kind.TRASH, store.state.value.undo?.kind)
        assertEquals("Moved to Trash", store.state.value.undo?.label)
    }

    @Test
    fun trashing_several_messages_counts_them_in_the_label() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.trash(listOf("m1", "m2"))

        assertEquals("Moved 2 to Trash", store.state.value.undo?.label)
        assertEquals(listOf("m3"), store.state.value.messages.map(MessageSummary::id))
    }

    @Test
    fun archiving_several_messages_counts_them_too() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.archive(listOf("m1", "m3"))

        assertEquals("Archived 2 messages", store.state.value.undo?.label)
    }

    @Test
    fun reporting_spam_removes_the_row_and_offers_an_undo() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.markSpam(listOf("m3"))

        assertEquals(listOf("m1", "m2"), store.state.value.messages.map(MessageSummary::id))
        assertEquals(UndoableAction.Kind.SPAM, store.state.value.undo?.kind)
        assertEquals("Reported as spam", store.state.value.undo?.label)
    }

    @Test
    fun an_empty_selection_does_nothing_at_all() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.archive(emptyList())
        store.trash(emptyList())
        store.markSpam(emptyList())
        store.assignCategory(emptyList(), "cat-1")

        assertEquals(3, store.state.value.messages.size)
        assertNull(store.state.value.undo)
    }

    @Test
    fun undo_puts_the_message_back_and_clears_the_offer() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        store.trash(listOf("m2"))
        assertNotNull(store.state.value.undo)

        store.undo()
        awaitUntil(describe = { "the undo to complete" }) { store.state.value.undo == null }

        assertEquals("Undone", store.state.value.statusMessage)
        // The list is reloaded from the server, which is what actually restores the row.
        awaitUntil { store.state.value.messages.size == 3 }
    }

    @Test
    fun undoing_an_archive_takes_the_other_route_back() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        store.archive(listOf("m2"))

        store.undo()
        awaitUntil(describe = { "the undo to complete" }) { store.state.value.undo == null }

        assertEquals("Undone", store.state.value.statusMessage)
    }

    @Test
    fun undo_with_nothing_to_undo_is_a_no_op() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.undo()

        assertNull(store.state.value.undo)
        assertEquals(3, store.state.value.messages.size)
    }

    @Test
    fun dismissing_the_undo_offer_keeps_the_action() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }
        store.archive(listOf("m2"))

        store.dismissUndo()

        assertNull(store.state.value.undo)
        // Dismissing the offer is not undoing it: the row stays gone.
        assertEquals(listOf("m1", "m3"), store.state.value.messages.map(MessageSummary::id))
    }

    // ---- navigation and filters -------------------------------------------

    @Test
    fun choosing_a_folder_clears_the_search_and_the_category() = runTest {
        val requests = MutableStateFlow(emptyList<String>())
        val store = store(defaultRoutes, onRequest = { request -> requests.update { it + request } })
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.selectCategory("cat-1")
        awaitUntil { store.state.value.query.categoryId == "cat-1" }

        store.selectFolder("SENT", folderId = "fol-9")

        assertEquals("SENT", store.state.value.query.folderType)
        assertEquals("fol-9", store.state.value.query.folderId)
        // Leaving either behind means the folder shows a filtered subset with no sign why.
        assertNull(store.state.value.query.categoryId)
        assertNull(store.state.value.query.searchQuery)
    }

    @Test
    fun the_starred_filter_toggles_both_ways() = runTest {
        val store = store()
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.toggleStarredOnly()
        awaitUntil { store.state.value.query.starredOnly }

        store.toggleStarredOnly()
        awaitUntil { !store.state.value.query.starredOnly }
    }

    @Test
    fun closing_a_message_clears_the_selection() = runTest {
        val store = store(defaultRoutes + okAction)
        store.start()
        awaitUntil { store.state.value.messages.size == 3 }

        store.openMessage("m1")
        assertEquals("m1", store.state.value.selectedMessageId)

        store.closeMessage()

        assertNull(store.state.value.selectedMessageId)
    }

    @Test
    fun opening_a_message_that_is_already_read_does_not_call_the_server_again() = runTest {
        val requests = MutableStateFlow(emptyList<String>())
        val store = store(
            defaultRoutes + ("/messages?" to (messagesPageJson("m1", unread = false) to HttpStatusCode.OK)) + okAction,
            onRequest = { request -> requests.update { it + request } },
        )
        store.start()
        awaitUntil { store.state.value.messages.size == 1 }
        val before = requests.value.size

        store.openMessage("m1")

        assertEquals("m1", store.state.value.selectedMessageId)
        assertEquals(before, requests.value.size)
    }
}
