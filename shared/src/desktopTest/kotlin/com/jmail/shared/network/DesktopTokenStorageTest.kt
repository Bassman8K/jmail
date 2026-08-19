package com.jmail.shared.network

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop session file.
 *
 * Two things matter and neither is visible from the app: the tokens must not be readable by
 * other users on a shared machine, and an unreadable or half-written file must sign the user
 * out rather than crash the launch. Every case here is one that leaves a real user unable to
 * open the app if it is got wrong.
 *
 * `user.home` is redirected at a temporary directory so the test never touches the real one.
 */
class DesktopTokenStorageTest {

    private lateinit var home: Path
    private var originalHome: String? = null

    private val sessionFile: Path get() = home.resolve(".jmail/session")

    @BeforeTest
    fun redirectHome() {
        originalHome = System.getProperty("user.home")
        home = Files.createTempDirectory("jmail-home")
        System.setProperty("user.home", home.toString())
    }

    @AfterTest
    fun restoreHome() {
        originalHome?.let { System.setProperty("user.home", it) }
        home.toFile().deleteRecursively()
    }

    // The path is read when the storage is constructed, so it is built after the redirect.
    private fun storage(): TokenStorage = createTokenStorage()

    @Test
    fun tokens_survive_a_restart() {
        storage().save("the-access-token", "the-refresh-token")

        // A second instance is what the next launch gets.
        val reopened = storage()
        assertEquals("the-access-token", reopened.readAccessToken())
        assertEquals("the-refresh-token", reopened.readRefreshToken())
    }

    @Test
    fun nothing_is_returned_before_anything_is_saved() {
        assertNull(storage().readAccessToken())
        assertNull(storage().readRefreshToken())
    }

    @Test
    fun saving_again_replaces_the_previous_session() {
        val storage = storage()
        storage.save("first-access", "first-refresh")
        storage.save("second-access", "second-refresh")

        assertEquals("second-access", storage.readAccessToken())
        assertEquals("second-refresh", storage.readRefreshToken())
        // The old token must not be left behind at the end of the file.
        assertFalse(sessionFile.readText().contains("first-"))
    }

    @Test
    fun clearing_removes_the_file_rather_than_blanking_it() {
        val storage = storage()
        storage.save("access", "refresh")
        assertTrue(sessionFile.exists())

        storage.clear()

        assertFalse(sessionFile.exists())
        assertNull(storage.readAccessToken())
    }

    @Test
    fun clearing_a_session_that_was_never_saved_is_not_an_error() {
        storage().clear()
        assertFalse(sessionFile.exists())
    }

    @Test
    fun the_session_file_is_readable_only_by_its_owner() {
        // The whole reason for a file rather than a keychain entry is that this permission
        // is the protection. Losing it puts refresh tokens in reach of every local account.
        storage().save("access", "refresh")

        val permissions = Files.getPosixFilePermissions(sessionFile)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
        )

        val directoryPermissions = Files.getPosixFilePermissions(home.resolve(".jmail"))
        assertFalse(PosixFilePermission.GROUP_READ in directoryPermissions)
        assertFalse(PosixFilePermission.OTHERS_READ in directoryPermissions)
    }

    @Test
    fun a_truncated_file_signs_the_user_out_instead_of_crashing() {
        // An interrupted write leaves the access token with no refresh token after it.
        // Returning half a session would fail later with a confusing 401 loop.
        home.resolve(".jmail").createDirectories()
        sessionFile.writeText("only-one-line")

        assertNull(storage().readAccessToken())
        assertNull(storage().readRefreshToken())
    }

    @Test
    fun an_empty_file_is_treated_as_no_session() {
        home.resolve(".jmail").createDirectories()
        sessionFile.writeText("")

        assertNull(storage().readAccessToken())
    }

    @Test
    fun a_home_that_cannot_be_written_to_does_not_bring_the_app_down() {
        // A locked-down profile or a read-only volume. `save` cannot create ~/.jmail, and
        // the app must still start — signed out — rather than failing on launch.
        // (Note the directory itself is not enough: save() re-applies its own permissions
        // to ~/.jmail before writing, so the unwritable parent is what actually blocks it.)
        val storage = storage()
        Files.setPosixFilePermissions(
            home,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
        )

        try {
            storage.save("access", "refresh")
            assertNull(storage.readAccessToken())
            assertFalse(sessionFile.exists())
        } finally {
            Files.setPosixFilePermissions(
                home,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }
}
