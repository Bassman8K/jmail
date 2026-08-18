package com.jmail.backend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Publishes the API contract at `/docs`. The Kotlin Multiplatform client is hand-written
 * against this document, so it is treated as the source of truth for request and response
 * shapes rather than as an afterthought.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun jmailOpenApi(properties: JmailProperties): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("JMail API")
                .version("1.0.0")
                .description(
                    """
                    The JMail backend: multi-provider sign-in, unified mailbox access and
                    rule-based categorisation.

                    **Authentication** — every endpoint outside `/api/v1/auth` expects
                    `Authorization: Bearer <access token>`. Access tokens are short-lived;
                    when one expires, exchange the refresh token at `POST /api/v1/auth/refresh`.
                    Refresh tokens rotate on every use, so always store the newest one.
                    """.trimIndent(),
                )
                .contact(Contact().name("JMail").url("https://jmail.app"))
                .license(License().name("MIT").url("https://opensource.org/licenses/MIT")),
        )
        .servers(listOf(Server().url(properties.baseUrl).description("This server")))
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("A JMail access token obtained from any sign-in endpoint"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    private companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }
}
