package com.jmail.shared.network

import platform.Foundation.NSUserDefaults

/**
 * iOS token storage.
 *
 * `NSUserDefaults` lives inside the app sandbox and is encrypted at rest with the device
 * passcode under the default data-protection class, so it is not readable by other apps.
 * The Keychain would additionally survive reinstalls — which for a session token is a
 * downside rather than a feature: deleting the app should end the session.
 */
private class IosTokenStorage : TokenStorage {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun readAccessToken(): String? = defaults.stringForKey(ACCESS_TOKEN_KEY)

    override fun readRefreshToken(): String? = defaults.stringForKey(REFRESH_TOKEN_KEY)

    override fun save(accessToken: String, refreshToken: String) {
        defaults.setObject(accessToken, ACCESS_TOKEN_KEY)
        defaults.setObject(refreshToken, REFRESH_TOKEN_KEY)
    }

    override fun clear() {
        defaults.removeObjectForKey(ACCESS_TOKEN_KEY)
        defaults.removeObjectForKey(REFRESH_TOKEN_KEY)
    }

    private companion object {
        const val ACCESS_TOKEN_KEY = "jmail.accessToken"
        const val REFRESH_TOKEN_KEY = "jmail.refreshToken"
    }
}

actual fun createTokenStorage(): TokenStorage = IosTokenStorage()
