package com.jmail.backend.mail.provider

import com.jmail.backend.common.BadRequestException
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import org.springframework.stereotype.Component

/**
 * Resolves the [MailProvider] that can serve a linked account.
 *
 * Only Google and Microsoft mailboxes can be synced: both are reached through their own API
 * with an OAuth token. EXCHANGE and IMAP accounts predate web-only sign-in and no longer
 * have an implementation, so they resolve to a clear "cannot sync" rather than to nothing.
 */
@Component
class MailProviderRegistry(providers: List<MailProvider>) {

    private val byProvider: Map<AccountProvider, MailProvider> = providers.associateBy(MailProvider::provider)

    fun forAccount(account: MailAccount): MailProvider = forProvider(account.provider)

    fun forProvider(provider: AccountProvider): MailProvider =
        byProvider[provider]
            ?: throw BadRequestException(
                "unsupported_provider",
                "JMail cannot sync ${provider.displayName} mailboxes",
            )

    /**
     * Apple issues an identity, not a mailbox: iCloud has no mail API, and the IMAP route
     * that used to reach it went with password sign-in. An Apple account therefore signs in
     * but has nothing to sync, and the sync scheduler skips it rather than failing it.
     */
    fun canSync(account: MailAccount): Boolean = byProvider.containsKey(account.provider)
}
