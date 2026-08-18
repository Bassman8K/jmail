package com.jmail.shared.state

import com.jmail.shared.model.ComposeRequest
import com.jmail.shared.model.EmailAddress
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.model.RecipientInput
import com.jmail.shared.network.ApiError
import com.jmail.shared.network.ApiResult
import com.jmail.shared.repository.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the composer is open, which decides how it is pre-filled and titled. */
enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, EDIT_DRAFT }

data class ComposeUiState(
    val isOpen: Boolean = false,
    val mode: ComposeMode = ComposeMode.NEW,
    val accountId: String? = null,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val toInput: String = "",
    val ccInput: String = "",
    val bccInput: String = "",
    val subject: String = "",
    val body: String = "",
    val showCcBcc: Boolean = false,
    val inReplyToMessageId: String? = null,
    val threadId: String? = null,
    val isSending: Boolean = false,
    val isSavingDraft: Boolean = false,
    val error: ApiError? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val sentMessage: MessageDetail? = null,
) {
    val canSend: Boolean get() = to.isNotEmpty() && body.isNotBlank() && !isSending

    /** Closing with unsaved content should prompt rather than silently discard it. */
    val hasUnsavedContent: Boolean
        get() = to.isNotEmpty() || subject.isNotBlank() || body.isNotBlank()

    val title: String
        get() = when (mode) {
            ComposeMode.NEW -> "New message"
            ComposeMode.REPLY -> "Reply"
            ComposeMode.REPLY_ALL -> "Reply all"
            ComposeMode.FORWARD -> "Forward"
            ComposeMode.EDIT_DRAFT -> "Draft"
        }
}

/**
 * The composer.
 *
 * Reply pre-fill is handled here rather than in the UI because getting it right — who ends
 * up on the To line for a reply-all, how the subject is prefixed, how the quoted text reads
 * — is logic worth testing, not layout.
 */
