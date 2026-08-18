package com.jmail.backend.user

import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.auth.dto.AccountResponse
import com.jmail.backend.auth.dto.UpdatePreferencesRequest
import com.jmail.backend.auth.dto.UserResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/users")
@Validated
@Tag(name = "Account", description = "The signed-in person, their preferences and linked mailboxes")
class UserController(private val userService: UserService) {

    @GetMapping("/me")
    @Operation(summary = "The signed-in user and every mailbox they have connected")
    fun currentUser(@AuthenticationPrincipal user: AuthenticatedUser): UserResponse =
        userService.currentUser(user)

    @PatchMapping("/me")
    @Operation(summary = "Update display name, theme, density, locale or timezone")
    fun updatePreferences(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody request: UpdatePreferencesRequest,
    ): UserResponse = userService.updatePreferences(user, request)

    @GetMapping("/me/accounts")
    @Operation(summary = "Linked mailboxes")
    fun accounts(@AuthenticationPrincipal user: AuthenticatedUser): List<AccountResponse> =
        userService.accounts(user)

    @DeleteMapping("/me/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Disconnect a mailbox",
        description = "Also removes the messages synced from it. Your last remaining mailbox " +
            "cannot be disconnected.",
    )
    fun unlinkAccount(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable accountId: UUID,
    ) = userService.unlinkAccount(user, accountId)

    @PostMapping("/me/accounts/{accountId}/primary")
    @Operation(summary = "Choose the mailbox new messages are sent from by default")
    fun setPrimary(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable accountId: UUID,
    ): List<AccountResponse> = userService.setPrimaryAccount(user, accountId)
}
