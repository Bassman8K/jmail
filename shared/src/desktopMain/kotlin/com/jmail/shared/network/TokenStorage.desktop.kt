package com.jmail.shared.network

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Desktop token storage: a file under the user's home directory, readable only by them.
 *
 * The OS keychains (macOS Keychain, Windows Credential Manager, libsecret) would be better
 * still, but each needs a native dependency and none is available on all three. An
 * owner-only file is the strongest protection available with no platform-specific code, and
 * the tokens it holds are short-lived and individually revocable.
 */
private class DesktopTokenStorage : TokenStorage {

    private val directory: Path = Paths.get(System.getProperty("user.home"), ".jmail")
    private val sessionFile: Path = directory.resolve("session")

    override fun readAccessToken(): String? = read()?.first

    override fun readRefreshToken(): String? = read()?.second

    override fun save(accessToken: String, refreshToken: String) {
        runCatching {
            Files.createDirectories(directory)
            restrictPermissions(directory, directoryPermissions)

            Files.writeString(sessionFile, "$accessToken\n$refreshToken")
            restrictPermissions(sessionFile, filePermissions)
        }
    }

    override fun clear() {
        runCatching { Files.deleteIfExists(sessionFile) }
    }

    private fun read(): Pair<String, String>? = runCatching {
        if (!Files.exists(sessionFile)) return null
        val lines = Files.readAllLines(sessionFile)
        if (lines.size < 2) return null
        lines[0] to lines[1]
    }.getOrNull()

    /** No-op on filesystems without POSIX permissions (Windows), which is expected. */
    private fun restrictPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }

    private val filePermissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    private val directoryPermissions = filePermissions + PosixFilePermission.OWNER_EXECUTE
}

actual fun createTokenStorage(): TokenStorage = DesktopTokenStorage()
