package com.jmail.app.ui.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.clickableWithRole
import com.jmail.app.ui.components.pointerCursor
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.app.ui.theme.parseHexColor
import com.jmail.shared.model.Category
import com.jmail.shared.model.MailAccount
import com.jmail.shared.state.MailboxUiState
import com.jmail.shared.util.Formatting

/**
 * The navigation sidebar: where mail *lives* — folders, then the accounts they come from.
 *
 * Categories are deliberately not here. They are a view of the inbox rather than a place, so
 * they belong across the top of the message list (see [CategoryTabs]), which is also where
 * Gmail and Apple Mail put them. Mixing the two in one list makes "Promotions" look like a
 * sibling of "Trash", which it is not.
 */
@Composable
fun Sidebar(
    state: MailboxUiState,
    accounts: List<MailAccount>,
    onSelectFolder: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onReconnectAccount: (MailAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = JMailTheme.spacing.medium),
    ) {
        ExtendedFloatingActionButton(
            onClick = onCompose,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            text = { Text("Compose") },
            modifier = Modifier
                .padding(horizontal = JMailTheme.spacing.large)
                .semantics { contentDescription = "Write a new message" },
        )

        Spacer(Modifier.height(JMailTheme.spacing.betweenSections))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            val counts = state.folders.associate { it.type.name to it.unreadCount }

            SidebarItem(
                label = "Inbox",
                icon = Icons.Outlined.Inbox,
                badge = state.counts.totalUnread,
                isSelected = state.query.folderType == "INBOX" && state.query.categoryId == null &&
                    !state.query.starredOnly,
                onClick = { onSelectFolder("INBOX") },
            )
            SidebarItem(
                label = "Starred",
                icon = Icons.Outlined.Star,
                isSelected = state.query.starredOnly,
                onClick = { onSelectFolder("STARRED") },
            )
            SidebarItem(
                label = "Sent",
                icon = Icons.Outlined.Send,
                isSelected = state.query.folderType == "SENT",
                onClick = { onSelectFolder("SENT") },
            )
            SidebarItem(
                label = "Drafts",
                icon = Icons.Outlined.Drafts,
                badge = counts["DRAFTS"]?.toLong() ?: 0,
                isSelected = state.query.folderType == "DRAFTS",
                onClick = { onSelectFolder("DRAFTS") },
            )
            SidebarItem(
                label = "Archive",
                icon = Icons.Outlined.Archive,
                isSelected = state.query.folderType == "ARCHIVE",
                onClick = { onSelectFolder("ARCHIVE") },
            )
            SidebarItem(
                label = "Spam",
                icon = Icons.Outlined.Report,
                isSelected = state.query.folderType == "SPAM",
                onClick = { onSelectFolder("SPAM") },
            )
            SidebarItem(
                label = "Trash",
                icon = Icons.Outlined.Delete,
                isSelected = state.query.folderType == "TRASH",
                onClick = { onSelectFolder("TRASH") },
            )

            if (accounts.isNotEmpty()) {
                SectionHeader("Accounts")
                accounts.forEach { account ->
                    AccountItem(account = account, onReconnect = { onReconnectAccount(account) })
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SidebarItem(
            label = "Settings",
            icon = Icons.Outlined.Settings,
            isSelected = false,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Spacer(Modifier.height(JMailTheme.spacing.large))
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = JMailTheme.spacing.betweenSections,
            end = JMailTheme.spacing.large,
            bottom = JMailTheme.spacing.small,
        ),
    )
}

/**
 * One navigation row.
 *
 * Selection is shown with a filled pill rather than a text-colour change, because a colour
 * shift alone is both easy to miss and inaccessible; the pill also gives the row a hit area
 * that matches what it looks like.
 */
@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    badge: Long = 0,
    leadingColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = JMailTheme.spacing.medium, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickableWithRole(onClick = onClick, label = "Show $label", role = Role.Tab)
            .pointerCursor()
            .padding(horizontal = JMailTheme.spacing.medium, vertical = JMailTheme.spacing.medium)
            .semantics { selected = isSelected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.medium),
    ) {
        if (leadingColor != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(leadingColor),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (badge > 0) {
            Text(
                text = Formatting.badgeCount(badge),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * An account row that only demands attention when something is wrong. A healthy account is
 * a quiet line of text; a broken one gets a warning colour and a way to fix it.
 */
@Composable
private fun AccountItem(account: MailAccount, onReconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = JMailTheme.spacing.medium, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .then(
                if (account.needsAttention) {
                    Modifier.clickableWithRole(
                        onClick = onReconnect,
                        label = "Reconnect ${account.email}",
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = JMailTheme.spacing.medium, vertical = JMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.medium),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(parseHexColor(account.color)),
        )

        Column(Modifier.weight(1f)) {
            Text(
                text = account.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.needsAttention) {
                Text(
                    text = "Tap to reconnect",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (account.needsAttention) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = "This account needs attention",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
