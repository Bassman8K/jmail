package com.jmail.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.auth.AuthenticatedUser
import com.jmail.backend.common.ApiErrorResponse
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Token-bucket rate limiting, keyed on the signed-in user where there is one and the client
 * address otherwise.
 *
 * The sign-in endpoints get a much tighter budget than the rest of the API: they are the
 * ones worth attacking (credential stuffing, handoff-code guessing), and no legitimate
 * client needs to hit them more than a handful of times a minute.
 */
@Component
@Order(RATE_LIMIT_FILTER_ORDER)
class RateLimitFilter(
    private val properties: JmailProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        val isAuthEndpoint = path.startsWith("/api/v1/auth")
        val limit = if (isAuthEndpoint) {
            properties.security.authRateLimitPerMinute
        } else {
            properties.security.rateLimitPerMinute
        }

        val bucket = buckets.computeIfAbsent(bucketKey(request, isAuthEndpoint)) { newBucket(limit) }
        val probe = bucket.tryConsumeAndReturnRemaining(1)

        if (probe.isConsumed) {
            response.setHeader("X-RateLimit-Remaining", probe.remainingTokens.toString())
            filterChain.doFilter(request, response)
            return
        }

        val retryAfterSeconds = Duration.ofNanos(probe.nanosToWaitForRefill).seconds.coerceAtLeast(1)
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader("Retry-After", retryAfterSeconds.toString())
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiErrorResponse(
                code = "rate_limited",
                message = "Too many requests. Try again in ${retryAfterSeconds}s.",
                details = mapOf("retryAfterSeconds" to retryAfterSeconds.toString()),
            ),
        )
    }

    /** Health checks are polled every few seconds by orchestrators and must never be limited. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("/actuator")

    private fun newBucket(requestsPerMinute: Long): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.classic(
                requestsPerMinute,
                // Greedy refill drips tokens back continuously rather than releasing a full
                // burst on the minute boundary, which is what a fixed window would do.
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1)),
            ),
        )
        .build()

    private fun bucketKey(request: HttpServletRequest, isAuthEndpoint: Boolean): String {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        val identity = principal?.userId?.toString() ?: clientAddress(request)
        return if (isAuthEndpoint) "auth:$identity" else "api:$identity"
    }

    /**
     * Honours `X-Forwarded-For` because the app runs behind nginx in the Docker stack, where
     * every remote address would otherwise be the proxy's and share one bucket.
     */
    private fun clientAddress(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"
}

/** Runs after Spring Security so the bucket can be keyed on the authenticated user. */
const val RATE_LIMIT_FILTER_ORDER = 100
