package com.jmail.shared.state

import com.jmail.shared.model.MailThread
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.network.ApiError
import com.jmail.shared.network.ApiResult
import com.jmail.shared.repository.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val messageId: String? = null,
    val message: MessageDetail? = null,
    val thread: MailThread? = null,
    val isLoading: Boolean = false,
    val error: ApiError? = null,
    val remoteImagesLoaded: Boolean = false,
    val expandedMessageIds: Set<String> = emptySet(),
) {
    val isOpen: Boolean get() = messageId != null

    /** A conversation is only worth rendering as one when it actually has replies. */
    val showAsThread: Boolean get() = (thread?.messageCount ?: 0) > 1

    val canLoadRemoteImages: Boolean
        get() = message?.hasBlockedImages == true && !remoteImagesLoaded
}

/**
 * The reading pane.
 *
 * Kept separate from [MailboxStore] so that opening a message cannot invalidate the list
 * that is behind it — the two update independently, which is what lets the list stay put
 * while a message loads beside it.
 */
class ReaderStore(
    private val repository: MailRepository,
    private val scope: CoroutineScope,
) {

    private val internalState = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = internalState.asStateFlow()

    private var loadJob: Job? = null

    fun open(messageId: String) {
        if (internalState.value.messageId == messageId) return

        loadJob?.cancel() // a fast scroll through the list must not race older responses in
        internalState.value = ReaderUiState(messageId = messageId, isLoading = true)

        loadJob = scope.launch {
            when (val result = repository.message(messageId)) {
                is ApiResult.Success -> {
                    internalState.update {
                        it.copy(message = result.value, isLoading = false, error = null)
                    }
                    loadThread(result.value.threadId, result.value.id)
                }

                is ApiResult.Failure -> internalState.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        internalState.value = ReaderUiState()
    }

    /** Fetches the body again with remote images permitted, at the user's explicit request. */
    fun loadRemoteImages() {
        val messageId = internalState.value.messageId ?: return

        scope.launch {
            repository.message(messageId, loadRemoteImages = true).onSuccess { detail ->
                internalState.update { it.copy(message = detail, remoteImagesLoaded = true) }
            }
        }
    }

    fun toggleExpanded(messageId: String) = internalState.update { current ->
        val expanded = if (messageId in current.expandedMessageIds) {
            current.expandedMessageIds - messageId
        } else {
            current.expandedMessageIds + messageId
        }
        current.copy(expandedMessageIds = expanded)
    }

    fun retry() {
        internalState.value.messageId?.let { id ->
            internalState.update { it.copy(messageId = null) } // force open() past its guard
            open(id)
        }
    }

    fun dismissError() = internalState.update { it.copy(error = null) }

    private suspend fun loadThread(threadId: String, currentMessageId: String) {
        repository.thread(threadId).onSuccess { thread ->
            internalState.update { current ->
                current.copy(
                    thread = thread,
                    // The message you opened starts expanded; the rest of the conversation
                    // stays collapsed so the thread reads as a summary.
                    expandedMessageIds = current.expandedMessageIds + currentMessageId,
                )
            }
        }
    }
}
