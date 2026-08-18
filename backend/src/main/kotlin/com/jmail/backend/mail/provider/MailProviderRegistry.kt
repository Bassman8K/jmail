package com.jmail.backend.mail.provider

import com.jmail.backend.common.BadRequestException
import com.jmail.backend.user.AccountProvider
import com.jmail.backend.user.MailAccount
import org.springframework.stereotype.Component

/**
 * Resolves the [MailProvider] that can serve a linked account.
 *
 * IMAP-only accounts reuse the Exchange implementation: the protocol is identical, and the
 * distinction exists only so the UI can label the account correctly and so that Exchange
 * autodiscovery defaults are applied to one and not the other.
 */
@Component
class MailProviderRegistry(providers: List<MailProvider>) {

    private val byProvider: Map<AccountProvider, MailProvider> = providers.associateBy(MailProvider::provider)

    fun forAccount(account: MailAccount): MailProvider = forProvider(account.provider)

    fun forProvider(provider: AccountProvider): MailProvider {
        val resolved = if (provider == AccountProvider.IMAP) AccountProvider.EXCHANGE else provider

        return byProvider[resolved]
            ?: throw BadRequestException(
                "unsupported_provider",
                "JMail cannot sync ${provider.displayName} mailboxes",
            )
    }

    /** Apple accounts sign in but carry no mailbox of their own; iCloud mail is added as IMAP. */
    fun canSync(account: MailAccount): Boolean =
        account.provider != AccountProvider.APPLE || account.imapHost != null
}
