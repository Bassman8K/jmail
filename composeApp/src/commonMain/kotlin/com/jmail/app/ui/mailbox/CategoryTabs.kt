package com.jmail.app.ui.mailbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.jmail.shared.state.MailboxUiState
import com.jmail.shared.util.Formatting

/**
 * Category tabs across the top of the message list.
 *
 * This is where people expect them: Gmail puts its categories here, Apple Mail puts its
 * mailbox filters here, and both treat the left sidebar as *where mail lives* rather than
 * *how it is sorted*. Putting categories in the sidebar next to Inbox and Trash conflates
 * those two ideas — a message is in exactly one folder, but "Promotions" is a view of the
 * inbox, not a place.
 *
 * The row scrolls horizontally rather than wrapping or collapsing into a menu, so the same
 * component works from a phone to a wide desktop window, and the selected tab is always
 * scrolled into view.
 */
@Composable
fun CategoryTabs(
    state: MailboxUiState,
    onSelectCategory: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = state.categories
    if (categories.isEmpty()) return

    val listState = rememberLazyListState()
    val selectedId = state.query.categoryId

    // Keep the active tab visible when the selection changes from elsewhere — a deep link,
    // or clearing a filter.
    LaunchedEffect(selectedId) {
        val index = if (selectedId == null) 0 else categories.indexOfFirst { it.id == selectedId } + 1
        if (index >= 0) listState.animateScrollToItem(index.coerceAtLeast(0))
    }

    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = JMailTheme.spacing.medium,
                vertical = JMailTheme.spacing.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.tight),
        ) {
            item(key = "all") {
                CategoryTab(
                    label = "All mail",
                    unread = state.counts.totalUnread,
                    accent = MaterialTheme.colorScheme.primary,
                    isSelected = selectedId == null,
                    showLeadingIcon = true,
                    onClick = { onSelectCategory(null) },
                )
            }

            items(categories, key = { it.id }) { category ->
                CategoryTab(
                    label = category.name,
                    unread = state.counts.categories.firstOrNull { it.categoryId == category.id }?.unread ?: 0,
                    accent = parseHexColor(category.color),
                    isSelected = selectedId == category.id,
                    showLeadingIcon = false,
                    onClick = { onSelectCategory(category.id) },
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * One tab: a pill that fills with the category's own colour when selected.
 *
 * A pill rather than an underline because each category already has a colour, and tinting
 * the whole control makes the current view obvious at a glance without adding a second
 * visual language. The dot keeps the colour visible when the tab is *not* selected, so the
 * mapping between colour and category is learnable.
 */
@Composable
private fun CategoryTab(
    label: String,
    unread: Long,
    accent: Color,
    isSelected: Boolean,
    showLeadingIcon: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent,
        label = "tab-background",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "tab-content",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .clickableWithRole(onClick = onClick, label = "Show $label", role = Role.Tab)
            .pointerCursor()
            .padding(horizontal = JMailTheme.spacing.medium, vertical = JMailTheme.spacing.small)
            .semantics {
                selected = isSelected
                contentDescription = if (unread > 0) "$label, $unread unread" else label
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small),
    ) {
        if (showLeadingIcon) {
            Icon(
                imageVector = Icons.Outlined.AllInbox,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (unread > 0) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (isSelected) accent else MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = Formatting.badgeCount(unread),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) {
                        // The pill is the category colour; pick a foreground that survives it.
                        if (accent.luminanceIsLight()) Color(0xFF15171C) else Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Perceptual lightness, used to keep badge text legible on any category colour. */
private fun Color.luminanceIsLight(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) > 0.6
