package com.jmail.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.ComposeUIViewController
import com.jmail.shared.JMailContainer
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/**
 * The entry point Swift calls to get JMail's UI.
 *
 * The handoff code is held outside the composition because it arrives through
 * `SceneDelegate` — that is, from UIKit, before or after Compose exists — and needs to
 * survive that gap.
 */
private val pendingHandoffCode = mutableStateOf<String?>(null)

private val container: JMailContainer by lazy {
    JMailContainer(
        baseUrl = apiBaseUrl(),
        openUrl = { url ->
            NSURL.URLWithString(url)?.let { nsUrl ->
                UIApplication.sharedApplication.openURL(nsUrl)
            }
        },
    )
}

fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        container = container,
        pendingHandoffCode = pendingHandoffCode.value,
        onHandoffConsumed = { pendingHandoffCode.value = null },
    )
}

/**
 * Called from Swift when iOS opens the app with a `jmail://auth/callback?code=…` URL.
 *
 * @return true when the URL was a JMail sign-in callback, so Swift knows whether it was
 *   handled.
 */
fun handleDeepLink(url: String): Boolean {
    if (!url.startsWith("jmail://")) return false

    val code = url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("code=") }
        ?.removePrefix("code=")
        ?.takeIf { it.isNotBlank() }
        ?: return false

    pendingHandoffCode.value = code
    return true
}

/**
 * Reads `JMailApiUrl` from the app's Info.plist, so the same binary can point at a local
 * backend in development and a deployed one in a TestFlight build without a code change.
 */
private fun apiBaseUrl(): String {
    val configured = platform.Foundation.NSBundle.mainBundle
        .objectForInfoDictionaryKey("JMailApiUrl") as? String

    return configured?.takeIf { it.isNotBlank() } ?: JMailContainer.DEFAULT_BASE_URL
}
