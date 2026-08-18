package com.jmail.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role

/**
 * A clickable that always carries a role and an action label.
 *
 * Screen readers announce "double tap to <label>" from `onClickLabel`, and the role decides
 * how the element is described at all. Both are easy to omit on a plain `clickable`, so this
 * wrapper makes them part of the call rather than an afterthought.
 */
fun Modifier.clickableWithRole(
    onClick: () -> Unit,
    label: String? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
): Modifier = this.clickable(
    enabled = enabled,
    onClickLabel = label,
    role = role,
    onClick = onClick,
)

/**
 * Marks a surface as interactive to a pointer.
 *
 * Desktop and web users expect the cursor to change over anything clickable; without it a
 * message row reads as inert text no matter how it behaves.
 */
fun Modifier.pointerCursor(): Modifier = this.pointerHoverIcon(PointerIcon.Hand)

/** Tracks hover so a row can highlight under the pointer. Always false on touch platforms. */
@Composable
fun rememberHoverState(interactionSource: MutableInteractionSource): State<Boolean> =
    interactionSource.collectIsHoveredAsState()

@Composable
fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }
