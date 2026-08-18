package com.jmail.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.jmail.shared.JMailContainer
import com.jmail.shared.network.JMailAndroidContext

/**
 * Initialises the shared layer before any screen can ask for a token.
 *
 * Encrypted preferences need an application context, and the only place guaranteed to run
 * before the first Activity is here.
 */
class JMailApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        JMailAndroidContext.initialise(this)
    }
}

/**
 * The single Android activity.
 *
 * `singleTask` in the manifest means the OAuth redirect arrives at [onNewIntent] on the
 * *existing* instance, so the sign-in that is already in progress simply continues. Handling
 * it in `onCreate` alone would work only for a cold start and would silently drop the
 * callback in the common case.
 */
class MainActivity : ComponentActivity() {

    private var pendingHandoffCode by mutableStateOf<String?>(null)

    private val container: JMailContainer by lazy {
        JMailContainer(
            baseUrl = BuildConfig.JMAIL_API_URL,
            openUrl = ::openInBrowser,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingHandoffCode = handoffCodeFrom(intent)

        setContent {
            App(
                container = container,
                pendingHandoffCode = pendingHandoffCode,
                onHandoffConsumed = { pendingHandoffCode = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handoffCodeFrom(intent)?.let { code -> pendingHandoffCode = code }
    }

    override fun onDestroy() {
        if (isFinishing) container.dispose()
        super.onDestroy()
    }

    private fun openInBrowser(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    /** Reads `code` from a `jmail://auth/callback?code=…` intent. */
    private fun handoffCodeFrom(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        if (data.scheme != "jmail") return null
        return data.getQueryParameter("code")?.takeIf { it.isNotBlank() }
    }
}
