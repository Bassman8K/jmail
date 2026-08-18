package com.jmail.app.ui.mailbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.SenderAvatar
import com.jmail.app.ui.components.clickableWithRole
import com.jmail.app.ui.components.pointerCursor
import com.jmail.app.ui.components.rememberHoverState
import com.jmail.app.ui.components.rememberInteractionSource
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.app.ui.theme.parseHexColor
import com.jmail.shared.model.Category
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.util.Formatting

/**
 * One row in the message list.
 *
 * The whole design of this row exists to answer four questions in a single glance: who it
 * is from, what it is about, when it arrived, and whether it has been read. Everything else
 * — the star, the category, the attachment marker — is deliberately quieter so it does not
 * compete with those four.
 *
 * Unread is signalled three ways at once (weight, an amber dot, and a tinted background)
 * because colour alone fails for colour-blind users and weight alone is easy to miss when
 * scanning quickly.
 */
@Composable
fun MessageRow(
    message: MessageSummary,
    isSelected: Boolean,
    isChecked: Boolean,
    selectionMode: Boolean,
    category: Category?,
    onClick: () -> Unit,
    onToggleChecked: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
    showCategory: Boolean = true,
) {
    val interactionSource = rememberInteractionSource()
    val isHovered by rememberHoverState(interactionSource)

    val background by animateColorAsState(
        targetValue = when {
            isSelected -> JMailTheme.semantic.selectedRow
            isChecked -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            isHovered -> JMailTheme.semantic.hoveredRow
            !message.isRead -> MaterialTheme.colorScheme.surface
            else -> Color.Transparent
        },
        label = "row-background",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .clickableWithRole(
                onClick = onClick,
                label = "Open message",
                role = androidx.compose.ui.semantics.Role.Button,
            )
            .pointerCursor()
            .padding(
                horizontal = JMailTheme.spacing.rowHorizontal,
                vertical = JMailTheme.spacing.rowVertical,
            )
            // One description for the whole row: a screen reader should read it as a single
            // item, not as six disconnected fragments.
            .semantics(mergeDescendants = true) {
                selected = isSelected
                contentDescription = describeMessage(message, category)
            },
        verticalAlignment = Alignment.Top,
    ) {
        // The unread rail: a 3dp bar that survives at any density and any colour vision.
        Box(
            Modifier
                .width(3.dp)
                .height(if (message.isRead) 0.dp else 40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (message.isRead) Color.Transparent else JMailTheme.semantic.unread),
        )
        Spacer(Modifier.width(JMailTheme.spacing.small))

        // The avatar becomes a checkbox in selection mode and on hover, which is where
        // people reach for it — no separate column that is empty most of the time.
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selectionMode || isHovered || isChecked) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggleChecked() },
                    modifier = Modifier.semantics {
                        contentDescription = if (isChecked) "Deselect message" else "Select message"
                    },
                )
            } else {
                SenderAvatar(address = message.from)
            }
        }

        Spacer(Modifier.width(JMailTheme.spacing.medium))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.from.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Spacer(Modifier.width(JMailTheme.spacing.small))

                if (message.hasAttachments) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = null, // included in the row description
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(JMailTheme.spacing.tight))
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = Formatting.listTimestamp(message.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isRead) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(JMailTheme.spacing.hairline))

            Text(
                text = message.displaySubject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(JMailTheme.spacing.hairline))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small),
            ) {
                Text(
                    text = message.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // Primary is the default home for anything unclassified, so labelling it
                // would put a marker on most rows and mean nothing.
                if (showCategory && category != null && category.key != PRIMARY_CATEGORY_KEY) {
                    CategoryDot(category)
                }
            }
        }

        Spacer(Modifier.width(JMailTheme.spacing.small))

        IconButton(
            onClick = onToggleStar,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (message.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (message.isStarred) "Remove star" else "Add star",
                tint = if (message.isStarred) {
                    JMailTheme.semantic.starred
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isHovered) 0.9f else 0.45f)
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val PRIMARY_CATEGORY_KEY = "primary"

/**
 * The category marker: a coloured dot plus its name.
 *
 * A dot rather than a filled chip because the list already carries a lot of colour, and the
 * category is context rather than the point of the row. The name is always present — a
 * bare colour would be meaningless to anyone who cannot distinguish it.
 */
@Composable
private fun CategoryDot(category: Category) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.tight),
        modifier = Modifier.clearAndSetSemantics { }, // announced as part of the row
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(parseHexColor(category.color)),
        )
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The single sentence a screen reader announces for a row.
 *
 * Ordered by importance rather than by visual position: read state first, because it is
 * what a user scanning their inbox by ear most needs to know.
 */
internal fun describeMessage(message: MessageSummary, category: Category?): String = buildString {
    append(if (message.isRead) "Read" else "Unread")
    append(" message from ")
    append(message.from.displayLabel)
    append(". Subject: ")
    append(message.displaySubject)
    append(". Received ")
    append(Formatting.listTimestamp(message.receivedAt))
    if (message.hasAttachments) append(". Has attachments")
    if (message.isStarred) append(". Starred")
    category?.let { append(". Category: ${it.name}") }
    append(". ")
    append(message.snippet)
}
