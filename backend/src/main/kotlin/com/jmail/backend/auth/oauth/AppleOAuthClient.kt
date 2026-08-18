package com.jmail.backend.auth.oauth

import com.jmail.backend.common.EmailAddresses
import com.jmail.backend.common.ProviderException
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.AccountProvider
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Sign in with Apple.
 *
 * Two things make Apple different from the other providers:
 *  * the client secret is a signed assertion, not a static string (see
 *    [AppleClientSecretFactory]);
 *  * the user's name is delivered **once**, in the form post of the very first
 *    authorization, and never again. [nameFromCallback] captures it so the account is not
 *    stuck showing the local part of a private-relay address forever.
 */
@Component
class AppleOAuthClient(
    jmailProperties: JmailProperties,
    restClient: RestClient,
    private val clientSecretFactory: AppleClientSecretFactory,
) : AbstractOidcClient(jmailProperties.providers.apple, restClient) {

    private val baseUrl = jmailProperties.baseUrl

    override val provider = AccountProvider.APPLE

    override val isConfigured: Boolean
        get() = properties.enabled && clientSecretFactory.isConfigured()

    override fun redirectUri(): String = "$baseUrl/api/v1/auth/apple/callback"

    override fun clientSecret(): String = clientSecretFactory.clientSecret()

    override fun extraAuthorizationParameters(): Map<String, String> = mapOf(
        // Apple mandates form_post whenever `name` or `email` scopes are requested, so the
        // callback arrives as a POST rather than a redirect with query parameters.
        "response_mode" to "form_post",
    )

    override fun profileFrom(tokens: OAuthTokens): ProviderProfile {
        val idToken = tokens.idToken
            ?: throw ProviderException(provider.displayName, "Apple did not return an ID token")
        val claims = claimsOf(idToken)

        val email = claims.getStringClaim("email")
            ?: throw ProviderException(
                provider.displayName,
                "Apple did not share an email address. Reconnect and choose \"Share My Email\".",
            )

        return ProviderProfile(
            providerAccountId = claims.subject,
            email = EmailAddresses.canonical(email),
            // Apple never puts a name in the ID token; the caller merges in nameFromCallback.
            displayName = email.substringBefore('@'),
            avatarUrl = null,
        )
    }

    /**
     * Extracts the display name from the `user` field Apple form-posts on first consent.
     * The payload looks like `{"name":{"firstName":"Ada","lastName":"Lovelace"},"email":…}`.
     */
    fun nameFromCallback(userPayload: String?): String? {
        if (userPayload.isNullOrBlank()) return null

        return runCatching {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(userPayload)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?: return null

            val name = json["name"] as? kotlinx.serialization.json.JsonObject ?: return null
            val first = (name["firstName"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val last = (name["lastName"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

            "$first $last".trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
