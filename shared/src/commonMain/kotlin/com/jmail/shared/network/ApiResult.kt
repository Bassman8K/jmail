package com.jmail.shared.network

import com.jmail.shared.model.ApiErrorBody

/**
 * The result of an API call.
 *
 * Failures are values, not exceptions. Every screen has to render a failure state anyway,
 * and making that explicit in the type means a forgotten error path is a compile-time
 * `when` exhaustiveness error rather than a crash in someone's inbox.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): ApiError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun onSuccess(action: (T) -> Unit): ApiResult<T> = also { if (this is Success) action(value) }

    fun onFailure(action: (ApiError) -> Unit): ApiResult<T> = also { if (this is Failure) action(error) }
}

/**
 * A failure the UI can act on.
 *
 * [userMessage] is always safe to display; [kind] is what the code branches on. The two are
 * kept apart so that error handling never depends on matching a human-readable string.
 */
data class ApiError(
    val kind: Kind,
    val code: String,
    val userMessage: String,
    val details: Map<String, String> = emptyMap(),
    val cause: Throwable? = null,
) {
    enum class Kind {
        /** No usable connection, DNS failure, or the request timed out. */
        NETWORK,

        /** The session is gone; the user must sign in again. */
        UNAUTHENTICATED,

        /** A linked mailbox needs reconnecting, but the JMail session is still good. */
        REAUTHENTICATION_REQUIRED,

        /** The request was rejected — validation, a conflict, or a missing resource. */
        CLIENT,

        /** Rate limited; [retryAfterSeconds] says when to try again. */
        RATE_LIMITED,

        /** The server failed, or returned something the client could not parse. */
        SERVER,
    }

    val retryAfterSeconds: Long? get() = details["retryAfterSeconds"]?.toLongOrNull()

    /** Whether offering a "try again" button makes sense for this failure. */
    val isRetryable: Boolean
        get() = kind == Kind.NETWORK || kind == Kind.SERVER || kind == Kind.RATE_LIMITED

    companion object {

        fun network(cause: Throwable? = null) = ApiError(
            kind = Kind.NETWORK,
            code = "network_unavailable",
            userMessage = "You appear to be offline. JMail will retry when the connection returns.",
            cause = cause,
        )

        fun unexpected(cause: Throwable? = null) = ApiError(
            kind = Kind.SERVER,
            code = "unexpected_error",
            userMessage = "Something went wrong. Please try again.",
            cause = cause,
        )

        /** Maps a server error body plus its status onto the kind the UI branches on. */
        fun fromResponse(status: Int, body: ApiErrorBody?): ApiError {
            val code = body?.code ?: "http_$status"
            val message = body?.message ?: defaultMessageFor(status)
            val details = body?.details ?: emptyMap()

            val kind = when {
                code == "reauthentication_required" -> Kind.REAUTHENTICATION_REQUIRED
                status == 401 -> Kind.UNAUTHENTICATED
                status == 429 -> Kind.RATE_LIMITED
                status in 400..499 -> Kind.CLIENT
                else -> Kind.SERVER
            }

            return ApiError(kind = kind, code = code, userMessage = message, details = details)
        }

        private fun defaultMessageFor(status: Int): String = when (status) {
            401 -> "Your session has expired. Sign in again."
            403 -> "You do not have access to that."
            404 -> "That could not be found."
            409 -> "That conflicts with something that already exists."
            429 -> "Too many requests. Give it a moment."
            in 500..599 -> "The server is having trouble. Please try again."
            else -> "Something went wrong. Please try again."
        }
    }
}
