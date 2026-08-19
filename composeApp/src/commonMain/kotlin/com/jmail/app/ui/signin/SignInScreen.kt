package com.jmail.app.ui.signin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.components.ErrorState
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.ProviderSummary
import com.jmail.shared.model.SignInKind
import com.jmail.shared.state.SignInStep
import com.jmail.shared.state.SignInStore
import com.jmail.shared.state.SignInUiState

/**
 * The sign-in screen — the first thing anyone sees, and the only screen where a moment of
 * confusion costs you the user entirely.
 *
 * Only methods the server can actually complete are shown, so there is never a button that
 * leads to "not configured". The Exchange path is a separate step rather than a form buried
 * under the OAuth buttons, because the people who need it know they need it and everyone
 * else should never see a host field.
 */
@Composable
fun SignInScreen(
    state: SignInUiState,
    store: SignInStore,
    modifier: Modifier = Modifier,
    /**
     * Set when this screen is being used to add a mailbox to an account that is already
     * signed in, rather than to sign in from scratch. It gives the user a way back, which
     * they otherwise would not have.
     */
    onCancel: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { store.start() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(JMailTheme.spacing.generous),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onCancel != null) {
                Text(
                    text = "Add another mailbox",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(JMailTheme.spacing.large))
            }

            when (state.step) {
                SignInStep.CHOOSE_PROVIDER -> ProviderChooser(state, store)
                SignInStep.AWAITING_PROVIDER -> AwaitingProvider(store)
            }

            if (onCancel != null) {
                Spacer(Modifier.height(JMailTheme.spacing.large))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ProviderChooser(state: SignInUiState, store: SignInStore) {
    Brandmark()

    Spacer(Modifier.height(JMailTheme.spacing.generous))

    Text(
        text = "All your mail, in one place",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(JMailTheme.spacing.small))
    Text(
        text = "Connect Google, Microsoft or Apple. Add as many accounts as you like — " +
            "JMail keeps them in one inbox.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(JMailTheme.spacing.generous))

    when {
        state.isLoadingProviders -> CircularProgressIndicator()

        state.providers.isEmpty() && state.error != null ->
            ErrorState(error = state.error!!, onRetry = store::start)

        state.providers.isEmpty() -> Text(
            text = "No sign-in methods are configured on this server yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        else -> Column(
            verticalArrangement = Arrangement.spacedBy(JMailTheme.spacing.medium),
            modifier = Modifier.fillMaxWidth(),
        ) {
            state.providers.filter { it.kind == SignInKind.OAUTH }.forEach { provider ->
                ProviderButton(
                    provider = provider,
                    enabled = !state.isSubmitting,
                    onClick = { store.chooseProvider(provider) },
                )
            }

            if (!state.hasOAuthProviders) {
                Spacer(Modifier.height(JMailTheme.spacing.tight))
                Text(
                    text = "No mail provider is configured on this server yet. See " +
                        "docs/CONNECTING-ACCOUNTS.md to add \"Continue with Google\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.demoProvider?.let { provider ->
                Spacer(Modifier.height(JMailTheme.spacing.small))
                TextButton(
                    onClick = { store.chooseProvider(provider) },
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Explore, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(JMailTheme.spacing.small))
                    Text("Explore the demo mailbox")
                }
                Text(
                    text = "No account needed. A realistic inbox to look around in.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (state.error != null && state.providers.isNotEmpty()) {
        Spacer(Modifier.height(JMailTheme.spacing.large))
        Text(
            text = state.error!!.userMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProviderButton(
    provider: ProviderSummary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = provider.displayName },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Outlined.Mail, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(JMailTheme.spacing.medium))
        Text(provider.displayName, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AwaitingProvider(store: SignInStore) {
    CircularProgressIndicator()
    Spacer(Modifier.height(JMailTheme.spacing.betweenSections))
    Text(
        text = "Finish signing in in your browser",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(JMailTheme.spacing.small))
    Text(
        text = "JMail opened a secure sign-in page. Come back here once you have approved access.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(JMailTheme.spacing.betweenSections))
    TextButton(onClick = { store.cancelOAuthSignIn() }) { Text("Use a different account") }
}

@Composable
private fun Brandmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Mail,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(JMailTheme.spacing.medium))
        Text(
            text = "JMail",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
