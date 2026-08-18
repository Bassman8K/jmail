package com.jmail.shared.state

import com.jmail.shared.model.ExchangeSignInRequest
import com.jmail.shared.model.MailProviderOption
import com.jmail.shared.model.ProviderSummary
import com.jmail.shared.model.SignInKind
import com.jmail.shared.network.ApiError
import com.jmail.shared.network.ApiResult
import com.jmail.shared.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which step of sign-in is on screen. */
enum class SignInStep {
    /** The provider buttons. */
    CHOOSE_PROVIDER,

    /** The browser is open at the provider; we are waiting for the redirect back. */
    AWAITING_PROVIDER,

    /** Picking which mail service the address belongs to. */
    CHOOSE_MAIL_SERVICE,

    /** The address-and-password form. */
    EXCHANGE_CREDENTIALS,
}

data class SignInUiState(
    val step: SignInStep = SignInStep.CHOOSE_PROVIDER,
    val providers: List<ProviderSummary> = emptyList(),
    val isLoadingProviders: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: ApiError? = null,

    // Exchange form
    val email: String = "",
    val password: String = "",
    val imapHost: String = "",
    val imapPort: String = "993",
    val smtpHost: String = "",
    val smtpPort: String = "587",
    val useTls: Boolean = true,
    val showAdvanced: Boolean = false,
    val settingsWereSuggested: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),

    // The mail service picker
    val mailProviders: List<MailProviderOption> = emptyList(),
    val selectedMailProvider: MailProviderOption? = null,
    /** Set once the typed address identifies a service, even if none was picked. */
    val detectedProviderName: String? = null,
    val requiresAppPassword: Boolean = false,
    val appPasswordUrl: String? = null,
    val providerHelpText: String? = null,
) {
    val canSubmitExchange: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && !isSubmitting

    val hasOAuthProviders: Boolean get() = providers.any { it.kind == SignInKind.OAUTH }

    val demoProvider: ProviderSummary? get() = providers.firstOrNull { it.kind == SignInKind.DEMO }

    /** The service being connected, whether picked from the list or detected from the address. */
    val mailServiceName: String? get() = selectedMailProvider?.displayName ?: detectedProviderName

    /** True when the user has to type the server themselves. */
    val needsManualServer: Boolean
        get() = selectedMailProvider?.requiresManualServer == true ||
            (selectedMailProvider == null && detectedProviderName == null && email.isNotBlank())
}

/**
 * Drives the sign-in screen.
 *
 * The OAuth leg is deliberately split: this store hands the caller a URL to open and then
 * waits, because *how* a browser is opened is platform-specific (a Custom Tab on Android,
 * `Desktop.browse` on the desktop, `window.open` in a browser) while everything before and
 * after it is not.
 */
