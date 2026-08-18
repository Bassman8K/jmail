package com.jmail.shared.state

import com.jmail.shared.model.Category
import com.jmail.shared.model.MailFolder
import com.jmail.shared.model.MailboxCounts
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.network.ApiError
import com.jmail.shared.network.ApiResult
import com.jmail.shared.repository.MailRepository
import com.jmail.shared.repository.MailboxQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An action the user can take back, surfaced as a snackbar with an Undo button. */
data class UndoableAction(
    val label: String,
    val messageIds: List<String>,
    val kind: Kind,
) {
    enum class Kind { ARCHIVE, TRASH, SPAM, CATEGORY }
}

/** Everything the mailbox screen renders from. */
data class MailboxUiState(
    val query: MailboxQuery = MailboxQuery(),
    val messages: List<MessageSummary> = emptyList(),
    val categories: List<Category> = emptyList(),
    val folders: List<MailFolder> = emptyList(),
    val counts: MailboxCounts = MailboxCounts(),
    val selectedMessageId: String? = null,
    val checkedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSyncing: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 0,
    val error: ApiError? = null,
    val undo: UndoableAction? = null,
    val statusMessage: String? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty() && !isLoading && error == null

    val isSelectionMode: Boolean get() = checkedIds.isNotEmpty()

    val unreadCount: Long get() = counts.totalUnread

    fun categoryFor(message: MessageSummary): Category? =
        message.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
}

/**
 * The mailbox screen's brain: what to show, what is selected, and what every action does.
 *
 * Actions are applied optimistically and rolled back if the server rejects them. In a mail
 * client this is not a nicety — archiving a message has to feel instant, and waiting for a
 * round trip before the row disappears makes the whole app feel broken on a slow connection.
 */
