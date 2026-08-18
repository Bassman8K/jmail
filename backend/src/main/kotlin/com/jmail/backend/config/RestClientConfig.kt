package com.jmail.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The HTTP client used to talk to identity and mail providers.
 *
 * Timeouts are mandatory, not optional: a provider that accepts a connection and then stops
 * responding would otherwise hold a request thread until the container gives up, and a slow
 * Gmail call must never be able to exhaust the pool that serves the user's own inbox.
 */
@Configuration
class RestClientConfig {

    @Bean
    fun restClient(): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(CONNECT_TIMEOUT)
            setReadTimeout(READ_TIMEOUT)
        }

        return RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader("User-Agent", USER_AGENT)
            .build()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val USER_AGENT = "JMail/1.0 (+https://jmail.app)"
    }
}