class SignInStore(
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope,
    /** Opens a URL in the platform's browser and returns once it has been handed over. */
    private val openUrl: (String) -> Unit,
    /** "WEB" or "APP" — which callback style this build can actually receive. */
    private val clientTarget: String = "APP",
) {

    private val internalState = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = internalState.asStateFlow()

    fun start() {
        scope.launch {
            internalState.update { it.copy(isLoadingProviders = true) }

            when (val result = sessionRepository.availableProviders()) {
                is ApiResult.Success -> internalState.update {
                    it.copy(providers = result.value, isLoadingProviders = false, error = null)
                }

                is ApiResult.Failure -> internalState.update {
                    it.copy(isLoadingProviders = false, error = result.error)
                }
            }

            // The mail service directory is what makes "sign in with my own account" work
            // without any OAuth setup, so it is loaded up front rather than on demand.
            sessionRepository.mailProviders().onSuccess { providers ->
                internalState.update { it.copy(mailProviders = providers) }
            }
        }
    }

    /** Opens the list of mail services (Gmail, iCloud, Outlook, a company server, …). */
    fun chooseMailService() =
        internalState.update { it.copy(step = SignInStep.CHOOSE_MAIL_SERVICE, error = null) }

    /**
     * Picks a service and pre-fills everything known about it, so the form is down to an
     * address and a password for all but self-hosted servers.
     */
    fun selectMailProvider(option: MailProviderOption) = internalState.update { current ->
        current.copy(
            step = SignInStep.EXCHANGE_CREDENTIALS,
            selectedMailProvider = option,
            imapHost = option.imapHost,
            imapPort = option.imapPort.toString(),
            smtpHost = option.smtpHost,
            smtpPort = option.smtpPort.toString(),
            useTls = option.useTls,
            requiresAppPassword = option.requiresAppPassword,
            appPasswordUrl = option.appPasswordUrl,
            providerHelpText = option.helpText,
            // A self-hosted server has nothing to pre-fill, so open the fields straight away.
            showAdvanced = option.requiresManualServer,
            settingsWereSuggested = !option.requiresManualServer,
            error = null,
            fieldErrors = emptyMap(),
        )
    }

    /** Opens the page where the selected service issues app passwords. */
    fun openAppPasswordPage() {
        internalState.value.appPasswordUrl?.let(openUrl)
    }

    fun chooseProvider(provider: ProviderSummary, target: String = clientTarget) {
        when (provider.kind) {
            SignInKind.CREDENTIALS -> chooseMailService()

            SignInKind.DEMO -> signInAsDemo()

            SignInKind.OAUTH -> scope.launch {
                internalState.update { it.copy(isSubmitting = true, error = null) }

                when (val result = sessionRepository.beginOAuthSignIn(provider.id.name, target)) {
                    is ApiResult.Success -> {
                        openUrl(result.value)
                        internalState.update {
                            it.copy(step = SignInStep.AWAITING_PROVIDER, isSubmitting = false)
                        }
                    }

                    is ApiResult.Failure -> internalState.update {
                        it.copy(isSubmitting = false, error = result.error)
                    }
                }
            }
        }
    }

    /** Called with the handoff code carried back by the deep link or web redirect. */
    fun completeOAuthSignIn(handoffCode: String) {
        scope.launch {
            internalState.update { it.copy(isSubmitting = true, error = null) }

            when (val result = sessionRepository.completeOAuthSignIn(handoffCode)) {
                is ApiResult.Success -> internalState.update { SignInUiState(isLoadingProviders = false) }
                is ApiResult.Failure -> internalState.update {
                    it.copy(step = SignInStep.CHOOSE_PROVIDER, isSubmitting = false, error = result.error)
                }
            }
        }
    }

    /** Called when the provider redirected back with an error, or the user cancelled. */
    fun cancelOAuthSignIn(reason: String? = null) {
        internalState.update { current ->
            current.copy(
                step = SignInStep.CHOOSE_PROVIDER,
                isSubmitting = false,
                error = reason?.let {
                    ApiError(
                        kind = ApiError.Kind.CLIENT,
                        code = it,
                        userMessage = "Sign-in was not completed. Please try again.",
                    )
                },
            )
        }
    }

    fun signInAsDemo() {
        scope.launch {
            internalState.update { it.copy(isSubmitting = true, error = null) }
            sessionRepository.signInAsDemo().onFailure { error ->
                internalState.update { it.copy(isSubmitting = false, error = error) }
            }
        }
    }

    // ---- Exchange form ----------------------------------------------------

    fun updateEmail(value: String) {
        internalState.update { it.copy(email = value, fieldErrors = it.fieldErrors - "email") }

        // Suggest servers once the address looks complete, so the advanced section stays shut
        // for the people who do not need it.
        if (value.count { it == '@' } == 1 && value.substringAfter('@').contains('.')) {
            suggestSettings(value)
        }
    }

    fun updatePassword(value: String) =
        internalState.update { it.copy(password = value, fieldErrors = it.fieldErrors - "password") }

    fun updateImapHost(value: String) = internalState.update { it.copy(imapHost = value) }

    fun updateImapPort(value: String) =
        internalState.update { it.copy(imapPort = value.filter(Char::isDigit).take(5)) }

    fun updateSmtpHost(value: String) = internalState.update { it.copy(smtpHost = value) }

    fun updateSmtpPort(value: String) =
        internalState.update { it.copy(smtpPort = value.filter(Char::isDigit).take(5)) }

    fun toggleTls() = internalState.update { it.copy(useTls = !it.useTls) }

    fun toggleAdvanced() = internalState.update { it.copy(showAdvanced = !it.showAdvanced) }

    fun backToProviders() = internalState.update { current ->
        // Step back one level: the form returns to the service list, the list to the start.
        val previous = if (current.step == SignInStep.EXCHANGE_CREDENTIALS) {
            SignInStep.CHOOSE_MAIL_SERVICE
        } else {
            SignInStep.CHOOSE_PROVIDER
        }
        current.copy(step = previous, error = null, fieldErrors = emptyMap())
    }

    private fun suggestSettings(email: String) {
        scope.launch {
            sessionRepository.suggestExchangeSettings(email).onSuccess { suggestion ->
                internalState.update { current ->
                    // Never overwrite something the user typed themselves.
                    current.copy(
                        imapHost = current.imapHost.ifBlank { suggestion.imapHost },
                        imapPort = if (current.imapPort == "993") suggestion.imapPort.toString() else current.imapPort,
                        smtpHost = current.smtpHost.ifBlank { suggestion.smtpHost },
                        smtpPort = if (current.smtpPort == "587") suggestion.smtpPort.toString() else current.smtpPort,
                        settingsWereSuggested = suggestion.confident,
                        detectedProviderName = suggestion.providerName ?: current.detectedProviderName,
                        // Telling the user an app password is needed *before* they try their
                        // real one is what stops the most common sign-in dead end.
                        requiresAppPassword = current.requiresAppPassword || suggestion.requiresAppPassword,
                        appPasswordUrl = suggestion.appPasswordUrl ?: current.appPasswordUrl,
                        providerHelpText = suggestion.helpText ?: current.providerHelpText,
                    )
                }
            }
        }
    }

    fun submitExchangeSignIn() {
        val current = internalState.value
        val errors = validateExchangeForm(current)
        if (errors.isNotEmpty()) {
            internalState.update { it.copy(fieldErrors = errors) }
            return
        }

        scope.launch {
            internalState.update { it.copy(isSubmitting = true, error = null, fieldErrors = emptyMap()) }

            val result = sessionRepository.signInWithExchange(
                ExchangeSignInRequest(
                    email = current.email.trim(),
                    password = current.password,
                    imapHost = current.imapHost.trim().ifBlank { null },
                    imapPort = current.imapPort.toIntOrNull(),
                    smtpHost = current.smtpHost.trim().ifBlank { null },
                    smtpPort = current.smtpPort.toIntOrNull(),
                    useTls = current.useTls,
                ),
            )

            when (result) {
                is ApiResult.Success -> internalState.update { SignInUiState(isLoadingProviders = false) }
                is ApiResult.Failure -> internalState.update {
                    it.copy(
                        isSubmitting = false,
                        error = result.error,
                        // A rejected server field is worth reopening the advanced section for.
                        showAdvanced = it.showAdvanced || result.error.code == "imap_host_required",
                        fieldErrors = result.error.details.filterKeys { key -> key != "provider" },
                    )
                }
            }
        }
    }

    internal fun validateExchangeForm(state: SignInUiState): Map<String, String> = buildMap {
        if (state.email.isBlank()) {
            put("email", "Enter your email address")
        } else if (!state.email.contains('@') || state.email.substringAfterLast('@').length < 3) {
            put("email", "That does not look like an email address")
        }

        if (state.password.isBlank()) put("password", "Enter your password")

        state.imapPort.toIntOrNull()?.let { port ->
            if (port !in 1..65535) put("imapPort", "Ports run from 1 to 65535")
        }
        state.smtpPort.toIntOrNull()?.let { port ->
            if (port !in 1..65535) put("smtpPort", "Ports run from 1 to 65535")
        }
    }
}
