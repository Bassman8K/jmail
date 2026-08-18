package com.jmail.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.jmail.shared.JMailContainer
import kotlinx.browser.document
import kotlinx.browser.localStorage
import org.w3c.dom.get
import kotlinx.browser.window

/**
 * The browser application.
 *
 * The API base URL is the page's own origin: nginx proxies `/api` to the backend, so the
 * browser build makes same-origin requests and never triggers a CORS preflight in production.
 * A `JMAIL_API_URL` global overrides it for local development against a separate backend.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Where the backend lives, in order of precedence:
    //   1. ?api=… on the URL, remembered afterwards — this is what makes a hosted build
    //      (GitHub Pages, a static host) usable against a backend the visitor runs.
    //   2. window.JMAIL_API_URL, injected by config.js for a local dev server.
    //   3. This page's own origin, which is right when nginx proxies /api alongside it.
    val baseUrl = apiFromQuery()?.also(::rememberApiBaseUrl)
        ?: rememberedApiBaseUrl()
        ?: apiBaseUrlOverride()
        ?: window.location.origin

    val container = JMailContainer(
        baseUrl = baseUrl,
        openUrl = { url ->
            // Sign-in URLs replace this page so the provider's redirect comes back to the
            // app itself. Opening a popup would deliver the handoff code to the popup, which
            // the app cannot read — and popup blockers frequently stop it outright.
            if (url.startsWith("$baseUrl/api/v1/auth/") || url.contains("oauth", ignoreCase = true)) {
                window.location.href = url
            } else {
                window.open(url, "_blank")
            }
        },
        // This build receives its callback as a URL in this browser, not as a jmail:// link.
        clientTarget = "WEB",
    )

    // The OAuth redirect lands back here as ?code=…; it is read once and then scrubbed from
    // the address bar so it cannot be replayed from history or from a shared link.
    val handoffCode = handoffCodeFromLocation()
    if (handoffCode != null || queryParameter("error") != null || queryParameter("api") != null) {
        window.history.replaceState(null, document.title, window.location.pathname)
    }

    ComposeViewport(document.body!!) {
        App(container = container, pendingHandoffCode = handoffCode)
    }
}

private fun handoffCodeFromLocation(): String? = queryParameter("code")

/** `?api=https://…` lets a hosted build talk to a backend the visitor is running. */
private fun apiFromQuery(): String? = queryParameter("api")
    ?.let { encoded -> decodeUriComponent(encoded) }
    ?.trimEnd('/')
    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private fun rememberApiBaseUrl(url: String) {
    runCatching { localStorage.setItem(API_BASE_URL_KEY, url) }
}

private fun rememberedApiBaseUrl(): String? =
    runCatching { localStorage[API_BASE_URL_KEY] }.getOrNull()?.takeIf { it.isNotBlank() }

private const val API_BASE_URL_KEY = "jmail.apiBaseUrl"

private fun queryParameter(name: String): String? = window.location.search
    .removePrefix("?")
    .split("&")
    .firstOrNull { it.startsWith("$name=") }
    ?.removePrefix("$name=")
    ?.takeIf { it.isNotBlank() }

/**
 * Reads `window.JMAIL_API_URL`, injected by index.html for local development.
 *
 * Kotlin/Wasm requires a `js(...)` body to be the entire function, hence the two-step read:
 * the interop call returns the raw string, and the emptiness check happens in Kotlin.
 */
private fun apiBaseUrlOverride(): String? = readApiBaseUrl().takeIf { it.isNotEmpty() }

private fun readApiBaseUrl(): String = js("window.JMAIL_API_URL || ''")

private fun decodeUriComponent(value: String): String = js("decodeURIComponent(value)")
