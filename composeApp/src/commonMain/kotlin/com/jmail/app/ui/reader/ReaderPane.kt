package com.jmail.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forward
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.EmptyState
import com.jmail.app.ui.components.ErrorState
import com.jmail.app.ui.components.InlineBanner
import com.jmail.app.ui.components.LoadingState
import com.jmail.app.ui.components.PaneWidths
import com.jmail.app.ui.components.SenderAvatar
import com.jmail.app.ui.components.clickableWithRole
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.Attachment
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.state.ReaderUiState
import com.jmail.shared.util.Formatting

/**
 * The reading pane.
 *
 * Content is constrained to a comfortable measure and centred rather than filling a wide
 * desktop window: an 1,800px-wide line of text is unreadable however nice the typography.
 */
@Composable
fun ReaderPane(
    state: ReaderUiState,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onSpam: () -> Unit,
    onToggleStar: () -> Unit,
    onReply: (MessageDetail, Boolean) -> Unit,
    onForward: (MessageDetail) -> Unit,
    onLoadRemoteImages: () -> Unit,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when {
            !state.isOpen -> {
                EmptyState(
                    title = "Nothing selected",
                    description = "Choose a message from the list to read it here.",
                )
                return@Column
            }

            state.isLoading -> {
                LoadingState(label = "Loading message")
                return@Column
            }

            state.error != null && state.message == null -> {
                ErrorState(error = state.error!!, onRetry = onRetry)
                return@Column
            }
        }

        val message = state.message ?: return@Column

        ReaderToolbar(
            message = message,
            showBackButton = showBackButton,
            onClose = onClose,
            onArchive = onArchive,
            onTrash = onTrash,
            onSpam = onSpam,
            onToggleStar = onToggleStar,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.canLoadRemoteImages) {
            InlineBanner(
                message = "Images in this message were not loaded, so the sender cannot tell " +
                    "that you opened it.",
                icon = Icons.Outlined.ImageNotSupported,
                actionLabel = "Show images",
                onAction = onLoadRemoteImages,
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = PaneWidths.readerContentMaximum)
                    .padding(JMailTheme.spacing.betweenSections),
            ) {
                Text(
                    text = message.displaySubject,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )

                Spacer(Modifier.height(JMailTheme.spacing.large))

                SenderHeader(message)

                Spacer(Modifier.height(JMailTheme.spacing.large))

                if (message.attachments.any { !it.isInline }) {
                    AttachmentsRow(message.attachments.filterNot(Attachment::isInline))
                    Spacer(Modifier.height(JMailTheme.spacing.large))
                }

                MessageBody(bodyText = message.bodyText, onLinkClick = onOpenLink)

                Spacer(Modifier.height(JMailTheme.spacing.betweenSections))

                ReplyActions(
                    onReply = { onReply(message, false) },
                    onReplyAll = { onReply(message, true) },
                    onForward = { onForward(message) },
                    showReplyAll = message.to.size + message.cc.size > 1,
                )

                if (state.showAsThread) {
                    Spacer(Modifier.height(JMailTheme.spacing.generous))
                    ThreadSummary(state)
                }
            }
        }
    }
}

@Composable
private fun ReaderToolbar(
    message: MessageDetail,
    showBackButton: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onSpam: () -> Unit,
    onToggleStar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = JMailTheme.spacing.small, vertical = JMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackButton) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the message list")
            }
        }

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onArchive) {
            Icon(Icons.Outlined.Archive, contentDescription = "Archive this message")
        }
        IconButton(onClick = onTrash) {
            Icon(Icons.Outlined.Delete, contentDescription = "Move this message to Trash")
        }
        IconButton(onClick = onSpam) {
            Icon(Icons.Outlined.Report, contentDescription = "Report this message as spam")
        }
        IconButton(onClick = onToggleStar) {
            Icon(
                imageVector = if (message.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (message.isStarred) "Remove star" else "Add star",
                tint = if (message.isStarred) {
                    JMailTheme.semantic.starred
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Who sent it, to whom, and when.
 *
 * Recipients collapse to "to me" by default and expand on tap: the full list matters
 * occasionally and takes four lines when it does, which is not a reasonable default.
 */
@Composable
private fun SenderHeader(message: MessageDetail) {
    var expanded by remember(message.id) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.Top) {
        SenderAvatar(address = message.from, size = 44.dp)

        Spacer(Modifier.width(JMailTheme.spacing.medium))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.from.displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(JMailTheme.spacing.small))
                Text(
                    text = Formatting.detailTimestamp(message.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = message.from.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(JMailTheme.spacing.tight))

            val recipientSummary = remember(message.id, expanded) {
                if (expanded) {
                    buildString {
                        append("To: ")
                        append(message.to.joinToString { it.displayLabel })
                        if (message.cc.isNotEmpty()) {
                            append("\nCc: ")
                            append(message.cc.joinToString { it.displayLabel })
                        }
                    }
                } else {
                    val count = message.to.size + message.cc.size
                    if (count <= 1) "to ${message.to.firstOrNull()?.displayLabel ?: "you"}" else "to $count recipients"
                }
            }

            Text(
                text = recipientSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickableWithRole(
                    onClick = { expanded = !expanded },
                    label = if (expanded) "Hide recipients" else "Show all recipients",
                ),
            )
        }
    }
}

@Composable
private fun AttachmentsRow(attachments: List<Attachment>) {
    Column {
        Text(
            text = if (attachments.size == 1) "1 attachment" else "${attachments.size} attachments",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(JMailTheme.spacing.small))

        Row(horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small)) {
            attachments.take(MAX_VISIBLE_ATTACHMENTS).forEach { attachment ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .semantics {
                            contentDescription =
                                "Attachment ${attachment.filename}, ${Formatting.fileSize(attachment.sizeBytes)}"
                        },
                ) {
                    Row(
                        Modifier.padding(JMailTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(JMailTheme.spacing.small))
                        Column {
                            Text(
                                text = attachment.filename,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = Formatting.fileSize(attachment.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (attachments.size > MAX_VISIBLE_ATTACHMENTS) {
                AssistChip(
                    onClick = { },
                    label = { Text("+${attachments.size - MAX_VISIBLE_ATTACHMENTS} more") },
                )
            }
        }
    }
}

@Composable
private fun ReplyActions(
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    showReplyAll: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small)) {
        OutlinedButton(onClick = onReply) {
            Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(JMailTheme.spacing.small))
            Text("Reply")
        }
        if (showReplyAll) {
            OutlinedButton(onClick = onReplyAll) {
                Icon(Icons.AutoMirrored.Outlined.ReplyAll, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(JMailTheme.spacing.small))
                Text("Reply all")
            }
        }
        OutlinedButton(onClick = onForward) {
            Icon(Icons.Outlined.Forward, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(JMailTheme.spacing.small))
            Text("Forward")
        }
    }
}

/** The rest of the conversation, listed compactly beneath the open message. */
@Composable
private fun ThreadSummary(state: ReaderUiState) {
    val thread = state.thread ?: return
    val others = thread.messages.filterNot { it.id == state.messageId }
    if (others.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(JMailTheme.spacing.large),
    ) {
        Text(
            text = "${thread.messageCount} messages in this conversation",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(JMailTheme.spacing.medium))

        others.forEach { other ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = JMailTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SenderAvatar(address = other.from, size = 28.dp)
                Spacer(Modifier.width(JMailTheme.spacing.medium))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = other.from.displayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = other.bodyText?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = Formatting.listTimestamp(other.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_VISIBLE_ATTACHMENTS = 3
