package com.jmail.shared.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android token storage, backed by `EncryptedSharedPreferences`.
 *
 * The encryption key lives in the hardware-backed keystore, so the tokens are unreadable
 * even with physical access to the device's storage. If the keystore is unavailable — a
 * genuine failure mode on some manufacturer ROMs — the storage degrades to in-memory rather
 * than falling back to plaintext on disk.
 */
private class AndroidTokenStorage(context: Context) : TokenStorage {

    private val fallback = InMemoryTokenStorage()

    private val preferences: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "jmail.session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    override fun readAccessToken(): String? =
        preferences?.getString(ACCESS_TOKEN_KEY, null) ?: fallback.readAccessToken()

    override fun readRefreshToken(): String? =
        preferences?.getString(REFRESH_TOKEN_KEY, null) ?: fallback.readRefreshToken()

    override fun save(accessToken: String, refreshToken: String) {
        val editor = preferences?.edit()
        if (editor == null) {
            fallback.save(accessToken, refreshToken)
            return
        }
        editor.putString(ACCESS_TOKEN_KEY, accessToken)
            .putString(REFRESH_TOKEN_KEY, refreshToken)
            .apply()
    }

    override fun clear() {
        preferences?.edit()?.clear()?.apply()
        fallback.clear()
    }

    private companion object {
        const val ACCESS_TOKEN_KEY = "accessToken"
        const val REFRESH_TOKEN_KEY = "refreshToken"
    }
}

/**
 * Holds the application context needed to open encrypted preferences.
 *
 * Set once from `Application.onCreate`. The *application* context is deliberately the only
 * thing stored — holding an Activity here would leak it for the life of the process.
 */
object JMailAndroidContext {

    @SuppressLint("StaticFieldLeak")
    @Volatile
    internal var applicationContext: Context? = null

    fun initialise(context: Context) {
        applicationContext = context.applicationContext
    }
}

actual fun createTokenStorage(): TokenStorage {
    val context = JMailAndroidContext.applicationContext
        ?: return InMemoryTokenStorage() // initialise() not called: stay usable, do not crash
    return AndroidTokenStorage(context)
}
