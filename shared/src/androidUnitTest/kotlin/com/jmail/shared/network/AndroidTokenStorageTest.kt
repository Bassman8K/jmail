package com.jmail.shared.network

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Android session store.
 *
 * `EncryptedSharedPreferences` needs a hardware-backed keystore, which Robolectric does not
 * emulate — and that is exactly the case worth testing. Some manufacturer ROMs fail the same
 * way on real devices, and the requirement is that JMail degrades to holding the session in
 * memory rather than either crashing on launch or quietly writing tokens to disk in
 * plaintext. Every assertion here is about that fallback behaving like a real store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTokenStorageTest {

    @Before
    fun clearContext() {
        JMailAndroidContext.applicationContext = null
    }

    @After
    fun tearDown() {
        JMailAndroidContext.applicationContext = null
    }

    private fun initialised(): TokenStorage {
        JMailAndroidContext.initialise(ApplicationProvider.getApplicationContext())
        return createTokenStorage()
    }

    @Test
    fun `before initialise the app still gets a usable store rather than a crash`() {
        // Reached if a process is restarted straight into a service without Application
        // having run. Signed out is recoverable; a crash on launch is not.
        val storage = createTokenStorage()

        storage.save("access", "refresh")

        assertEquals("access", storage.readAccessToken())
        assertEquals("refresh", storage.readRefreshToken())
    }

    @Test
    fun `initialise keeps the application context, not the one it was handed`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()

        JMailAndroidContext.initialise(context)

        // Holding an Activity here would leak it for the life of the process.
        assertEquals(context.applicationContext, JMailAndroidContext.applicationContext)
    }

    @Test
    fun `a saved session reads back`() {
        // Deliberately one instance: when the keystore is unavailable the store is backed by
        // memory, so persistence across instances is not something it can promise here. What
        // it must promise is that a session saved during a run is readable during that run.
        val storage = initialised()
        storage.save("the-access-token", "the-refresh-token")

        assertEquals("the-access-token", storage.readAccessToken())
        assertEquals("the-refresh-token", storage.readRefreshToken())
    }

    @Test
    fun `nothing is returned before a session is saved`() {
        val storage = initialised()

        assertNull(storage.readAccessToken())
        assertNull(storage.readRefreshToken())
    }

    @Test
    fun `saving again replaces the previous session`() {
        val storage = initialised()

        storage.save("first-access", "first-refresh")
        storage.save("second-access", "second-refresh")

        assertEquals("second-access", storage.readAccessToken())
        assertEquals("second-refresh", storage.readRefreshToken())
    }

    @Test
    fun `clearing signs the user out`() {
        val storage = initialised()
        storage.save("access", "refresh")

        storage.clear()

        assertNull(storage.readAccessToken())
        assertNull(storage.readRefreshToken())
    }

    @Test
    fun `clearing a store that holds nothing is not an error`() {
        val storage = initialised()

        storage.clear()

        assertNull(storage.readAccessToken())
    }
}
