package com.jmail.backend.auth.dto

import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.AccountStatus
import com.jmail.backend.user.MailAccount
import com.jmail.backend.user.UiDensity
import com.jmail.backend.user.UiTheme
import com.jmail.backend.user.UserAccount
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** How a provider authenticates, which decides what the sign-in button does. */
enum class SignInKind {
    /** Redirect to the provider in a browser, come back with a code. */
    OAUTH,

    /** One click into a seeded mailbox; local development only. */
    DEMO,
}

@Schema(description = "A sign-in method this server can actually complete")
data class ProviderSummary(
    @get:Schema(example = "GOOGLE") val id: AccountProvider,
    @get:Schema(example = "Google") val displayName: String,
    val kind: SignInKind,
    @get:Schema(description = "Icon key the client maps to its own asset", example = "google")
    val icon: String,
)

@Schema(description = "Where to send the user to authorise JMail")
data class StartAuthorizationResponse(
    val authorizationUrl: String,
    @get:Schema(description = "Opaque value echoed back on the callback; the client may ignore it")
    val state: String,
    val expiresInSeconds: Long,
)

data class HandoffExchangeRequest(
    @field:NotBlank(message = "A sign-in code is required")
    val code: String,
)

data class RefreshTokenRequest(
    @field:NotBlank(message = "A refresh token is required")
    val refreshToken: String,
)

data class LogoutRequest(
    val refreshToken: String? = null,
    @get:Schema(description = "Sign out of every device rather than just this one")
    val allSessions: Boolean = false,
)

@Schema(description = "A signed-in session")
data class AuthTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    @get:Schema(description = "Access token lifetime in seconds", example = "1800")
    val expiresIn: Long,
    val user: UserResponse,
)

@Schema(description = "A linked mailbox")
data class AccountResponse(
    val id: UUID,
    val provider: AccountProvider,
    val providerName: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val status: AccountStatus,
    val statusDetail: String?,
    val isPrimary: Boolean,
    val color: String?,
    val lastSyncAt: Instant?,
) {
    companion object {
        fun from(account: MailAccount) = AccountResponse(
            id = account.id,
            provider = account.provider,
            providerName = account.provider.displayName,
            email = account.email,
            displayName = account.displayName,
            avatarUrl = account.avatarUrl,
            status = account.status,
            statusDetail = account.statusDetail,
            isPrimary = account.isPrimary,
            color = account.color,
            lastSyncAt = account.lastSyncAt,
        )
    }
}

@Schema(description = "The signed-in person and every mailbox they have connected")
data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val locale: String,
    val timezone: String,
    val theme: UiTheme,
    val density: UiDensity,
    val accounts: List<AccountResponse>,
) {
    companion object {
        fun from(user: UserAccount, accounts: List<MailAccount>) = UserResponse(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            locale = user.locale,
            timezone = user.timezone,
            theme = user.theme,
            density = user.density,
            accounts = accounts.map(AccountResponse::from),
        )
    }
}

data class UpdatePreferencesRequest(
    val theme: UiTheme? = null,
    val density: UiDensity? = null,
    @field:Size(max = 200) val displayName: String? = null,
    @field:Size(max = 64) val timezone: String? = null,
    @field:Size(max = 20) val locale: String? = null,
)