class ComposeStore(
    private val repository: MailRepository,
    private val scope: CoroutineScope,
) {

    private val internalState = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = internalState.asStateFlow()

    fun newMessage(accountId: String? = null) {
        internalState.value = ComposeUiState(isOpen = true, mode = ComposeMode.NEW, accountId = accountId)
    }

    fun reply(message: MessageDetail, replyAll: Boolean = false, selfAddress: String? = null) {
        val replyTo = message.replyTo?.let { EmailAddress(it) } ?: message.from

        // Reply-all keeps everyone except the sender's duplicate and the user themselves —
        // nothing makes a mail client feel worse than putting you on your own To line.
        val others = if (replyAll) {
            (message.to + message.cc)
                .filter { it.address != selfAddress && it.address != replyTo.address }
                .distinctBy(EmailAddress::address)
        } else {
            emptyList()
        }

        internalState.value = ComposeUiState(
            isOpen = true,
            mode = if (replyAll) ComposeMode.REPLY_ALL else ComposeMode.REPLY,
            accountId = message.accountId,
            to = listOf(replyTo),
            cc = others,
            showCcBcc = others.isNotEmpty(),
            subject = prefixSubject(message.subject, "Re:"),
            body = "\n\n" + quote(message),
            inReplyToMessageId = message.id,
            threadId = message.threadId,
        )
    }

    fun forward(message: MessageDetail) {
        internalState.value = ComposeUiState(
            isOpen = true,
            mode = ComposeMode.FORWARD,
            accountId = message.accountId,
            subject = prefixSubject(message.subject, "Fwd:"),
            body = "\n\n" + quote(message, forwarded = true),
        )
    }

    fun close() {
        internalState.value = ComposeUiState()
    }

    // ---- editing ----------------------------------------------------------

    fun updateToInput(value: String) = updateRecipientInput(value, RecipientField.TO)

    fun updateCcInput(value: String) = updateRecipientInput(value, RecipientField.CC)

    fun updateBccInput(value: String) = updateRecipientInput(value, RecipientField.BCC)

    fun commitRecipient(field: RecipientField) {
        val current = internalState.value
        val raw = when (field) {
            RecipientField.TO -> current.toInput
            RecipientField.CC -> current.ccInput
            RecipientField.BCC -> current.bccInput
        }

        val address = raw.trim().trim(',', ';')
        if (address.isBlank()) return

        if (!isPlausibleAddress(address)) {
            internalState.update {
                it.copy(fieldErrors = it.fieldErrors + (field.key to "\"$address\" is not a valid address"))
            }
            return
        }

        internalState.update { state ->
            val recipient = EmailAddress(address.lowercase())
            when (field) {
                RecipientField.TO -> state.copy(
                    to = (state.to + recipient).distinctBy(EmailAddress::address),
                    toInput = "",
                    fieldErrors = state.fieldErrors - field.key,
                )
                RecipientField.CC -> state.copy(
                    cc = (state.cc + recipient).distinctBy(EmailAddress::address),
                    ccInput = "",
                    fieldErrors = state.fieldErrors - field.key,
                )
                RecipientField.BCC -> state.copy(
                    bcc = (state.bcc + recipient).distinctBy(EmailAddress::address),
                    bccInput = "",
                    fieldErrors = state.fieldErrors - field.key,
                )
            }
        }
    }

    fun removeRecipient(field: RecipientField, address: String) = internalState.update { state ->
        when (field) {
            RecipientField.TO -> state.copy(to = state.to.filterNot { it.address == address })
            RecipientField.CC -> state.copy(cc = state.cc.filterNot { it.address == address })
            RecipientField.BCC -> state.copy(bcc = state.bcc.filterNot { it.address == address })
        }
    }

    fun updateSubject(value: String) = internalState.update { it.copy(subject = value) }

    fun updateBody(value: String) = internalState.update { it.copy(body = value) }

    fun toggleCcBcc() = internalState.update { it.copy(showCcBcc = !it.showCcBcc) }

    fun setAccount(accountId: String) = internalState.update { it.copy(accountId = accountId) }

    fun dismissError() = internalState.update { it.copy(error = null) }

    // ---- sending ----------------------------------------------------------

    fun send() {
        // A recipient typed but never committed (no Enter pressed) is still meant to be sent to.
        commitPendingInputs()

        val current = internalState.value
        if (current.to.isEmpty()) {
            internalState.update { it.copy(fieldErrors = it.fieldErrors + ("to" to "Add at least one recipient")) }
            return
        }
        if (current.body.isBlank()) {
            internalState.update { it.copy(fieldErrors = it.fieldErrors + ("body" to "Write a message first")) }
            return
        }

        scope.launch {
            internalState.update { it.copy(isSending = true, error = null) }

            when (val result = repository.send(current.toRequest(saveAsDraft = false))) {
                is ApiResult.Success -> internalState.value = ComposeUiState(sentMessage = result.value)
                is ApiResult.Failure -> internalState.update {
                    it.copy(isSending = false, error = result.error)
                }
            }
        }
    }

    fun saveDraft() {
        commitPendingInputs()
        val current = internalState.value
        if (!current.hasUnsavedContent) return

        scope.launch {
            internalState.update { it.copy(isSavingDraft = true, error = null) }

            when (val result = repository.send(current.toRequest(saveAsDraft = true))) {
                is ApiResult.Success -> internalState.value = ComposeUiState()
                is ApiResult.Failure -> internalState.update {
                    it.copy(isSavingDraft = false, error = result.error)
                }
            }
        }
    }

    private fun commitPendingInputs() {
        if (internalState.value.toInput.isNotBlank()) commitRecipient(RecipientField.TO)
        if (internalState.value.ccInput.isNotBlank()) commitRecipient(RecipientField.CC)
        if (internalState.value.bccInput.isNotBlank()) commitRecipient(RecipientField.BCC)
    }

    /** Typing a separator commits the address, the interaction people expect from mail apps. */
    private fun updateRecipientInput(value: String, field: RecipientField) {
        val endsWithSeparator = value.endsWith(',') || value.endsWith(';') || value.endsWith(' ')

        internalState.update { state ->
            when (field) {
                RecipientField.TO -> state.copy(toInput = value)
                RecipientField.CC -> state.copy(ccInput = value)
                RecipientField.BCC -> state.copy(bccInput = value)
            }
        }

        if (endsWithSeparator && value.trim().trim(',', ';').isNotEmpty()) commitRecipient(field)
    }

    private fun ComposeUiState.toRequest(saveAsDraft: Boolean) = ComposeRequest(
        accountId = accountId,
        to = to.map { RecipientInput(it.address, it.name) },
        cc = cc.map { RecipientInput(it.address, it.name) },
        bcc = bcc.map { RecipientInput(it.address, it.name) },
        subject = subject,
        bodyText = body,
        inReplyToMessageId = inReplyToMessageId,
        threadId = threadId,
        saveAsDraft = saveAsDraft,
    )

    enum class RecipientField(val key: String) { TO("to"), CC("cc"), BCC("bcc") }

    internal companion object {

        /** "Re: Re: Lunch" is noise; one prefix is enough however deep the thread runs. */
        fun prefixSubject(subject: String, prefix: String): String {
            val trimmed = subject.trim()
            return if (trimmed.startsWith(prefix, ignoreCase = true)) trimmed else "$prefix $trimmed".trim()
        }

        fun quote(message: MessageDetail, forwarded: Boolean = false): String {
            val header = if (forwarded) {
                "---------- Forwarded message ----------\nFrom: ${message.from.displayLabel}\n" +
                    "Subject: ${message.displaySubject}\nTo: ${message.to.joinToString { it.displayLabel }}"
            } else {
                "On ${message.sentAt}, ${message.from.displayLabel} wrote:"
            }

            val quoted = (message.bodyText ?: "")
                .lineSequence()
                .joinToString("\n") { line -> "> $line" }

            return "$header\n$quoted"
        }

        /** Shape check only; the server is the authority on deliverability. */
        fun isPlausibleAddress(value: String): Boolean {
            val parts = value.split('@')
            if (parts.size != 2) return false
            val (local, domain) = parts
            return local.isNotEmpty() &&
                domain.contains('.') &&
                !domain.startsWith('.') &&
                !domain.endsWith('.') &&
                domain.substringAfterLast('.').length >= 2 &&
                value.none(Char::isWhitespace)
        }
    }
}
