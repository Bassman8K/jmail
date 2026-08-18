package com.jmail.app.ui.mailbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.PaneWidths
import com.jmail.app.ui.components.WindowSizeClass
import com.jmail.app.ui.reader.ReaderPane
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.MailAccount
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.state.MailboxStore
import com.jmail.shared.state.MailboxUiState
import com.jmail.shared.state.ReaderStore
import com.jmail.shared.state.ReaderUiState
import kotlinx.coroutines.launch

/**
 * The mailbox: sidebar, list and reader, arranged to fit whatever room there is.
 *
 * One composable handles all three layouts rather than three screens, because the state and
 * the actions are identical — only the arrangement differs. That is also what makes a
 * desktop window resized down to phone width behave exactly like a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    state: MailboxUiState,
    readerState: ReaderUiState,
    accounts: List<MailAccount>,
    mailboxStore: MailboxStore,
    readerStore: ReaderStore,
    onCompose: () -> Unit,
    onReply: (MessageDetail, Boolean) -> Unit,
    onForward: (MessageDetail) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLink: (String) -> Unit,
    onReconnectAccount: (MailAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Undo is a snackbar with an action; dismissing it simply lets the window lapse.
    LaunchedEffect(state.undo) {
        val undo = state.undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = undo.label,
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) mailboxStore.undo() else mailboxStore.dismissUndo()
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            mailboxStore.dismissStatus()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val sizeClass = WindowSizeClass.forWidth(maxWidth)

        val sidebar = @Composable {
            Sidebar(
                state = state,
                accounts = accounts,
                onSelectFolder = { folder ->
                    when (folder) {
                        "STARRED" -> mailboxStore.toggleStarredOnly()
                        else -> mailboxStore.selectFolder(folder)
                    }
                    coroutineScope.launch { drawerState.close() }
                },
                onSelectCategory = { categoryId ->
                    mailboxStore.selectCategory(categoryId)
                    coroutineScope.launch { drawerState.close() }
                },
                onCompose = onCompose,
                onOpenSettings = onOpenSettings,
                onReconnectAccount = onReconnectAccount,
                modifier = Modifier.width(PaneWidths.sidebar),
            )
        }

        val content = @Composable {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = {
                    if (!sizeClass.showsPersistentSidebar) {
                        FloatingActionButton(
                            onClick = onCompose,
                            modifier = Modifier.semantics { contentDescription = "Write a new message" },
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    MailboxToolbar(
                        state = state,
                        showMenuButton = !sizeClass.showsPersistentSidebar,
                        onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                        onSearch = mailboxStore::search,
                        onSync = mailboxStore::syncNow,
                        onToggleUnread = mailboxStore::toggleUnreadOnly,
                    )

                    // Directly under the search bar and spanning the full width, the way
                    // Gmail and Apple Mail present them. Inside the list column they were
                    // only as wide as the list, which read as part of the list rather than
                    // as the primary way to move between views.
                    CategoryTabs(
                        state = state,
                        onSelectCategory = mailboxStore::selectCategory,
                    )

                    AnimatedVisibility(visible = state.isSelectionMode) {
                        SelectionActionBar(
                            count = state.checkedIds.size,
                            onClear = mailboxStore::clearChecked,
                            onMarkRead = { mailboxStore.markRead(state.checkedIds.toList(), true) },
                            onArchive = { mailboxStore.archive(state.checkedIds.toList()) },
                            onTrash = { mailboxStore.trash(state.checkedIds.toList()) },
                            onSpam = { mailboxStore.markSpam(state.checkedIds.toList()) },
                        )
                    }

                    Row(Modifier.fillMaxSize()) {
                        val readerIsOpen = readerState.isOpen

                        // On a phone the reader replaces the list; anywhere larger they sit
                        // side by side and the list keeps its scroll position.
                        if (!sizeClass.showsListAndReaderTogether && readerIsOpen) {
                            ReaderPane(
                                state = readerState,
                                showBackButton = true,
                                onClose = {
                                    readerStore.close()
                                    mailboxStore.closeMessage()
                                },
                                onArchive = { readerState.messageId?.let { mailboxStore.archive(listOf(it)) } },
                                onTrash = { readerState.messageId?.let { mailboxStore.trash(listOf(it)) } },
                                onSpam = { readerState.messageId?.let { mailboxStore.markSpam(listOf(it)) } },
                                onToggleStar = {
                                    state.messages.firstOrNull { it.id == readerState.messageId }
                                        ?.let(mailboxStore::toggleStar)
                                },
                                onReply = onReply,
                                onForward = onForward,
                                onLoadRemoteImages = readerStore::loadRemoteImages,
                                onRetry = readerStore::retry,
                                onOpenLink = onOpenLink,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MessageList(
                                state = state,
                                onOpenMessage = { id ->
                                    mailboxStore.openMessage(id)
                                    readerStore.open(id)
                                },
                                onToggleChecked = mailboxStore::toggleChecked,
                                onToggleStar = mailboxStore::toggleStar,
                                onLoadMore = mailboxStore::loadMore,
                                onRetry = mailboxStore::refresh,
                                onClearFilters = mailboxStore::clearFilters,
                                modifier = if (sizeClass.showsListAndReaderTogether) {
                                    Modifier.width(PaneWidths.listPreferred).fillMaxHeight()
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            )

                            if (sizeClass.showsListAndReaderTogether) {
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ReaderPane(
                                    state = readerState,
                                    onClose = {
                                        readerStore.close()
                                        mailboxStore.closeMessage()
                                    },
                                    onArchive = { readerState.messageId?.let { mailboxStore.archive(listOf(it)) } },
                                    onTrash = { readerState.messageId?.let { mailboxStore.trash(listOf(it)) } },
                                    onSpam = { readerState.messageId?.let { mailboxStore.markSpam(listOf(it)) } },
                                    onToggleStar = {
                                        state.messages.firstOrNull { it.id == readerState.messageId }
                                            ?.let(mailboxStore::toggleStar)
                                    },
                                    onReply = onReply,
                                    onForward = onForward,
                                    onLoadRemoteImages = readerStore::loadRemoteImages,
                                    onRetry = readerStore::retry,
                                    onOpenLink = onOpenLink,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sizeClass.showsPersistentSidebar) {
            Row(Modifier.fillMaxSize()) {
                sidebar()
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet { sidebar() } },
                content = content,
            )
        }
    }
}

/** Search, sync and filters. Search is always visible — it is the primary way people navigate mail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailboxToolbar(
    state: MailboxUiState,
    showMenuButton: Boolean,
    onOpenDrawer: () -> Unit,
    onSearch: (String) -> Unit,
    onSync: () -> Unit,
    onToggleUnread: () -> Unit,
) {
    var searchTerm by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                horizontal = JMailTheme.spacing.medium,
                vertical = JMailTheme.spacing.small,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showMenuButton) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Open navigation")
                }
            }

            OutlinedTextField(
                value = searchTerm,
                onValueChange = {
                    searchTerm = it
                    onSearch(it)
                },
                placeholder = { Text("Search mail") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchTerm.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchTerm = ""
                                onSearch("")
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Search all mail" },
            )

            Spacer(Modifier.width(JMailTheme.spacing.small))

            IconButton(onClick = onSync, enabled = !state.isSyncing) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = if (state.isSyncing) "Checking for new mail" else "Check for new mail",
                )
            }
        }

        Row(
            Modifier.padding(top = JMailTheme.spacing.small),
            horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.query.unreadOnly,
                onClick = onToggleUnread,
                label = { Text("Unread") },
                leadingIcon = {
                    Icon(Icons.Outlined.FilterList, contentDescription = null, Modifier.size(16.dp))
                },
            )

            if (state.unreadCount > 0) {
                Text(
                    text = "${state.unreadCount} unread",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The bar that replaces the toolbar when messages are checked.
 *
 * Actions are icons with labels announced to screen readers, and the count is stated
 * explicitly — "3 selected" removes any doubt about what a destructive action will hit.
 */
@Composable
private fun SelectionActionBar(
    count: Int,
    onClear: () -> Unit,
    onMarkRead: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onSpam: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = JMailTheme.spacing.medium, vertical = JMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
        }
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMarkRead) {
            Icon(Icons.Outlined.MarkEmailRead, contentDescription = "Mark $count messages as read")
        }
        IconButton(onClick = onArchive) {
            Icon(Icons.Outlined.Archive, contentDescription = "Archive $count messages")
        }
        IconButton(onClick = onTrash) {
            Icon(Icons.Outlined.Delete, contentDescription = "Move $count messages to Trash")
        }
        IconButton(onClick = onSpam) {
            Icon(Icons.Outlined.Report, contentDescription = "Report $count messages as spam")
        }
    }
}
