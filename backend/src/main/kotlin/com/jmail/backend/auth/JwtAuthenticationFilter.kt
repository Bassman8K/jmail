package com.jmail.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.common.ApiErrorResponse
import com.jmail.backend.common.UnauthorizedException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Turns a `Authorization: Bearer …` header into an authenticated [AuthenticatedUser].
 *
 * A *missing* token leaves the request anonymous — the authorization rules then decide
 * whether that is acceptable, which keeps public endpoints working. A *present but invalid*
 * token is rejected immediately with a specific code, so clients can tell "signed out" apart
 * from "expired, please refresh" and act on it without guessing.
 */
@Component
class JwtAuthenticationFilter(
    private val tokenService: TokenService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        val token = header.substring(BEARER_PREFIX.length).trim()
        val user = try {
            tokenService.verifyAccessToken(token)
        } catch (failure: UnauthorizedException) {
            SecurityContextHolder.clearContext()
            respondUnauthorized(response, failure)
            return
        }

        val authentication = UsernamePasswordAuthenticationToken(
            user,
            null,
            listOf(SimpleGrantedAuthority(ROLE_USER)),
        )
        SecurityContextHolder.getContext().authentication = authentication

        try {
            filterChain.doFilter(request, response)
        } finally {
            // The context is thread-local and threads are pooled; leaving it set would leak
            // one request's identity into the next request served by the same thread.
            SecurityContextHolder.clearContext()
        }
    }

    /** Never authenticate the pre-flight request; the CORS filter answers those. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        HttpMethod.OPTIONS.matches(request.method)

    private fun respondUnauthorized(response: HttpServletResponse, failure: UnauthorizedException) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiErrorResponse(code = failure.code, message = failure.message),
        )
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val ROLE_USER = "ROLE_USER"
    }
}
