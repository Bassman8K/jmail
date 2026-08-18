package com.jmail.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Every JMail-specific setting, bound from the `jmail.*` block of application.yml.
 *
 * Providers are optional by design: one with a blank client id is simply not advertised
 * on the sign-in screen, which is what lets the app start with no credentials configured.
 */
@ConfigurationProperties(prefix = "jmail")
data class JmailProperties(
    /** Public URL of this backend; used to build OAuth redirect URIs. */
    val baseUrl: String = "http://localhost:8090",
    /** Where the browser app lives; used as the post-sign-in redirect target. */
    val webOrigin: String = "http://localhost:3000",
    val allowedOrigins: List<String> = listOf("http://localhost:3000"),
    val security: SecurityProperties = SecurityProperties(),
    val demo: DemoProperties = DemoProperties(),
    val sync: SyncProperties = SyncProperties(),
    val providers: ProvidersProperties = ProvidersProperties(),
    val exchange: ExchangeProperties = ExchangeProperties(),
) {

    data class SecurityProperties(
        /** HMAC key for access tokens. Blank generates an ephemeral key at startup. */
        val jwtSecret: String = "",
        /** AES key protecting provider tokens at rest. Blank generates an ephemeral key. */
        val encryptionKey: String = "",
        val accessTokenTtl: Duration = Duration.ofMinutes(30),
        val refreshTokenTtl: Duration = Duration.ofDays(30),
        val handoffTtl: Duration = Duration.ofMinutes(2),
        val rateLimitPerMinute: Long = 120,
        /** Sign-in endpoints get a tighter budget: they are the ones worth attacking. */
        val authRateLimitPerMinute: Long = 20,
    )

    data class DemoProperties(
        val enabled: Boolean = false,
        val email: String = "demo@jmail.app",
        val displayName: String = "Demo User",
    )

    data class SyncProperties(
        val enabled: Boolean = true,
        val interval: Duration = Duration.ofMinutes(5),
        val pageSize: Int = 100,
        val maxMessagesPerRun: Int = 500,
    )

    data class ProvidersProperties(
        val google: OAuthProviderProperties = OAuthProviderProperties(),
        val microsoft: OAuthProviderProperties = OAuthProviderProperties(),
        val apple: OAuthProviderProperties = OAuthProviderProperties(),
    )

    /**
     * One OAuth 2.0 / OpenID Connect provider.
     *
     * `{tenant}` in any URI is replaced with [tenantId], which is how Microsoft's
     * single-tenant, multi-tenant and consumer endpoints are expressed with one config shape.
     */
    data class OAuthProviderProperties(
        val enabled: Boolean = true,
        val clientId: String = "",
        val clientSecret: String = "",
        val tenantId: String = "common",
        val teamId: String = "",
        val keyId: String = "",
        val privateKey: String = "",
        val authorizationUri: String = "",
        val tokenUri: String = "",
        val jwksUri: String = "",
        val issuer: String = "",
        val scopes: List<String> = emptyList(),
    ) {
        /** True when this provider has enough configuration to complete a sign-in. */
        val isConfigured: Boolean
            get() = enabled && clientId.isNotBlank() && authorizationUri.isNotBlank()

        fun authorizationUriResolved(): String = authorizationUri.replace("{tenant}", tenantId)

        fun tokenUriResolved(): String = tokenUri.replace("{tenant}", tenantId)

        fun jwksUriResolved(): String = jwksUri.replace("{tenant}", tenantId)

        fun issuerResolved(): String = issuer.replace("{tenant}", tenantId)
    }

    data class ExchangeProperties(
        val enabled: Boolean = true,
        val defaultHost: String = "",
        val defaultImapPort: Int = 993,
        val defaultSmtpPort: Int = 587,
        val connectTimeout: Duration = Duration.ofSeconds(10),
        val readTimeout: Duration = Duration.ofSeconds(30),
    )
}