class MailboxStore(
    private val repository: MailRepository,
    private val scope: CoroutineScope,
    /**
     * How long to wait after the last keystroke before searching. Injectable so tests can
     * exercise the debounce without sitting through it.
     */
    private val searchDebounceMillis: Long = DEFAULT_SEARCH_DEBOUNCE_MILLIS,
    /** How long an undone action stays undoable. */
    private val undoWindowMillis: Long = DEFAULT_UNDO_WINDOW_MILLIS,
) {

    private val internalState = MutableStateFlow(MailboxUiState())
    val state: StateFlow<MailboxUiState> = internalState.asStateFlow()

    private var searchJob: Job? = null
    private var undoJob: Job? = null

    /** Loads the sidebar and the first page. Call once when the mailbox opens. */
    fun start() {
        scope.launch {
            loadSidebar()
            loadFirstPage()
        }
    }

    fun setQuery(query: MailboxQuery) {
        if (query == internalState.value.query) return
        internalState.update { it.copy(query = query, selectedMessageId = null, checkedIds = emptySet()) }
        scope.launch { loadFirstPage() }
    }

    fun selectCategory(categoryId: String?) =
        setQuery(internalState.value.query.copy(categoryId = categoryId, searchQuery = null))

    fun selectFolder(folderType: String?, folderId: String? = null) =
        setQuery(
            internalState.value.query.copy(
                folderType = folderType,
                folderId = folderId,
                categoryId = null,
                searchQuery = null,
            ),
        )

    fun toggleUnreadOnly() = setQuery(
        internalState.value.query.copy(unreadOnly = !internalState.value.query.unreadOnly),
    )

    fun toggleStarredOnly() = setQuery(
        internalState.value.query.copy(starredOnly = !internalState.value.query.starredOnly),
    )

    fun clearFilters() = setQuery(
        internalState.value.query.copy(
            unreadOnly = false,
            starredOnly = false,
            withAttachmentsOnly = false,
            categoryId = null,
        ),
    )

    /**
     * Debounced search. Each keystroke cancels the previous request, so a fast typist issues
     * one query rather than one per character.
     */
    fun search(term: String) {
        searchJob?.cancel()

        if (term.isBlank()) {
            setQuery(internalState.value.query.copy(searchQuery = null))
            return
        }
        if (term.trim().length < MIN_SEARCH_LENGTH) return

        searchJob = scope.launch {
            delay(searchDebounceMillis)
            internalState.update { it.copy(query = it.query.copy(searchQuery = term.trim())) }
            loadFirstPage()
        }
    }

    fun refresh() {
        scope.launch {
            internalState.update { it.copy(isRefreshing = true, error = null) }
            loadSidebar()
            loadPage(0, replace = true)
            internalState.update { it.copy(isRefreshing = false) }
        }
    }

    fun loadMore() {
        val current = internalState.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return

        scope.launch {
            internalState.update { it.copy(isLoadingMore = true) }
            loadPage(current.page + 1, replace = false)
            internalState.update { it.copy(isLoadingMore = false) }
        }
    }

    /** Pulls new mail from the providers, then reloads what is on screen. */
    fun syncNow() {
        scope.launch {
            internalState.update { it.copy(isSyncing = true, statusMessage = "Checking for new mail…") }

            val result = repository.sync()
            val summary = result.getOrNull()?.sumOf { it.messagesAdded } ?: 0

            loadSidebar()
            loadPage(0, replace = true)

            internalState.update {
                it.copy(
                    isSyncing = false,
                    statusMessage = when {
                        result is ApiResult.Failure -> null
                        summary == 0 -> "No new mail"
                        summary == 1 -> "1 new message"
                        else -> "$summary new messages"
                    },
                    error = (result as? ApiResult.Failure)?.error ?: it.error,
                )
            }
        }
    }

    // ---- selection --------------------------------------------------------

    fun openMessage(messageId: String) {
        internalState.update { it.copy(selectedMessageId = messageId) }
        // Opening a message marks it read, exactly as the reader shows it.
        if (internalState.value.messages.firstOrNull { it.id == messageId }?.isRead == false) {
            markRead(listOf(messageId), read = true)
        }
    }

    fun closeMessage() = internalState.update { it.copy(selectedMessageId = null) }

    fun toggleChecked(messageId: String) = internalState.update { current ->
        val checked = if (messageId in current.checkedIds) {
            current.checkedIds - messageId
        } else {
            current.checkedIds + messageId
        }
        current.copy(checkedIds = checked)
    }

    fun checkAll() = internalState.update { current ->
        current.copy(checkedIds = current.messages.map(MessageSummary::id).toSet())
    }

    fun clearChecked() = internalState.update { it.copy(checkedIds = emptySet()) }

    // ---- actions ----------------------------------------------------------

    fun markRead(messageIds: List<String>, read: Boolean) {
        if (messageIds.isEmpty()) return
        val snapshot = internalState.value.messages

        internalState.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id in messageIds) message.copy(isRead = read) else message
                },
            )
        }

        scope.launch {
            val result = repository.markRead(messageIds, read)
            result.onFailure { error -> rollback(snapshot, error) }
            if (result.isSuccess) refreshCounts()
        }
    }

    fun toggleStar(message: MessageSummary) {
        val snapshot = internalState.value.messages
        val starred = !message.isStarred

        internalState.update { current ->
            current.copy(
                messages = current.messages.map {
                    if (it.id == message.id) it.copy(isStarred = starred) else it
                },
            )
        }

        scope.launch {
            repository.star(listOf(message.id), starred).onFailure { error -> rollback(snapshot, error) }
        }
    }

    fun archive(messageIds: List<String>) = removeWithUndo(
        messageIds = messageIds,
        kind = UndoableAction.Kind.ARCHIVE,
        label = if (messageIds.size == 1) "Archived" else "Archived ${messageIds.size} messages",
    ) { repository.archive(it) }

    fun trash(messageIds: List<String>) = removeWithUndo(
        messageIds = messageIds,
        kind = UndoableAction.Kind.TRASH,
        label = if (messageIds.size == 1) "Moved to Trash" else "Moved ${messageIds.size} to Trash",
    ) { repository.trash(it) }

    fun markSpam(messageIds: List<String>) = removeWithUndo(
        messageIds = messageIds,
        kind = UndoableAction.Kind.SPAM,
        label = "Reported as spam",
    ) { repository.markSpam(it) }

    fun assignCategory(messageIds: List<String>, categoryId: String?) {
        if (messageIds.isEmpty()) return
        val snapshot = internalState.value.messages

        internalState.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id in messageIds) message.copy(categoryId = categoryId, categoryConfidence = 1f) else message
                },
                checkedIds = emptySet(),
            )
        }

        scope.launch {
            val result = repository.assignCategory(messageIds, categoryId)
            result.onFailure { error -> rollback(snapshot, error) }
            if (result.isSuccess) refreshCounts()
        }
    }

    /**
     * Undo re-fetches rather than replaying the inverse action: the server may have changed
     * other things in the meantime, and reloading is the only way to be sure the list matches
     * what is actually stored.
     */
    fun undo() {
        val action = internalState.value.undo ?: return
        undoJob?.cancel()

        scope.launch {
            when (action.kind) {
                UndoableAction.Kind.ARCHIVE ->
                    repository.markRead(action.messageIds, read = false) // restores it to the list
                UndoableAction.Kind.TRASH, UndoableAction.Kind.SPAM, UndoableAction.Kind.CATEGORY ->
                    repository.assignCategory(action.messageIds, null)
            }
            internalState.update { it.copy(undo = null, statusMessage = "Undone") }
            loadPage(0, replace = true)
        }
    }

    fun dismissUndo() = internalState.update { it.copy(undo = null) }

    fun dismissError() = internalState.update { it.copy(error = null) }

    fun dismissStatus() = internalState.update { it.copy(statusMessage = null) }

    // ---- internals --------------------------------------------------------

    private fun removeWithUndo(
        messageIds: List<String>,
        kind: UndoableAction.Kind,
        label: String,
        action: suspend (List<String>) -> ApiResult<*>,
    ) {
        if (messageIds.isEmpty()) return
        val snapshot = internalState.value.messages

        internalState.update { current ->
            current.copy(
                messages = current.messages.filterNot { it.id in messageIds },
                checkedIds = emptySet(),
                selectedMessageId = current.selectedMessageId?.takeUnless { it in messageIds },
                undo = UndoableAction(label, messageIds, kind),
            )
        }

        scope.launch {
            val result = action(messageIds)
            result.onFailure { error -> rollback(snapshot, error) }
            if (result.isSuccess) refreshCounts()
        }

        undoJob?.cancel()
        undoJob = scope.launch {
            delay(undoWindowMillis)
            internalState.update { it.copy(undo = null) }
        }
    }

    private suspend fun loadFirstPage() {
        internalState.update { it.copy(isLoading = true, error = null) }
        loadPage(0, replace = true)
        internalState.update { it.copy(isLoading = false) }
    }

    private suspend fun loadPage(page: Int, replace: Boolean) {
        when (val result = repository.messages(internalState.value.query, page)) {
            is ApiResult.Success -> internalState.update { current ->
                val items = result.value.items
                current.copy(
                    messages = if (replace) items else current.messages + items,
                    page = result.value.page,
                    hasMore = result.value.hasMore,
                    error = null,
                )
            }

            is ApiResult.Failure -> internalState.update { it.copy(error = result.error) }
        }
    }

    private suspend fun loadSidebar() {
        repository.categories().onSuccess { categories ->
            internalState.update { it.copy(categories = categories) }
        }
        repository.folders().onSuccess { folders ->
            internalState.update { it.copy(folders = folders) }
        }
        refreshCounts()
    }

    private suspend fun refreshCounts() {
        repository.counts().onSuccess { counts ->
            internalState.update { it.copy(counts = counts) }
        }
    }

    private fun rollback(snapshot: List<MessageSummary>, error: ApiError) {
        internalState.update { it.copy(messages = snapshot, error = error, undo = null) }
    }

    companion object {
        const val DEFAULT_SEARCH_DEBOUNCE_MILLIS = 300L
        const val DEFAULT_UNDO_WINDOW_MILLIS = 6_000L
        private const val MIN_SEARCH_LENGTH = 2
    }
}
