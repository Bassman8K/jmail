# Connecting your accounts

JMail can connect a mailbox two ways: an **app password over IMAP** (works everywhere,
2 minutes) or **OAuth** — the "Continue with Google" style button, where you sign in on the
provider's own page.

- [Gmail](#gmail)
- [Microsoft — Outlook.com and Microsoft 365](#microsoft)
- [Apple — iCloud and Sign in with Apple](#apple)
- [Everything else](#everything-else)

---

## Gmail

Google does not accept your Gmail password over IMAP. This is Google's rule, not JMail's —
they turned off password access for third-party mail apps in May 2022, and their servers
reject the password no matter which app sends it. Apple Mail, Thunderbird and Outlook are
all in the same position.

There are exactly two ways in. Both let you keep using your own account; they differ in
which secret you hand over.

---

## Option 1 — App password (2 minutes)

Google issues a separate 16-character password that only works for mail. Your real password
is never typed into JMail.

1. Turn on 2-Step Verification if it is not already on:
   **https://myaccount.google.com/signinoptions/two-step-verification**
   Google only offers app passwords once 2-Step Verification is enabled.
2. Go to **https://myaccount.google.com/apppasswords**
3. Type a name — "JMail" — and select **Create**.
4. Google shows 16 characters in four groups. Copy them.
5. In JMail: **Use your email address → Gmail**, enter your Gmail address, and paste the app
   password. Spaces do not matter.

JMail links straight to that page from the sign-in form, so you do not have to find it.

**To undo it:** revoke the app password on the same page. It stops working immediately, and
nothing else about your account is affected.

---

## Option 2 — "Continue with Google" (10 minutes, one-off)

This is the familiar Google sign-in page, where you *do* type your real password — into
Google's own page, never into JMail. It needs an OAuth client, which Google requires every
app to register, including one you run yourself.

1. Go to **https://console.cloud.google.com/** and create a project (any name).
2. **APIs & Services → Library** → search for **Gmail API** → **Enable**.
3. **APIs & Services → OAuth consent screen**:
   - User type: **External**
   - Fill in the app name and your email where required
   - On **Scopes**, no changes are needed
   - On **Test users**, add your own Gmail address
   - Leave it in **Testing**; you do not need Google's verification for your own use
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Web application**
   - Authorised redirect URI — exactly this, including the path:
     ```
     http://localhost:8090/api/v1/auth/google/callback
     ```
5. Copy the client ID and client secret into `.env`:
   ```
   GOOGLE_CLIENT_ID=1234567890-abcdef.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=GOCSPX-your-secret
   ```
6. Restart so the backend picks them up:
   ```bash
   ./run.sh restart
   ```
   It prints `OAuth sign-in enabled for: Google` when the credentials have been read.

**Continue with Google** now appears on the sign-in screen.

While the consent screen is in Testing, Google shows an "unverified app" warning — choose
**Advanced → Go to … (unsafe)**. That warning is about Google not having reviewed the app,
which is expected for something you built yourself and are the only user of.

---

## Which to pick

| | App password | Continue with Google |
|---|---|---|
| Setup | 2 minutes | ~10 minutes, once |
| Where your real password goes | nowhere | Google's own page |
| Requires 2-Step Verification | yes | no |
| Revoke access | one page in your Google account | one page in your Google account |

For trying JMail out, the app password is the quicker path. For something you keep using,
OAuth is the better one: JMail never holds a credential that can read your mailbox on its
own, and access can be withdrawn without changing any password.

---

## Microsoft

### App password (Outlook.com, Hotmail, Live)

Outlook.com requires an app password once two-step verification is on:

1. **https://account.microsoft.com/security** → **Advanced security options**
2. Under **App passwords**, choose **Create a new app password**
3. In JMail: **Use your email address → Outlook.com**, then paste it

Microsoft 365 work and school accounts usually accept your normal password over IMAP —
unless your administrator has turned IMAP off for the organisation, which is common. If it
fails with correct credentials, that is almost always why, and OAuth below is the way in.

### Continue with Microsoft

Covers personal, work and school accounts through one app registration.

1. **https://portal.azure.com** → **Microsoft Entra ID** → **App registrations** →
   **New registration**
2. Name it, and under **Supported account types** choose
   **Accounts in any organizational directory and personal Microsoft accounts**
3. **Redirect URI**: platform **Web**, and exactly:
   ```
   http://localhost:8090/api/v1/auth/microsoft/callback
   ```
4. Once created, copy the **Application (client) ID**
5. **Certificates & secrets** → **New client secret** → copy the **Value** (not the ID —
   the value is only shown once)
6. **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated**, and add:
   `openid`, `email`, `profile`, `offline_access`, `Mail.ReadWrite`, `Mail.Send`, `User.Read`
7. Put them in `.env`:
   ```
   MICROSOFT_CLIENT_ID=00000000-0000-0000-0000-000000000000
   MICROSOFT_CLIENT_SECRET=your-secret-value
   MICROSOFT_TENANT_ID=common
   ```
   Leave `MICROSOFT_TENANT_ID` as `common` unless you are restricting it to one organisation.
8. `./run.sh restart`

---

## Apple

### iCloud Mail (app password)

iCloud *always* requires an app-specific password, whatever your settings:

1. **https://account.apple.com** → **Sign-In and Security** → **App-Specific Passwords**
2. Generate one and name it "JMail"
3. In JMail: **Use your email address → iCloud Mail**, then paste it

This is the practical way to read iCloud mail, and takes about a minute.

### Sign in with Apple

Worth knowing before you start: **Sign in with Apple proves who you are — it does not grant
access to your iCloud mailbox.** Apple provides no mail API. So this gives you a JMail
account, and you then connect the mailbox itself with an app password anyway. It also
requires a paid Apple Developer account and an HTTPS redirect URI, which `localhost` is not.

If you still want it:

1. **https://developer.apple.com/account** → **Certificates, Identifiers & Profiles**
2. **Identifiers** → **+** → **App IDs**, and enable **Sign In with Apple**
3. **Identifiers** → **+** → **Services IDs** — this ID is your `APPLE_CLIENT_ID`
4. Configure it with your domain and the return URL:
   ```
   https://your-public-host/api/v1/auth/apple/callback
   ```
   Apple rejects `http://` and rejects `localhost`, so this needs a real hostname — a tunnel
   such as ngrok works for testing.
5. **Keys** → **+** → enable **Sign In with Apple** → download the `.p8` file (once only)
6. Fill in `.env`:
   ```
   APPLE_CLIENT_ID=com.yourdomain.jmail
   APPLE_TEAM_ID=ABCDE12345
   APPLE_KEY_ID=ABCDE12345
   APPLE_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIGT...\n-----END PRIVATE KEY-----
   ```
   Keep the `\n` sequences; JMail converts them back to newlines.
7. Set `JMAIL_BASE_URL` to the same public host, then `./run.sh restart`

---

## Everything else

iCloud, Yahoo, AOL, Fastmail, Zoho and Yandex all require app passwords. JMail recognises
each from your address, says so before you type anything, and links you to the right page.

Anything else that speaks IMAP works through **Other (IMAP)** — you supply the server names,
which your provider's help pages will list.

---

## After editing .env

```bash
./run.sh restart
```

It prints `OAuth sign-in enabled for: …` listing what it picked up. If a provider is missing
from that line, its client ID is still blank, and the button will not appear on the sign-in
screen.
