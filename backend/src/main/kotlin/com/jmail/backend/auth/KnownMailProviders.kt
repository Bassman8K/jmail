package com.jmail.backend.auth

import com.jmail.backend.common.EmailAddresses

/**
 * What JMail knows about the mail services people actually use.
 *
 * Autodiscovery by naming convention (`imap.<domain>`) works for self-hosted servers and
 * almost nothing else: none of the large providers follow it. This directory is what lets
 * someone type their address and be connected without knowing a port number.
 *
 * The `requiresAppPassword` flag matters more than the host names. Every major provider now
 * refuses an account password over IMAP when two-factor authentication is on, and the error
 * they return is an unhelpful "authentication failed". Telling the user *before* they try —
 * and linking them straight to the page that issues one — is the difference between signing
 * in and giving up.
 */
data class KnownMailProvider(
    val id: String,
    val displayName: String,
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String,
    val smtpPort: Int = 587,
    val useTls: Boolean = true,
    /** Domains that identify this provider. Empty for entries chosen by hand. */
    val domains: Set<String> = emptySet(),
    /** True when the provider rejects the normal account password over IMAP. */
    val requiresAppPassword: Boolean = false,
    /** Where the user creates that app password. */
    val appPasswordUrl: String? = null,
    /** One sentence shown under the password field. */
    val helpText: String? = null,
    /** Ordering in the picker; lower comes first. */
    val position: Int = 100,
)

object KnownMailProviders {

