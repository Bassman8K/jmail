package com.jmail.backend.auth.oauth

import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Microsoft sign-in through the Entra ID v2.0 endpoint, covering personal accounts,
 * work/school accounts and Exchange Online mailboxes via Microsoft Graph.
 *
 * This is the *cloud* Microsoft path. On-premises Exchange, which has no OAuth endpoint of
 * its own, is handled by ExchangeAuthenticator over IMAP/EWS instead.
 */
@Component
class MicrosoftOAuthClient(
    jmailProperties: JmailProperties,
    restClient: RestClient,
) : AbstractOidcClient(jmailProperties.providers.microsoft, restClient) {

    private val baseUrl = jmailProperties.baseUrl

    override val provider = AccountProvider.MICROSOFT

    override fun redirectUri(): String = "$baseUrl/api/v1/auth/microsoft/callback"

    override fun extraAuthorizationParameters(): Map<String, String> = mapOf(
        // Without this, a signed-in browser session silently reuses the wrong account when
        // someone is adding a second mailbox.
        "prompt" to "select_account",
        "response_mode" to "query",
    )

    override fun profileFrom(tokens: OAuthTokens): ProviderProfile {
        val idToken = tokens.idToken
            ?: throw ProviderException(provider.displayName, "Microsoft did not return an ID token")
        val claims = claimsOf(idToken)

        // Entra ID puts the address in different claims depending on account type: `email`
        // for consumer accounts, `preferred_username` or `upn` for work and school ones.
        val email = claims.getStringClaim("email")
            ?: claims.getStringClaim("preferred_username")
            ?: claims.getStringClaim("upn")
            ?: throw ProviderException(provider.displayName, "Microsoft did not share an email address")

        // `oid` is stable per tenant and survives address changes; `sub` is pairwise and
        // differs between applications, so `oid` is the better account key when present.
        val accountId = claims.getStringClaim("oid") ?: claims.subject

        return ProviderProfile(
            providerAccountId = accountId,
            email = EmailAddresses.canonical(email),
            displayName = claims.getStringClaim("name") ?: email.substringBefore('@'),
            avatarUrl = null, // Graph exposes photos on a separate endpoint, fetched on sync
        )
    }
}
