package com.jmail.shared.network

import kotlinx.browser.localStorage
import org.w3c.dom.get

/**
 * Browser token storage.
 *
 * `localStorage` is the only durable option available to a WebAssembly app, and it is
 * readable by any script running on the origin. JMail's own strict CSP and the sanitiser
 * that strips scripts from message bodies are what keep that boundary intact; the short
 * access-token lifetime limits the damage if it is ever crossed.
 *
 * Every access is guarded: `localStorage` throws in private-browsing modes and when storage
 * is disabled entirely, and neither should stop the app from loading.
 */
private class BrowserTokenStorage : TokenStorage {

    override fun readAccessToken(): String? = read(ACCESS_TOKEN_KEY)

    override fun readRefreshToken(): String? = read(REFRESH_TOKEN_KEY)

    override fun save(accessToken: String, refreshToken: String) {
        runCatching {
            localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
            localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
        }
    }

    override fun clear() {
        runCatching {
            localStorage.removeItem(ACCESS_TOKEN_KEY)
            localStorage.removeItem(REFRESH_TOKEN_KEY)
        }
    }

    private fun read(key: String): String? = runCatching { localStorage[key] }.getOrNull()

    private companion object {
        const val ACCESS_TOKEN_KEY = "jmail.accessToken"
        const val REFRESH_TOKEN_KEY = "jmail.refreshToken"
    }
}

actual fun createTokenStorage(): TokenStorage = BrowserTokenStorage()
