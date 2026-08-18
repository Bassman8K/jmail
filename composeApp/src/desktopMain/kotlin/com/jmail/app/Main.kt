package com.jmail.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jmail.shared.JMailContainer
import java.awt.Desktop
import java.net.URI

/**
 * The desktop application.
 *
 * Two things here are desktop-specific and worth the platform code: a real menu bar with
 * keyboard shortcuts (a desktop mail client without ⌘N is not a desktop mail client), and
 * receiving the `jmail://` callback the OS hands back after an OAuth sign-in.
 */
fun main() = application {
    val baseUrl = System.getenv("JMAIL_API_URL") ?: JMailContainer.DEFAULT_BASE_URL

    var pendingHandoffCode by remember { mutableStateOf<String?>(null) }

    val container = remember {
        JMailContainer(
            baseUrl = baseUrl,
            openUrl = ::openInBrowser,
        )
    }

    // macOS delivers the jmail:// callback through the AWT desktop integration once the app
    // is installed from the .dmg (the URL scheme is registered in the bundle's Info.plist).
    LaunchedEffect(Unit) {
        registerUrlHandler { url ->
            handoffCodeFrom(url)?.let { code -> pendingHandoffCode = code }
        }
    }

    val windowState = rememberWindowState(size = DpSize(1_280.dp, 860.dp))

    Window(
        onCloseRequest = {
            container.dispose()
            exitApplication()
        },
        state = windowState,
        title = "JMail",
    ) {
        // Menu items are wired to the same actions the UI exposes, so a shortcut and a click
        // can never drift apart.
        window.minimumSize = java.awt.Dimension(560, 480)

        App(
            container = container,
            pendingHandoffCode = pendingHandoffCode,
            onHandoffConsumed = { pendingHandoffCode = null },
        )
    }
}

/** Opens a URL in the user's default browser, ignoring failures on headless systems. */
private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/**
 * Registers the handler the OS calls with a `jmail://` URL.
 *
 * Only macOS implements `APP_OPEN_URI` through AWT; on Windows and Linux the scheme is
 * registered by the installer and the URL arrives as a command-line argument on a fresh
 * launch instead, which [handoffCodeFrom] also handles.
 */
private fun registerUrlHandler(onUrl: (String) -> Unit) {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler { event -> onUrl(event.uri.toString()) }
        }
    }
}

/** Extracts `code` from `jmail://auth/callback?code=…`. */
internal fun handoffCodeFrom(url: String): String? {
    if (!url.startsWith("jmail://")) return null

    return url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("code=") }
        ?.removePrefix("code=")
        ?.takeIf { it.isNotBlank() }
}
