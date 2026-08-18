package com.jmail.backend.common

import org.springframework.http.HttpStatus

/**
 * Base class for every error JMail deliberately returns to a client.
 *
 * [code] is a stable, machine-readable identifier (`message_not_found`) that clients
 * switch on; [message] is human-readable and safe to display. Anything not derived from
 * this class is treated as an internal error and its detail is never sent to the client.
 */
sealed class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundException(
    resource: String,
    identifier: Any,
) : ApiException(
    status = HttpStatus.NOT_FOUND,
    code = "${resource.lowercase()}_not_found",
    message = "$resource $identifier was not found",
)

class BadRequestException(
    code: String,
    message: String,
    details: Map<String, String> = emptyMap(),
) : ApiException(HttpStatus.BAD_REQUEST, code, message, details)

class UnauthorizedException(
    message: String = "Authentication is required",
    code: String = "unauthorized",
) : ApiException(HttpStatus.UNAUTHORIZED, code, message)

class ForbiddenException(
    message: String = "You do not have access to this resource",
) : ApiException(HttpStatus.FORBIDDEN, "forbidden", message)

class ConflictException(
    code: String,
    message: String,
) : ApiException(HttpStatus.CONFLICT, code, message)

class RateLimitedException(
    retryAfterSeconds: Long,
) : ApiException(
    status = HttpStatus.TOO_MANY_REQUESTS,
    code = "rate_limited",
    message = "Too many requests. Try again in ${retryAfterSeconds}s.",
    details = mapOf("retryAfterSeconds" to retryAfterSeconds.toString()),
)

/** A failure reported by an upstream mail provider (Gmail, Graph, an IMAP server, …). */
class ProviderException(
    provider: String,
    message: String,
    cause: Throwable? = null,
) : ApiException(
    status = HttpStatus.BAD_GATEWAY,
    code = "provider_error",
    message = message,
    details = mapOf("provider" to provider),
    cause = cause,
)

/** The linked account's credentials expired or were revoked; the user must sign in again. */
class ReauthenticationRequiredException(
    provider: String,
) : ApiException(
    status = HttpStatus.UNAUTHORIZED,
    code = "reauthentication_required",
    message = "Your $provider account needs to be reconnected",
    details = mapOf("provider" to provider),
)
