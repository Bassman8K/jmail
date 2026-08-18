package com.jmail.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.jmail.backend.auth.JwtAuthenticationFilter
import com.jmail.backend.common.ApiErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * JMail's API is stateless: every request carries a bearer token and no session cookie is
 * ever issued. That is what makes CSRF protection unnecessary here — there is no ambient
 * credential for a cross-site request to ride on.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val properties: JmailProperties,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize ->
                authorize
                    // Sign-in endpoints must be reachable without a token, by definition.
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    // Liveness/readiness are polled by Docker and Kubernetes before any
                    // credential exists; the rest of actuator stays protected.
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint { _, response, _ ->
                        writeError(
                            response,
                            HttpServletResponse.SC_UNAUTHORIZED,
                            ApiErrorResponse("unauthorized", "Authentication is required"),
                        )
                    }
                    .accessDeniedHandler { _, response, _ ->
                        writeError(
                            response,
                            HttpServletResponse.SC_FORBIDDEN,
                            ApiErrorResponse("forbidden", "You do not have access to this resource"),
                        )
                    }
            }
            .headers { headers ->
                headers.frameOptions { it.deny() }
                headers.contentTypeOptions { }
                headers.httpStrictTransportSecurity { hsts ->
                    hsts.includeSubDomains(true).maxAgeInSeconds(SECONDS_IN_YEAR)
                }
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    /**
     * The browser build runs on a different origin from the API in development, so the exact
     * origins are allow-listed. Wildcards are avoided deliberately: credentials are sent on
     * every request and `*` cannot be combined with them safely.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins.distinct()
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Requested-With")
            exposedHeaders = listOf("X-Total-Count", "Retry-After")
            allowCredentials = true
            maxAge = 3600
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    private fun writeError(response: HttpServletResponse, status: Int, body: ApiErrorResponse) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, body)
    }

    private companion object {
        const val SECONDS_IN_YEAR = 31_536_000L
    }
}
