package com.jmail.shared.state

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
}

data class SignInUiState(
    val step: SignInStep = SignInStep.CHOOSE_PROVIDER,
    val providers: List<ProviderSummary> = emptyList(),
    val isLoadingProviders: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: ApiError? = null,
) {
    val hasOAuthProviders: Boolean get() = providers.any { it.kind == SignInKind.OAUTH }

    val demoProvider: ProviderSummary? get() = providers.firstOrNull { it.kind == SignInKind.DEMO }
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
        }
    }

    fun chooseProvider(provider: ProviderSummary, target: String = clientTarget) {
        when (provider.kind) {
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
}
