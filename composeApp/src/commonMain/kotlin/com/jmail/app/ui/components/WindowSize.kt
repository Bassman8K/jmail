package com.jmail.app.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the app has, and therefore how many panes it shows.
 *
 * The breakpoints follow Material's window size classes, but the *meaning* attached to each
 * is JMail's own: one pane on a phone, list plus reader on a tablet or small window, and
 * sidebar plus list plus reader when there is room for all three. This is the single
 * decision the whole layout hangs off.
 */
enum class WindowSizeClass {
    /** Phones, and desktop windows dragged narrow. One pane at a time. */
    COMPACT,

    /** Large phones in landscape, tablets, half-screen desktop. List and reader. */
    MEDIUM,

    /** Desktop and tablets in landscape. Sidebar, list and reader together. */
    EXPANDED,
    ;

    val showsPersistentSidebar: Boolean get() = this == EXPANDED

    /** Below this the reader replaces the list rather than sitting beside it. */
    val showsListAndReaderTogether: Boolean get() = this != COMPACT

    companion object {
        val mediumBreakpoint: Dp = 640.dp
        val expandedBreakpoint: Dp = 1_040.dp

        fun forWidth(width: Dp): WindowSizeClass = when {
            width < mediumBreakpoint -> COMPACT
            width < expandedBreakpoint -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** Widths that keep each pane readable rather than letting them stretch arbitrarily. */
object PaneWidths {
    val sidebar: Dp = 264.dp
    val listMinimum: Dp = 320.dp
    val listPreferred: Dp = 400.dp

    /** Roughly 75 characters at the body size — the width prose stays readable at. */
    val readerContentMaximum: Dp = 720.dp
}
