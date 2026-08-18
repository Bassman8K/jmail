package com.jmail.backend.auth.oauth

import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Google sign-in, including the Gmail scopes JMail needs to read and send mail.
 *
 * Google only returns a refresh token on the first consent, so `access_type=offline` and
 * `prompt=consent` are both required — without them a reconnect leaves the account unable
 * to sync once the hour-long access token expires.
 */
@Component
class GoogleOAuthClient(
    jmailProperties: JmailProperties,
    restClient: RestClient,
) : AbstractOidcClient(jmailProperties.providers.google, restClient) {

    private val baseUrl = jmailProperties.baseUrl

    override val provider = AccountProvider.GOOGLE

    override fun redirectUri(): String = "$baseUrl/api/v1/auth/google/callback"

    override fun extraAuthorizationParameters(): Map<String, String> = mapOf(
        "access_type" to "offline",
        "prompt" to "consent",
        "include_granted_scopes" to "true",
    )

    override fun profileFrom(tokens: OAuthTokens): ProviderProfile {
        val idToken = tokens.idToken
            ?: throw ProviderException(provider.displayName, "Google did not return an ID token")
        val claims = claimsOf(idToken)

        val email = claims.getStringClaim("email")
            ?: throw ProviderException(provider.displayName, "Google did not share an email address")

        return ProviderProfile(
            providerAccountId = claims.subject,
            email = EmailAddresses.canonical(email),
            displayName = claims.getStringClaim("name")
                ?: claims.getStringClaim("given_name")
                ?: email.substringBefore('@'),
            avatarUrl = claims.getStringClaim("picture"),
        )
    }
}
