package com.jmail.shared.network

/**
 * Where the session tokens live between launches.
 *
 * Each platform supplies the most protected store it has: the Keychain-backed
 * `EncryptedSharedPreferences` on Android, `NSUserDefaults` inside the app sandbox on iOS,
 * a file with owner-only permissions on desktop, and `localStorage` in the browser.
 *
 * Implementations must never throw: a store that is unavailable behaves as empty, which
 * signs the user out rather than crashing the app on launch.
 */
interface TokenStorage {

    fun readAccessToken(): String?

    fun readRefreshToken(): String?

    fun save(accessToken: String, refreshToken: String)

    fun clear()
}

/** Created per platform by the actual implementation of this function. */
expect fun createTokenStorage(): TokenStorage

/**
 * The fallback used in tests and wherever no persistent store exists. Tokens live only as
 * long as the process, which is the safest possible default.
 */
class InMemoryTokenStorage : TokenStorage {

    private var accessToken: String? = null
    private var refreshToken: String? = null

    override fun readAccessToken(): String? = accessToken

    override fun readRefreshToken(): String? = refreshToken

    override fun save(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
    }
}