    val GMAIL = KnownMailProvider(
        id = "gmail",
        displayName = "Gmail",
        imapHost = "imap.gmail.com",
        smtpHost = "smtp.gmail.com",
        domains = setOf("gmail.com", "googlemail.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://myaccount.google.com/apppasswords",
        helpText = "Gmail needs a 16-character app password, not your Google password. " +
            "Two-step verification must be on before you can create one.",
        position = 0,
    )

    val OUTLOOK = KnownMailProvider(
        id = "outlook",
        displayName = "Outlook.com",
        imapHost = "outlook.office365.com",
        smtpHost = "smtp-mail.outlook.com",
        domains = setOf("outlook.com", "hotmail.com", "live.com", "msn.com", "passport.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://account.microsoft.com/security",
        helpText = "Outlook.com needs an app password when two-step verification is on. " +
            "Create one under Security → Advanced security options.",
        position = 1,
    )

    val OFFICE365 = KnownMailProvider(
        id = "office365",
        displayName = "Microsoft 365 / Exchange Online",
        imapHost = "outlook.office365.com",
        smtpHost = "smtp.office365.com",
        domains = setOf("office365.com", "microsoft.com"),
        helpText = "Work and school accounts. If your organisation has disabled IMAP, ask " +
            "your administrator to enable it, or use Microsoft sign-in instead.",
        position = 2,
    )

    val ICLOUD = KnownMailProvider(
        id = "icloud",
        displayName = "iCloud Mail",
        imapHost = "imap.mail.me.com",
        smtpHost = "smtp.mail.me.com",
        domains = setOf("icloud.com", "me.com", "mac.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://account.apple.com/account/manage",
        helpText = "iCloud always requires an app-specific password. Create one at " +
            "account.apple.com under Sign-In and Security.",
        position = 3,
    )

    val YAHOO = KnownMailProvider(
        id = "yahoo",
        displayName = "Yahoo Mail",
        imapHost = "imap.mail.yahoo.com",
        smtpHost = "smtp.mail.yahoo.com",
        smtpPort = 465,
        domains = setOf("yahoo.com", "yahoo.co.uk", "yahoo.com.au", "ymail.com", "rocketmail.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://login.yahoo.com/account/security",
        helpText = "Yahoo requires an app password generated from Account Security.",
        position = 4,
    )

    val AOL = KnownMailProvider(
        id = "aol",
        displayName = "AOL Mail",
        imapHost = "imap.aol.com",
        smtpHost = "smtp.aol.com",
        smtpPort = 465,
        domains = setOf("aol.com", "aim.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://login.aol.com/account/security",
        helpText = "AOL requires an app password generated from Account Security.",
        position = 5,
    )

    val FASTMAIL = KnownMailProvider(
        id = "fastmail",
        displayName = "Fastmail",
        imapHost = "imap.fastmail.com",
        smtpHost = "smtp.fastmail.com",
        smtpPort = 465,
        domains = setOf("fastmail.com", "fastmail.fm", "messagingengine.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://app.fastmail.com/settings/security/apps",
        helpText = "Fastmail requires an app password with mail access.",
        position = 6,
    )

    val ZOHO = KnownMailProvider(
        id = "zoho",
        displayName = "Zoho Mail",
        imapHost = "imap.zoho.com",
        smtpHost = "smtp.zoho.com",
        smtpPort = 465,
        domains = setOf("zoho.com", "zohomail.com"),
        requiresAppPassword = true,
        appPasswordUrl = "https://accounts.zoho.com/home#security/app_password",
        helpText = "Zoho requires an application-specific password.",
        position = 7,
    )

    val GMX = KnownMailProvider(
        id = "gmx",
        displayName = "GMX",
        imapHost = "imap.gmx.com",
        smtpHost = "mail.gmx.com",
        domains = setOf("gmx.com", "gmx.net", "gmx.de", "gmx.co.uk"),
        helpText = "IMAP must be enabled once in GMX settings before it will accept a connection.",
        position = 8,
    )

    val MAIL_COM = KnownMailProvider(
        id = "mailcom",
        displayName = "Mail.com",
        imapHost = "imap.mail.com",
        smtpHost = "smtp.mail.com",
        domains = setOf("mail.com", "email.com", "usa.com"),
        position = 9,
    )

    val PROTON = KnownMailProvider(
        id = "proton",
        displayName = "Proton Mail (Bridge)",
        imapHost = "127.0.0.1",
        imapPort = 1143,
        smtpHost = "127.0.0.1",
        smtpPort = 1025,
        useTls = false,
        domains = setOf("proton.me", "protonmail.com", "pm.me"),
        requiresAppPassword = true,
        appPasswordUrl = "https://proton.me/mail/bridge",
        helpText = "Proton encrypts mail end to end, so IMAP goes through Proton Bridge " +
            "running on this machine. Use the credentials Bridge shows you, not your Proton password.",
        position = 10,
    )

    val YANDEX = KnownMailProvider(
        id = "yandex",
        displayName = "Yandex Mail",
        imapHost = "imap.yandex.com",
        smtpHost = "smtp.yandex.com",
        smtpPort = 465,
        domains = setOf("yandex.com", "yandex.ru", "ya.ru"),
        requiresAppPassword = true,
        appPasswordUrl = "https://id.yandex.com/security/app-passwords",
        helpText = "Yandex requires an app password created under Security → App passwords, " +
            "and IMAP must be switched on in Yandex Mail settings.",
        position = 11,
    )

    /** An on-premises Exchange server, reached over IMAP. */
    val EXCHANGE_ON_PREMISES = KnownMailProvider(
        id = "exchange",
        displayName = "Exchange Server (on-premises)",
        imapHost = "",
        smtpHost = "",
        helpText = "Enter the server your organisation gave you. It often looks like " +
            "mail.yourcompany.com or exchange.yourcompany.com.",
        position = 20,
    )

    /** Anything else: the user supplies the servers. */
    val OTHER = KnownMailProvider(
        id = "other",
        displayName = "Other (IMAP)",
        imapHost = "",
        smtpHost = "",
        helpText = "Any mail service that speaks IMAP and SMTP. Your provider's help pages " +
            "will list the server names.",
        position = 21,
    )

    /** Everything offered in the picker, in display order. */
    val all: List<KnownMailProvider> = listOf(
        GMAIL, OUTLOOK, OFFICE365, ICLOUD, YAHOO, AOL, FASTMAIL, ZOHO,
        GMX, MAIL_COM, PROTON, YANDEX, EXCHANGE_ON_PREMISES, OTHER,
    ).sortedBy(KnownMailProvider::position)

    private val byDomain: Map<String, KnownMailProvider> =
        all.flatMap { provider -> provider.domains.map { domain -> domain to provider } }.toMap()

    fun byId(id: String): KnownMailProvider? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * Recognises the provider from an address.
     *
     * Returns null for an unknown domain rather than guessing, so the caller can be honest
     * about the difference between "we know this service" and "we are trying a convention".
     */
    fun forEmail(email: String): KnownMailProvider? {
        val domain = EmailAddresses.domainOf(email)
        if (domain.isEmpty()) return null

        byDomain[domain]?.let { return it }

        // Organisations on Microsoft 365 keep their own domain, and the safest tell without
        // a DNS lookup is the `onmicrosoft.com` fallback domain every tenant also has.
        if (domain.endsWith(".onmicrosoft.com")) return OFFICE365

        return null
    }
}
