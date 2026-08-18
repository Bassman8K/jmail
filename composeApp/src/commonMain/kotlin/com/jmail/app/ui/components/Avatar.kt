package com.jmail.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.theme.AccountAccentColors
import com.jmail.shared.model.EmailAddress
import kotlin.math.abs

/**
 * The circular sender avatar.
 *
 * Colour is derived from the address, so the same person is always the same colour — which
 * turns the avatar into a genuine recognition cue when scanning a list rather than
 * decoration. The foreground is chosen from the background's luminance so contrast holds
 * whichever colour is picked.
 *
 * Marked `clearAndSetSemantics` with no label: the sender's name is already announced by the
 * row beside it, and repeating it would make every row read twice to a screen reader.
 */
@Composable
fun SenderAvatar(
    address: EmailAddress,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val background = remember(address.address) { colorForAddress(address.address) }
    val foreground = remember(background) { contrastingForeground(background) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = address.initials,
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Stable colour per address.
 *
 * A simple sum-of-characters hash rather than [String.hashCode]: hashCode is not specified
 * to be stable across platforms, and an avatar that changes colour between the desktop and
 * web builds would undermine the whole point.
 */
internal fun colorForAddress(address: String): Color {
    if (address.isEmpty()) return AccountAccentColors.first()

    val hash = address.fold(0) { accumulator, character -> accumulator * 31 + character.code }
    return AccountAccentColors[abs(hash) % AccountAccentColors.size]
}

/** Black on light backgrounds, white on dark ones, decided by perceptual luminance. */
internal fun contrastingForeground(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF15171C) else Color.White
