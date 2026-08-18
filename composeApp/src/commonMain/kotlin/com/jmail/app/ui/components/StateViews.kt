package com.jmail.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.network.ApiError

/**
 * The three states every data-backed surface must handle, built once so no screen has to
 * invent its own — and so none of them can quietly forget one.
 */

/**
 * An empty state that explains *why* it is empty and what to do next.
 *
 * "No messages" alone is a dead end; an empty inbox after a filter is applied needs a
 * different message and a different action from an inbox that has genuinely been cleared.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(JMailTheme.spacing.generous),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // the title below carries the meaning
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(JMailTheme.spacing.large))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(JMailTheme.spacing.small))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(JMailTheme.spacing.betweenSections))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A failure state that says what went wrong in plain language and offers a way forward.
 * Retry is only offered when retrying could actually help — a 404 does not get a button
 * that will fail again identically.
 */
@Composable
fun ErrorState(
    error: ApiError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(JMailTheme.spacing.generous)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Error: ${error.userMessage}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (error.kind == ApiError.Kind.NETWORK) {
                Icons.Outlined.CloudOff
            } else {
                Icons.Outlined.ErrorOutline
            },
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(JMailTheme.spacing.large))
        Text(
            text = when (error.kind) {
                ApiError.Kind.NETWORK -> "You're offline"
                ApiError.Kind.RATE_LIMITED -> "Slow down a moment"
                ApiError.Kind.REAUTHENTICATION_REQUIRED -> "Reconnect this account"
                else -> "Something went wrong"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(JMailTheme.spacing.small))
        Text(
            text = error.userMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (onRetry != null && error.isRetryable) {
            Spacer(Modifier.height(JMailTheme.spacing.betweenSections))
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = "Loading",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp)
    }
}

/**
 * Skeleton rows shown while the first page loads.
 *
 * Preferred over a spinner for the list specifically: it holds the layout still, so content
 * appearing does not shift anything, and it communicates *what* is coming rather than just
 * that something is.
 */
@Composable
fun MessageListSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 8,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Loading messages" },
    ) {
        repeat(rowCount) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = JMailTheme.spacing.rowHorizontal,
                        vertical = JMailTheme.spacing.rowVertical,
                    )
                    .alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(JMailTheme.semantic.skeleton),
                )
                Spacer(Modifier.width(JMailTheme.spacing.medium))
                Column(Modifier.weight(1f)) {
                    SkeletonBar(widthFraction = if (index % 3 == 0) 0.45f else 0.32f, height = 14.dp)
                    Spacer(Modifier.height(JMailTheme.spacing.small))
                    SkeletonBar(widthFraction = if (index % 2 == 0) 0.85f else 0.7f, height = 12.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(JMailTheme.semantic.skeleton),
    )
}

/** A thin inline banner, used for sync status and non-blocking warnings. */
@Composable
fun InlineBanner(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JMailTheme.semantic.warningContainer)
            .padding(horizontal = JMailTheme.spacing.large, vertical = JMailTheme.spacing.medium)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.medium),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = JMailTheme.semantic.onWarningContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = JMailTheme.semantic.onWarningContainer,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .padding(PaddingValues(horizontal = 4.dp))
                    .let { base -> base }
                    .clickableWithRole(onClick = onAction, label = actionLabel),
            )
        }
    }
}
