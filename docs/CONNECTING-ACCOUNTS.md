# Connecting your accounts

JMail signs in through your provider's own website. You click **Continue with Google** (or
Microsoft, or Apple), authenticate on their page, and come back — JMail never sees your
password, and there are no app passwords to create.

That does mean each provider needs an OAuth client, which they require every app to
register, including one you run yourself. It is a one-off, and this page walks through it.

- [Gmail](#gmail)
- [Microsoft — Outlook.com and Microsoft 365](#microsoft)
- [Apple — Sign in with Apple](#apple)
- [After editing .env](#after-editing-env)

---

## Gmail

The familiar Google sign-in page, where you type your real password into Google's own page,
never into JMail. Takes about ten minutes, once.

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

## Microsoft


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

**Read this before setting it up: Sign in with Apple proves who you are, and does not give
JMail access to your iCloud mailbox.** Apple publishes no mail API, so an Apple sign-in
creates a JMail account with no mail in it. It is here because it is a legitimate way to
create an identity, not because it will show you your iCloud inbox — for that, sign in with
the Google or Microsoft account you actually read mail in.

It also needs a paid Apple Developer account and an HTTPS redirect URI, which `localhost` is
not. If you still want it:

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

Providers other than Google and Microsoft are not supported. JMail reads mail through the
Gmail API and Microsoft Graph; the IMAP route that used to reach iCloud, Yahoo, Fastmail and
self-hosted servers required an app password for each one, and was removed in favour of
signing in on the provider's own page.

---

## After editing .env

```bash
./run.sh restart
```

It prints `OAuth sign-in enabled for: …` listing what it picked up. If a provider is missing
from that line, its client ID is still blank, and the button will not appear on the sign-in
screen.
