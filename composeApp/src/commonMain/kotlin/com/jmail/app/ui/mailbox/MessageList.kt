package com.jmail.app.ui.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.EmptyState
import com.jmail.app.ui.components.ErrorState
import com.jmail.app.ui.components.MessageListSkeleton
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.state.MailboxUiState
import com.jmail.shared.util.Formatting

/**
 * The message list.
 *
 * Grouped by age with sticky headers, which gives a long list a sense of place — you can
 * tell how far back you have scrolled without reading a single date. Pagination is driven
 * by scroll position rather than a "load more" button, but the trigger fires several rows
 * early so the next page is usually already there by the time it is needed.
 */
@Composable
fun MessageList(
    state: MailboxUiState,
    onOpenMessage: (String) -> Unit,
    onToggleChecked: (String) -> Unit,
    onToggleStar: (MessageSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    // First load only: a refresh keeps the existing rows on screen rather than replacing
    // them with skeletons, which would make a pull-to-refresh feel like a page navigation.
    if (state.isLoading && state.messages.isEmpty()) {
        MessageListSkeleton(modifier = modifier)
        return
    }

    if (state.error != null && state.messages.isEmpty()) {
        ErrorState(error = state.error!!, onRetry = onRetry, modifier = modifier)
        return
    }

    if (state.isEmpty) {
        EmptyStateForQuery(state = state, onClearFilters = onClearFilters, modifier = modifier)
        return
    }

    val shouldLoadMore by remember(state.hasMore, state.messages.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.hasMore && lastVisible >= state.messages.size - LOAD_MORE_THRESHOLD
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        var lastGroup: String? = null

        state.messages.forEach { message ->
            val group = Formatting.dateGroup(message.receivedAt)
            if (group != lastGroup) {
                lastGroup = group
                item(key = "header-$group", contentType = "header") {
                    DateGroupHeader(group)
                }
            }

            item(key = message.id, contentType = "message") {
                MessageRow(
                    message = message,
                    isSelected = state.selectedMessageId == message.id,
                    isChecked = message.id in state.checkedIds,
                    selectionMode = state.isSelectionMode,
                    category = state.categoryFor(message),
                    onClick = { onOpenMessage(message.id) },
                    onToggleChecked = { onToggleChecked(message.id) },
                    onToggleStar = { onToggleStar(message) },
                    showCategory = state.query.categoryId == null,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 1.dp,
                )
            }
        }

        if (state.isLoadingMore) {
            item(key = "loading-more", contentType = "footer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(JMailTheme.spacing.large),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun DateGroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(
                horizontal = JMailTheme.spacing.rowHorizontal,
                vertical = JMailTheme.spacing.small,
            ),
    )
}

/**
 * Empty states that say something useful.
 *
 * Which message appears depends entirely on *why* the list is empty: a filter that matched
 * nothing needs a way to clear it, a search that found nothing needs different words, and a
 * genuinely empty inbox deserves to be celebrated rather than apologised for.
 */
@Composable
private fun EmptyStateForQuery(
    state: MailboxUiState,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.query.isSearch -> EmptyState(
            title = "No results for \"${state.query.searchQuery}\"",
            description = "Check the spelling, try a sender's address, or search for a word " +
                "from the message body.",
            icon = Icons.Outlined.SearchOff,
            modifier = modifier,
        )

        state.query.hasFilters -> EmptyState(
            title = "Nothing matches these filters",
            description = "No messages here match what you have filtered by.",
            icon = Icons.Outlined.SearchOff,
            actionLabel = "Clear filters",
            onAction = onClearFilters,
            modifier = modifier,
        )

        state.query.folderType == "DRAFTS" -> EmptyState(
            title = "No drafts",
            description = "Messages you start but do not send will wait for you here.",
            icon = Icons.Outlined.Drafts,
            modifier = modifier,
        )

        else -> EmptyState(
            title = "You're all caught up",
            description = "Nothing left to read. New mail will appear here automatically.",
            icon = Icons.Outlined.Inbox,
            modifier = modifier,
        )
    }
}

/** Rows remaining below the fold when the next page is requested. */
private const val LOAD_MORE_THRESHOLD = 5
