package com.jmail.app.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.EmailAddress
import com.jmail.shared.state.ComposeStore
import com.jmail.shared.state.ComposeUiState

/**
 * The composer.
 *
 * Recipients are chips rather than a raw text field: an address that has been accepted looks
 * different from one still being typed, which is the difference between confidently sending
 * and hoping. Enter, comma and semicolon all commit — people type all three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeSheet(
    state: ComposeUiState,
    store: ComposeStore,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardPrompt by remember { mutableStateOf(false) }
    val toFocusRequester = remember { FocusRequester() }

    val requestClose = {
        if (state.hasUnsavedContent) showDiscardPrompt = true else onClose()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = JMailTheme.spacing.large,
                        vertical = JMailTheme.spacing.medium,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = requestClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close the composer")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                Modifier
                    .weight(1f)
                    .padding(JMailTheme.spacing.large),
            ) {
                RecipientField(
                    label = "To",
                    recipients = state.to,
                    input = state.toInput,
                    error = state.fieldErrors["to"],
                    onInputChange = store::updateToInput,
                    onCommit = { store.commitRecipient(ComposeStore.RecipientField.TO) },
                    onRemove = { store.removeRecipient(ComposeStore.RecipientField.TO, it) },
                    modifier = Modifier.focusRequester(toFocusRequester),
                    trailing = {
                        TextButton(onClick = store::toggleCcBcc) {
                            Text(if (state.showCcBcc) "Hide Cc/Bcc" else "Cc/Bcc")
                        }
                    },
                )

                if (state.showCcBcc) {
                    Spacer(Modifier.height(JMailTheme.spacing.small))
                    RecipientField(
                        label = "Cc",
                        recipients = state.cc,
                        input = state.ccInput,
                        error = state.fieldErrors["cc"],
                        onInputChange = store::updateCcInput,
                        onCommit = { store.commitRecipient(ComposeStore.RecipientField.CC) },
                        onRemove = { store.removeRecipient(ComposeStore.RecipientField.CC, it) },
                    )
                    Spacer(Modifier.height(JMailTheme.spacing.small))
                    RecipientField(
                        label = "Bcc",
                        recipients = state.bcc,
                        input = state.bccInput,
                        error = state.fieldErrors["bcc"],
                        onInputChange = store::updateBccInput,
                        onCommit = { store.commitRecipient(ComposeStore.RecipientField.BCC) },
                        onRemove = { store.removeRecipient(ComposeStore.RecipientField.BCC, it) },
                    )
                }

                Spacer(Modifier.height(JMailTheme.spacing.small))

                OutlinedTextField(
                    value = state.subject,
                    onValueChange = store::updateSubject,
                    label = { Text("Subject") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Spacer(Modifier.height(JMailTheme.spacing.medium))

                OutlinedTextField(
                    value = state.body,
                    onValueChange = store::updateBody,
                    label = { Text("Message") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 180.dp),
                    isError = state.fieldErrors.containsKey("body"),
                    supportingText = state.fieldErrors["body"]?.let { { Text(it) } },
                )

                state.error?.let { error ->
                    Spacer(Modifier.height(JMailTheme.spacing.medium))
                    Text(
                        text = error.userMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(JMailTheme.spacing.large),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.small),
            ) {
                Button(
                    onClick = store::send,
                    enabled = state.canSend,
                    modifier = Modifier.semantics { contentDescription = "Send this message" },
                ) {
                    if (state.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(JMailTheme.spacing.small))
                    Text(if (state.isSending) "Sending…" else "Send")
                }

                TextButton(onClick = store::saveDraft, enabled = !state.isSending) {
                    Text(if (state.isSavingDraft) "Saving…" else "Save draft")
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = "${state.to.size + state.cc.size + state.bcc.size} recipients",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDiscardPrompt) {
        AlertDialog(
            onDismissRequest = { showDiscardPrompt = false },
            title = { Text("Discard this message?") },
            text = { Text("You have unsent changes. Save it as a draft to come back to it later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardPrompt = false
                        onClose()
                    },
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardPrompt = false
                        store.saveDraft()
                    },
                ) {
                    Text("Save draft")
                }
            },
        )
    }
}

/**
 * One recipient line: committed addresses as chips, plus a field for the next one.
 *
 * Backspace on an empty field removes the last chip, which is the interaction people already
 * have in their fingers from every other mail client.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecipientField(
    label: String,
    recipients: List<EmailAddress>,
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (recipients.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(JMailTheme.spacing.tight),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = JMailTheme.spacing.tight),
            ) {
                recipients.forEach { recipient ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(recipient.address) },
                        label = { Text(recipient.displayLabel) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${recipient.address}",
                                Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false

                            event.key == Key.Enter || event.key == Key.Tab -> {
                                onCommit()
                                true
                            }

                            event.key == Key.Backspace && input.isEmpty() && recipients.isNotEmpty() -> {
                                onRemove(recipients.last().address)
                                true
                            }

                            else -> false
                        }
                    },
            )

            trailing?.invoke()
        }
    }
}
