package com.jmail.backend.common

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.servlet.NoHandlerFoundException

/**
 * The contract every client depends on: one shape of error body, a stable `code` to branch
 * on, and — the part worth guarding — nothing internal in `message`.
 *
 * A leak here is invisible in normal use and only shows up when something breaks in
 * production, which is exactly when a SQL fragment or a hostname must not reach a browser.
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()
    private val request = MockHttpServletRequest("GET", "/api/v1/messages/42")

    private fun ResponseEntity<ApiErrorResponse>.error(): ApiErrorResponse =
        requireNotNull(body) { "the handler must always return a body" }

    // ---- the API's own vocabulary -------------------------------------------------

    @Test
    fun `an ApiException is returned verbatim, with its status and details`() {
        val response = handler.handleApiException(NotFoundException("Message", 42), request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.error().code).isEqualTo("message_not_found")
        assertThat(response.error().message).isEqualTo("Message 42 was not found")
        assertThat(response.error().path).isEqualTo("/api/v1/messages/42")
    }

    @Test
    fun `a 5xx ApiException is still returned verbatim rather than being masked`() {
        // It is the API's own vocabulary, so the caller keeps the code it can branch on;
        // the difference from a 4xx is that this one is logged with its stack trace.
        val response = handler.handleApiException(
            ProviderException("gmail", "Gmail is not responding"),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(response.error().code).isEqualTo("provider_error")
        assertThat(response.error().details).isEqualTo(mapOf("provider" to "gmail"))
    }

    // ---- validation ---------------------------------------------------------------

    @Test
    fun `a bean validation failure names every offending field`() {
        val binding = BeanPropertyBindingResult(Any(), "signInRequest")
        binding.addError(FieldError("signInRequest", "email", "Enter your email address"))
        binding.addError(FieldError("signInRequest", "password", "Enter your password"))

        val response = handler.handleValidation(
            MethodArgumentNotValidException(mockk<MethodParameter>(relaxed = true), binding),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.error().code).isEqualTo("validation_failed")
        assertThat(response.error().details).isEqualTo(
            mapOf("email" to "Enter your email address", "password" to "Enter your password"),
        )
    }

    @Test
    fun `a field error with no message still reports something usable`() {
        val binding = BeanPropertyBindingResult(Any(), "signInRequest")
        binding.addError(FieldError("signInRequest", "imapPort", null, false, null, null, null))

        val response = handler.handleValidation(
            MethodArgumentNotValidException(mockk<MethodParameter>(relaxed = true), binding),
            request,
        )

        assertThat(response.error().details).isEqualTo(mapOf("imapPort" to "is invalid"))
    }

    @Test
    fun `constraint violations are reported per property path`() {
        val violation = mockk<ConstraintViolation<Any>>()
        val path = mockk<Path>()
        every { path.toString() } returns "syncNow.accountId"
        every { violation.propertyPath } returns path
        every { violation.message } returns "must not be blank"

        val response = handler.handleConstraintViolation(
            ConstraintViolationException(setOf(violation)),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.error().code).isEqualTo("validation_failed")
        assertThat(response.error().details).isEqualTo(mapOf("syncNow.accountId" to "must not be blank"))
    }

    // ---- malformed input ----------------------------------------------------------

    @Test
    fun `unparseable json does not echo the parser's complaint back`() {
        val response = handler.handleMalformedRequest(
            HttpMessageNotReadableException(
                "Unexpected character ('}' (code 125)) at [Source: (String)\"{\"; line: 1]",
                mockk<HttpInputMessage>(relaxed = true),
            ),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.error().code).isEqualTo("malformed_request")
        assertThat(response.error().message).isEqualTo("The request could not be parsed")
        assertThat(response.error().details).isEmpty()
    }

    @Test
    fun `a missing query parameter is a malformed request, not a 500`() {
        val response = handler.handleMalformedRequest(
            MissingServletRequestParameterException("email", "String"),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.error().code).isEqualTo("malformed_request")
    }

    // ---- security -----------------------------------------------------------------

    @Test
    fun `access denied is a 403 the client can branch on`() {
        val response = handler.handleAccessDenied(AccessDeniedException("nope"), request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.error().code).isEqualTo("forbidden")
    }

    @Test
    fun `an authentication failure is a 401 and never says which half was wrong`() {
        val response = handler.handleAuthentication(
            BadCredentialsException("Bad credentials for ada@example.com"),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.error().code).isEqualTo("unauthorized")
        assertThat(response.error().message).isEqualTo("Authentication is required")
    }

    // ---- persistence --------------------------------------------------------------

    @Test
    fun `a constraint violation in the database does not leak the constraint name`() {
        val response = handler.handleDataIntegrity(
            DataIntegrityViolationException(
                """duplicate key value violates unique constraint "uk_account_email"""",
            ),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.error().code).isEqualTo("conflict")
        assertThat(response.error().message).isEqualTo("That change conflicts with existing data")
    }

    @Test
    fun `a lost optimistic lock tells the user what to do about it`() {
        val response = handler.handleOptimisticLocking(
            OptimisticLockingFailureException("Row was updated by another transaction"),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.error().code).isEqualTo("concurrent_modification")
        assertThat(response.error().message).contains("Reload and try again")
    }

    // ---- wrong method, wrong media type -------------------------------------------

    @Test
    fun `a wrong method is 405 and says which methods do work`() {
        // Found in the running app: GET on a POST-only route came back as a 500 saying the
        // server had broken, and logged a stack trace for what is an ordinary client error.
        val response = handler.handleMethodNotSupported(
            HttpRequestMethodNotSupportedException("GET", listOf("POST", "PUT")),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.error().code).isEqualTo("method_not_allowed")
        assertThat(response.error().message).contains("GET")
        assertThat(response.error().message).contains("POST")
        // RFC 9110 requires Allow on a 405, and it is what lets a client correct itself.
        assertThat(response.headers.allow).isEqualTo(setOf(HttpMethod.POST, HttpMethod.PUT))
    }

    @Test
    fun `a wrong method with no alternatives still answers 405 rather than 500`() {
        val response = handler.handleMethodNotSupported(
            HttpRequestMethodNotSupportedException("TRACE"),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.error().code).isEqualTo("method_not_allowed")
        assertThat(response.error().message).isEqualTo("TRACE is not supported here")
    }

    @Test
    fun `an unsupported content type is 415`() {
        val response = handler.handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException(
                MediaType.TEXT_XML,
                listOf(MediaType.APPLICATION_JSON),
            ),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        assertThat(response.error().code).isEqualTo("unsupported_media_type")
    }

    @Test
    fun `an unacceptable Accept header is 406`() {
        val response = handler.handleNotAcceptable(
            HttpMediaTypeNotAcceptableException(listOf(MediaType.APPLICATION_JSON)),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_ACCEPTABLE)
        assertThat(response.error().code).isEqualTo("not_acceptable")
    }

    // ---- routing and the catch-all ------------------------------------------------

    @Test
    fun `an unmatched route names the method and url that missed`() {
        val response = handler.handleNoHandler(
            NoHandlerFoundException("GET", "/api/v1/nope", org.springframework.http.HttpHeaders()),
            request,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.error().code).isEqualTo("endpoint_not_found")
        assertThat(response.error().message).contains("/api/v1/nope")
    }

    @Test
    fun `an unexpected exception becomes a generic 500 with nothing internal in it`() {
        val leaky = IllegalStateException(
            "Connection to postgres://jmail:hunter2@10.0.0.4:5432/jmail refused",
        )

        val response = handler.handleUnexpected(leaky, request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.error().code).isEqualTo("internal_error")
        assertThat(response.error().message)
            .isEqualTo("Something went wrong on our side. The failure has been logged.")
        // The credentials and host in the cause must not survive into the response.
        assertThat(response.error().details).isEmpty()
    }

    // ---- shape --------------------------------------------------------------------

    @Test
    fun `every handler stamps the request path and a timestamp`() {
        val responses = listOf(
            handler.handleAccessDenied(AccessDeniedException("x"), request),
            handler.handleAuthentication(BadCredentialsException("x"), request),
            handler.handleOptimisticLocking(OptimisticLockingFailureException("x"), request),
            handler.handleUnexpected(RuntimeException("x"), request),
        )

        responses.forEach { response ->
            assertThat(response.error().path).isEqualTo("/api/v1/messages/42")
            assertThat(response.error().timestamp).isNotNull()
        }
    }

    @Test
    fun `the codes are distinct, so a client can switch on them`() {
        val codes = listOf(
            handler.handleAccessDenied(AccessDeniedException("x"), request).error().code,
            handler.handleAuthentication(BadCredentialsException("x"), request).error().code,
            handler.handleDataIntegrity(DataIntegrityViolationException("x"), request).error().code,
            handler.handleOptimisticLocking(OptimisticLockingFailureException("x"), request).error().code,
            handler.handleUnexpected(RuntimeException("x"), request).error().code,
        )

        assertThat(codes.toSet()).containsOnly(
            "forbidden",
            "unauthorized",
            "conflict",
            "concurrent_modification",
            "internal_error",
        )
    }
}
