package com.jmail.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jmail.app.ui.components.LoadingState
import com.jmail.app.ui.compose.ComposeSheet
import com.jmail.app.ui.mailbox.MailboxScreen
import com.jmail.app.ui.settings.SettingsScreen
import com.jmail.app.ui.signin.SignInScreen
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.JMailContainer
import com.jmail.shared.model.UiDensity
import com.jmail.shared.model.UiTheme
import com.jmail.shared.model.UpdatePreferencesRequest
import com.jmail.shared.repository.SessionState
import kotlinx.coroutines.launch

/** Which top-level surface is showing. Deliberately tiny — JMail is not a deep app. */
private enum class Destination {
    MAILBOX,
    SETTINGS,

    /**
     * Connecting an extra mailbox while already signed in. It reuses the sign-in screen, and
     * because the API client sends the current session's token, the backend links the new
     * mailbox to this user instead of creating a second account.
     */
    ADD_ACCOUNT,
}

/**
 * The application root.
 *
 * Owns the three things that outlive any screen: the session, the stores, and the composer
 * (which floats above whatever else is on screen). Navigation is a single enum rather than a
 * navigation library — there are two destinations, and a graph would be more machinery than
 * the problem deserves.
 *
 * @param pendingHandoffCode a sign-in code delivered by a deep link or web redirect before
 *   the UI was ready; consumed once the sign-in screen is up.
 */
@Composable
fun App(
    container: JMailContainer,
    pendingHandoffCode: String? = null,
    onHandoffConsumed: () -> Unit = {},
) {
    val sessionState by container.sessionRepository.sessionState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val signInStore = remember { container.createSignInStore(coroutineScope) }
    val mailboxStore = remember { container.createMailboxStore(coroutineScope) }
    val readerStore = remember { container.createReaderStore(coroutineScope) }
    val composeStore = remember { container.createComposeStore(coroutineScope) }

    var destination by remember { mutableStateOf(Destination.MAILBOX) }

    /** Mailbox count when "add an account" was opened, used to detect a successful link. */
    var accountCountWhenAdding by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { container.sessionRepository.restore() }

    // A handoff code can arrive before or after the session is restored, so it is handled
    // here rather than inside the sign-in screen.
    LaunchedEffect(pendingHandoffCode) {
        pendingHandoffCode?.let { code ->
            signInStore.completeOAuthSignIn(code)
            onHandoffConsumed()
        }
    }

    val signedInUser = (sessionState as? SessionState.SignedIn)?.user

    JMailTheme(
        theme = signedInUser?.theme ?: UiTheme.SYSTEM,
        density = signedInUser?.density ?: UiDensity.COMFORTABLE,
    ) {
        Surface(Modifier.fillMaxSize()) {
            when (val session = sessionState) {
                is SessionState.Restoring -> LoadingState(label = "Opening JMail")

                is SessionState.SignedOut -> {
                    val signInState by signInStore.state.collectAsState()
                    SignInScreen(state = signInState, store = signInStore)
                }

                is SessionState.SignedIn -> {
                    val mailboxState by mailboxStore.state.collectAsState()
                    val readerState by readerStore.state.collectAsState()
                    val composeState by composeStore.state.collectAsState()

                    LaunchedEffect(session.user.id) { mailboxStore.start() }

                    // Sending updates the mailbox: the sent message belongs in the list.
                    LaunchedEffect(composeState.sentMessage) {
                        if (composeState.sentMessage != null) mailboxStore.refresh()
                    }

                    Box(Modifier.fillMaxSize()) {
                        when (destination) {
                            Destination.MAILBOX -> MailboxScreen(
                                state = mailboxState,
                                readerState = readerState,
                                accounts = session.user.accounts,
                                mailboxStore = mailboxStore,
                                readerStore = readerStore,
                                onCompose = { composeStore.newMessage() },
                                onReply = { message, replyAll ->
                                    composeStore.reply(message, replyAll, session.user.email)
                                },
                                onForward = composeStore::forward,
                                onOpenSettings = { destination = Destination.SETTINGS },
                                onOpenLink = container.openUrl,
                                // Reconnecting is the same journey as connecting: send the
                                // user to the sign-in screen rather than guessing which
                                // provider flow to start on their behalf.
                                onReconnectAccount = {
                                    accountCountWhenAdding = session.user.accounts.size
                                    destination = Destination.ADD_ACCOUNT
                                },
                            )

                            Destination.ADD_ACCOUNT -> {
                                val signInState by signInStore.state.collectAsState()

                                // The session's account list is the signal that the new
                                // mailbox is connected — it is what the backend returns once
                                // the credentials have been verified and stored.
                                val connectedCount = session.user.accounts.size
                                LaunchedEffect(connectedCount) {
                                    if (connectedCount > accountCountWhenAdding) {
                                        destination = Destination.MAILBOX
                                        mailboxStore.refresh()
                                    }
                                }

                                SignInScreen(
                                    state = signInState,
                                    store = signInStore,
                                    onCancel = { destination = Destination.MAILBOX },
                                )
                            }

                            Destination.SETTINGS -> SettingsScreen(
                                user = session.user,
                                categories = mailboxState.categories,
                                onBack = { destination = Destination.MAILBOX },
                                onThemeChange = { theme ->
                                    coroutineScope.launch {
                                        container.sessionRepository.updatePreferences(
                                            UpdatePreferencesRequest(theme = theme),
                                        )
                                    }
                                },
                                onDensityChange = { density ->
                                    coroutineScope.launch {
                                        container.sessionRepository.updatePreferences(
                                            UpdatePreferencesRequest(density = density),
                                        )
                                    }
                                },
                                onAddAccount = {
                                    accountCountWhenAdding = session.user.accounts.size
                                    destination = Destination.ADD_ACCOUNT
                                },
                                onUnlinkAccount = { account ->
                                    coroutineScope.launch {
                                        container.sessionRepository.unlinkAccount(account.id)
                                    }
                                },
                                onSignOut = {
                                    coroutineScope.launch { container.sessionRepository.signOut() }
                                },
                            )
                        }

                        // The composer slides over everything, on every platform: on a phone
                        // it is effectively full screen, on desktop it overlays the panes.
                        AnimatedVisibility(
                            visible = composeState.isOpen,
                            enter = slideInVertically { height -> height },
                            exit = slideOutVertically { height -> height },
                        ) {
                            ComposeSheet(
                                state = composeState,
                                store = composeStore,
                                onClose = composeStore::close,
                            )
                        }
                    }
                }
            }
        }
    }
}
