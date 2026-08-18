package com.jmail.app.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.app.ui.theme.parseHexColor
import com.jmail.shared.model.Category
import com.jmail.shared.model.MailAccount
import com.jmail.shared.model.UiDensity
import com.jmail.shared.model.UiTheme
import com.jmail.shared.model.User
import com.jmail.shared.util.Formatting

/**
 * Settings.
 *
 * Appearance first because it is what people come here to change, accounts second because
 * it is what they come here to fix, and destructive actions last and clearly separated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: User,
    categories: List<Category>,
    onBack: () -> Unit,
    onThemeChange: (UiTheme) -> Unit,
    onDensityChange: (UiDensity) -> Unit,
    onAddAccount: () -> Unit,
    onUnlinkAccount: (MailAccount) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var accountPendingRemoval by remember { mutableStateOf<MailAccount?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(JMailTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to mail")
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(JMailTheme.spacing.large),
        ) {
            Column(Modifier.widthIn(max = 720.dp)) {
                SettingsSection("Appearance") {
                    SettingRow(
                        title = "Theme",
                        description = "Match your system, or pick one and stay with it.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small)) {
                            UiTheme.entries.forEach { theme ->
                                FilterChip(
                                    selected = user.theme == theme,
                                    onClick = { onThemeChange(theme) },
                                    label = {
                                        Text(theme.name.lowercase().replaceFirstChar(Char::uppercase))
                                    },
                                )
                            }
                        }
                    }

                    SettingRow(
                        title = "Density",
                        description = "How much breathing room the message list gets. " +
                            "Text size is never reduced.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small)) {
                            UiDensity.entries.forEach { density ->
                                FilterChip(
                                    selected = user.density == density,
                                    onClick = { onDensityChange(density) },
                                    label = {
                                        Text(density.name.lowercase().replaceFirstChar(Char::uppercase))
                                    },
                                )
                            }
                        }
                    }
                }

                SettingsSection("Accounts") {
                    user.accounts.forEach { account ->
                        AccountCard(
                            account = account,
                            canRemove = user.accounts.size > 1,
                            onRemove = { accountPendingRemoval = account },
                        )
                        Spacer(Modifier.height(JMailTheme.spacing.small))
                    }

                    OutlinedButton(onClick = onAddAccount) {
                        Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(JMailTheme.spacing.small))
                        Text("Add another mailbox")
                    }
                }

                SettingsSection("Categories") {
                    Text(
                        text = "Mail is filed into these automatically. Anything you move by hand " +
                            "stays where you put it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(JMailTheme.spacing.medium))

                    categories.forEach { category ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = JMailTheme.spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(category.color)),
                            )
                            Spacer(Modifier.width(JMailTheme.spacing.medium))
                            Column(Modifier.weight(1f)) {
                                Text(category.name, style = MaterialTheme.typography.bodyMedium)
                                category.description?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                text = "${category.ruleCount} rules",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SettingsSection("Session") {
                    Text(
                        text = "Signed in as ${user.email}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(JMailTheme.spacing.medium))
                    OutlinedButton(onClick = onSignOut) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(JMailTheme.spacing.small))
                        Text("Sign out")
                    }
                }
            }
        }
    }

    accountPendingRemoval?.let { account ->
        AlertDialog(
            onDismissRequest = { accountPendingRemoval = null },
            title = { Text("Disconnect ${account.email}?") },
            text = {
                Text(
                    "JMail will forget this mailbox and remove the messages it synced. " +
                        "Nothing is deleted from ${account.providerName}.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnlinkAccount(account)
                        accountPendingRemoval = null
                    },
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountPendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(JMailTheme.spacing.betweenSections))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(JMailTheme.spacing.medium))
    Column { content() }
    Spacer(Modifier.height(JMailTheme.spacing.small))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    control: @Composable () -> Unit,
) {
    Column(Modifier.padding(vertical = JMailTheme.spacing.small)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(JMailTheme.spacing.small))
        control()
    }
}

@Composable
private fun AccountCard(
    account: MailAccount,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (account.needsAttention) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(JMailTheme.spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(account.email, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = buildString {
                        append(account.providerName)
                        if (account.isPrimary) append(" · default for new mail")
                        append(" · synced ")
                        append(Formatting.relativeTime(account.lastSyncAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                account.statusDetail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Disconnect ${account.email}",
                    )
                }
            }
        }
    }
}
