package com.jmail.backend.common

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

@Schema(description = "Uniform error payload returned by every JMail endpoint")
data class ApiErrorResponse(
    @field:Schema(description = "Stable machine-readable error identifier", example = "message_not_found")
    val code: String,
    @field:Schema(description = "Human-readable, safe to display", example = "Message 42 was not found")
    val message: String,
    @field:Schema(description = "Field-level or contextual detail")
    val details: Map<String, String> = emptyMap(),
    val path: String? = null,
    val timestamp: Instant = Instant.now(),
)

/**
 * Translates every exception into [ApiErrorResponse].
 *
 * The rule: anything modelled as an [ApiException] is the API's own vocabulary and is
 * returned verbatim. Everything else is logged with its stack trace and reported as a
 * generic 500, so internal details (SQL, host names, library internals) never leak.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(
        exception: ApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        if (exception.status.is5xxServerError) {
            log.error("{} on {}: {}", exception.code, request.requestURI, exception.message, exception)
        } else {
            log.debug("{} on {}: {}", exception.code, request.requestURI, exception.message)
        }
        return ResponseEntity.status(exception.status).body(
            ApiErrorResponse(
                code = exception.code,
                message = exception.message,
                details = exception.details,
                path = request.requestURI,
            ),
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val fieldErrors = exception.bindingResult.fieldErrors.associate { error ->
            error.field to (error.defaultMessage ?: "is invalid")
        }
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "validation_failed",
                message = "The request contains invalid fields",
                details = fieldErrors,
                path = request.requestURI,
            ),
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val violations = exception.constraintViolations.associate { violation ->
            violation.propertyPath.toString() to violation.message
        }
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "validation_failed",
                message = "The request contains invalid values",
                details = violations,
                path = request.requestURI,
            ),
        )
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleMalformedRequest(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.badRequest().body(
        ApiErrorResponse(
            code = "malformed_request",
            message = "The request could not be parsed",
            path = request.requestURI,
        ),
    )

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        exception: AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(HttpStatus.FORBIDDEN).body(
        ApiErrorResponse(
            code = "forbidden",
            message = "You do not have access to this resource",
            path = request.requestURI,
        ),
    )

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(
        exception: AuthenticationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
        ApiErrorResponse(
            code = "unauthorized",
            message = "Authentication is required",
            path = request.requestURI,
        ),
    )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(
        exception: DataIntegrityViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        // The constraint name is useful in the log but must not reach the client.
        log.warn("Data integrity violation on {}", request.requestURI, exception)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "conflict",
                message = "That change conflicts with existing data",
                path = request.requestURI,
            ),
        )
    }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLocking(
        exception: OptimisticLockingFailureException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(HttpStatus.CONFLICT).body(
        ApiErrorResponse(
            code = "concurrent_modification",
            message = "Someone else changed this while you were editing it. Reload and try again.",
            path = request.requestURI,
        ),
    )

    /**
     * A wrong method, an unreadable Content-Type or an unacceptable Accept header are all
     * client mistakes with a status of their own. Without this they fell through to the
     * catch-all below and came back as 500 — telling the caller the server was broken when
     * the request was, and logging a stack trace at ERROR for every one of them.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(
        exception: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(HttpStatus.METHOD_NOT_ALLOWED)
        .apply { exception.supportedHttpMethods?.let { allowed -> allow(*allowed.toTypedArray()) } }
        .body(
            ApiErrorResponse(
                code = "method_not_allowed",
                message = "${exception.method} is not supported here" +
                    (exception.supportedMethods?.takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = ". Use ", separator = " or ") ?: ""),
                path = request.requestURI,
            ),
        )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(
        exception: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(
            ApiErrorResponse(
                code = "unsupported_media_type",
                message = "This endpoint accepts application/json",
                path = request.requestURI,
            ),
        )

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(
        exception: HttpMediaTypeNotAcceptableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(HttpStatus.NOT_ACCEPTABLE)
        .body(
            ApiErrorResponse(
                code = "not_acceptable",
                message = "This endpoint can only return application/json",
                path = request.requestURI,
            ),
        )

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNoHandler(
        exception: NoHandlerFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        ApiErrorResponse(
            code = "endpoint_not_found",
            message = "No endpoint matches ${exception.httpMethod} ${exception.requestURL}",
            path = request.requestURI,
        ),
    )

    /**
     * Spring 6 routes an unmatched URL to the static-resource handler, which raises this
     * rather than [NoHandlerFoundException]. Without a handler for it, *every* 404 on the
     * API came back as `500 internal_error` with a stack trace logged at ERROR — so a
     * client could not tell a wrong URL from a broken server, and the log filled with
     * alarming entries for what is usually a typo.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        exception: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        ApiErrorResponse(
            code = "endpoint_not_found",
            message = "No endpoint matches ${request.method} ${request.requestURI}",
            path = request.requestURI,
        ),
    )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled exception on {}", request.requestURI, exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = "internal_error",
                message = "Something went wrong on our side. The failure has been logged.",
                path = request.requestURI,
            ),
        )
    }
}
