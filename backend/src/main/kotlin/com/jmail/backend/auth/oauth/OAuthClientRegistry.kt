package com.jmail.backend.auth.oauth

import com.jmail.backend.common.BadRequestException
import com.jmail.backend.user.AccountProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Looks up the [OAuthClient] for a provider and reports which ones this deployment can
 * actually offer, so the sign-in screen only shows buttons that will work.
 */
@Component
class OAuthClientRegistry(clients: List<OAuthClient>) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byProvider: Map<AccountProvider, OAuthClient> = clients.associateBy(OAuthClient::provider)

    init {
        val configured = byProvider.values.filter(OAuthClient::isConfigured).map { it.provider }
        log.info(
            "OAuth providers configured: {}",
            configured.takeIf { it.isNotEmpty() }?.joinToString() ?: "none (demo and IMAP sign-in remain available)",
        )
    }

    fun clientFor(provider: AccountProvider): OAuthClient {
        val client = byProvider[provider]
            ?: throw BadRequestException("unsupported_provider", "${provider.displayName} sign-in is not supported")

        if (!client.isConfigured) {
            throw BadRequestException(
                "provider_not_configured",
                "${provider.displayName} sign-in is not configured on this server",
            )
        }
        return client
    }

    fun configuredProviders(): List<AccountProvider> =
        byProvider.values.filter(OAuthClient::isConfigured).map(OAuthClient::provider).sorted()

    fun isConfigured(provider: AccountProvider): Boolean = byProvider[provider]?.isConfigured == true

    /** Resolves the path segment used in `/api/v1/auth/{provider}/…` to a provider. */
    fun parseProvider(slug: String): AccountProvider =
        AccountProvider.entries.firstOrNull { it.name.equals(slug, ignoreCase = true) }
            ?: throw BadRequestException("unsupported_provider", "Unknown sign-in provider '$slug'")
}
