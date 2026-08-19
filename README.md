<div align="center">

# JMail

**All your mail, in one place.**

A unified mail client for Gmail and Microsoft 365 — sign in on your provider's own page,
with categories that sort your inbox for you.

One codebase: **desktop · web · Android · iOS**

[![CI](https://github.com/Bassman8K/jmail/actions/workflows/ci.yml/badge.svg)](https://github.com/Bassman8K/jmail/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Tests](https://img.shields.io/badge/tests-484%20passing-brightgreen.svg)](#testing)
[![Coverage](https://img.shields.io/badge/line%20coverage-94%25-brightgreen.svg)](#testing)

</div>

---

## Try it in one command

```bash
git clone https://github.com/Bassman8K/jmail.git && cd jmail
./run.sh
```

That starts PostgreSQL, runs the migrations, boots the API and tells you where to click.
Then pick how you want to look at it:

```bash
./run.sh desktop   # the desktop app
./run.sh web       # the browser app at http://localhost:3000
```

Sign in with **"Explore the demo mailbox"** — no account, no credentials, no API keys. You
land in a seeded inbox of 20 realistic messages already sorted across every category.

**Requirements:** Docker and Java 17+. Nothing else — the Gradle wrapper fetches its own
toolchain, and the Android SDK and Xcode are detected automatically if you have them.

Only have Docker? Then run everything in containers, using the backend image published with
each release, and skip the build entirely:

```bash
./run.sh docker    # pulls ghcr.io/bassman8k/jmail-backend, serves the app on :3000
```

---

## Downloads

Every release carries a build for each platform. Grab one from
**[Releases](https://github.com/Bassman8K/jmail/releases/latest)**:

| Platform | File | Notes |
|---|---|---|
| **macOS** | `JMail-1.0.0.dmg` | Drag to Applications. Unsigned, so the first launch needs right-click → Open |
| **macOS** | `JMail-1.0.0.pkg` | Installer package |
| **Windows** | `JMail-1.0.0.msi` | Standard installer, per-user |
| **Windows** | `JMail-1.0.0.exe` | Portable installer |
| **Linux** | `jmail_1.0.0-1_amd64.deb` | Debian, Ubuntu, Mint |
| **Linux** | `jmail-1.0.0-1.x86_64.rpm` | Fedora, RHEL, openSUSE |
| **Android** | `JMail-1.0.0.apk` | Signed, sideload directly |
| **Android** | `JMail-1.0.0.aab` | For a Play Store upload |
| **iOS** | `JMail-simulator.zip` | Simulator build — a device build needs your own Apple signing identity |
| **Web** | `jmail-web-v1.0.0.zip` | The built site, to serve yourself — or just use the hosted build below |

The desktop builds are not code-signed, because signing them needs an Apple Developer
identity and a Windows code-signing certificate that belong to whoever publishes the app.
macOS will say the app is from an unidentified developer on first launch (right-click → Open
once, and it stops asking), and Windows SmartScreen will show a "more info" prompt.

The Android APK **is** signed, with a key held in this repository's Actions secrets, so it
installs directly and upgrades in place across releases.

**Web:** [bassman8k.github.io/jmail](https://bassman8k.github.io/jmail) — published from
`main` on every push.

> **Every build needs the backend running.** JMail is a real mail client, not a mock: the
> server holds your provider credentials, syncs your mail and does the categorising. Start it
> with `./run.sh`, then point the app at it. The hosted web build takes a backend URL
> directly: `bassman8k.github.io/jmail/?api=http://localhost:8090`

---

## What it does

### Sign in on your provider's page, not on ours
**Continue with Google**, **Continue with Microsoft**, **Continue with Apple**. You
authenticate on the provider's own website and come back — JMail never sees your password,
and there are no app passwords to create or store.

Mail is read through the Gmail API and Microsoft Graph. Sign in with Apple establishes an
identity only: Apple publishes no mail API, so an Apple account carries no mailbox.

Each provider needs an OAuth client, which they require every app to register.
**[docs/CONNECTING-ACCOUNTS.md](docs/CONNECTING-ACCOUNTS.md)** walks through it, including
the exact redirect URIs. A provider with no credentials configured is simply not offered on
the sign-in screen, so there is never a button that leads nowhere.

### One inbox, many accounts
Connect as many mailboxes as you like. Each gets its own accent colour so a unified list
stays readable, and each can be reconnected or disconnected on its own.

### Categories that do the sorting
Mail is filed automatically into **Primary, Social, Promotions, Updates, Forums, Finance,
Travel and Receipts** by a weighted rule engine — tabs across the top of the list, where
Gmail and Apple Mail put them.

Rules *score* rather than first-match-win, because a message can look like both a receipt and
a promotion, and the strongest combined evidence should decide. Anything you file by hand is
pinned and never re-categorised: overruling an explicit human decision is the fastest way to
lose trust in automation. Add your own categories and rules in Settings.

### Search that finds things
PostgreSQL full-text search over subject, sender and body, with a trigram fallback so partial
order numbers and half-typed addresses match too.

### Reads safely by default
Message HTML is sanitised server-side against a strict allow-list — no scripts, styles,
iframes or event handlers — and remote images are blocked until you ask for them, so opening
a message never confirms to a sender that your address is live.

### Works at any size
One pane on a phone, list plus reader on a tablet, sidebar plus list plus reader on a
desktop. Not three screens — one layout that reads the space it has, so dragging a desktop
window narrow gives you exactly the phone experience.

### Built to be used
Optimistic actions with undo, skeleton loading, empty states that explain *why* they are
empty, errors that say what to do next, full keyboard and screen-reader support (each message
row is announced as one item, read state first), and a dark theme that lifts surfaces with
tonal elevation rather than going pure black.

---

## Commands

```bash
./run.sh              # start everything
./run.sh docker       # start everything in Docker, no Java needed
./run.sh desktop      # launch the desktop app
./run.sh web          # build and serve the browser app
./run.sh test         # every test, plus the coverage gate
./run.sh package      # native installers for this platform
./run.sh status       # what is running
./run.sh logs         # follow the backend log
./run.sh restart      # rebuild and restart the backend
./run.sh down         # stop everything
./run.sh reset        # stop and delete the database
```

---

## How it is built

```
composeApp/   Compose Multiplatform UI — every screen, every platform
shared/       Kotlin Multiplatform: API client, repositories, state holders
backend/      Spring Boot: auth, mail sync, categorisation, REST API
docker/       PostgreSQL, container images
```

| Layer | Technology |
|---|---|
| UI | Compose Multiplatform 1.7 (Jetpack Compose APIs), Material 3 |
| Shared logic | Kotlin Multiplatform 2.1, Ktor client, kotlinx.serialization |
| Backend | Spring Boot 3.4, Kotlin, Gradle Kotlin DSL |
| Database | PostgreSQL 17 with Flyway migrations |
| Mail | Gmail REST API, Microsoft Graph |
| Auth | OAuth 2.0 + PKCE, HS256 sessions, AES-GCM credential encryption |

**The client never holds a provider credential.** Every Google, Microsoft and Apple token
lives on the server; the app authenticates to JMail with JMail's own short-lived token. A lost
device exposes one revocable session rather than a mailbox.

The design decisions, and the seams that would need attention at scale, are written up in
**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

---

## Testing

```bash
./run.sh test
```

**554 tests**, all passing:

| Suite | Count | What it covers |
|---|---|---|
| Backend | 358 | Units, plus integration against a real PostgreSQL and the full Spring stack |
| Shared | 153 | API client, repositories, state holders, the desktop and Android session stores |
| UI | 43 | Compose UI tests that drive the actual screens |

Line coverage is enforced at **92%** by `koverVerify` (currently 94.5%), branch coverage at
**70%** (currently 76.3%); the report lands in `build/reports/kover/html/index.html`.

Coverage is measured over code that has behaviour to assert. Serialization models and
framework wiring are excluded — see the annotated list in `build.gradle.kts`. Leaving them
in made the branch figure a report on the Kotlin compiler's generated `equals`, `hashCode`
and `copy` rather than on JMail: when they were measured they accounted for
781 of 1,263 uncovered branches while contributing 41 uncovered lines.

The integration tests use whichever PostgreSQL is available: an explicit `JMAIL_TEST_DB_URL`,
the local `docker compose` stack, or a Testcontainers instance — so they run the same way on
a laptop and in CI.

---

## Connecting a real account

Sign-in goes through the provider's own website, which means registering an OAuth client
with them first — a one-off, ten minutes for Google.
**[docs/CONNECTING-ACCOUNTS.md](docs/CONNECTING-ACCOUNTS.md)** has click-by-click steps for
Gmail, Microsoft and Apple, including the exact redirect URIs.

Until you do, **Explore the demo mailbox** needs no configuration at all.

`.env.example` documents every setting. A provider with no credentials configured is simply
not offered on the sign-in screen, so there is never a button that leads nowhere.

---

## Documentation

| | |
|---|---|
| **[docs/TESTING.md](docs/TESTING.md)** | A ten-minute walkthrough for reviewing the app |
| **[docs/CONNECTING-ACCOUNTS.md](docs/CONNECTING-ACCOUNTS.md)** | Connecting Gmail, Microsoft, Apple and everything else |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | How it is put together, and why |
| **API reference** | `http://localhost:8090/docs` once running (OpenAPI) |

---

## Platform support

| Platform | Status | Build |
|---|---|---|
| macOS, Windows, Linux | Released | `./run.sh package` |
| Web (WebAssembly) | Released | `./run.sh web` |
| Android | Builds in CI | `./gradlew :composeApp:assembleRelease -Pjmail.android.enabled=true` |
| iOS | Compiles and links; device build needs signing | `./scripts/build-ios.sh` |

Android and iOS targets enable themselves when their SDKs are detected, so a contributor
without them is never blocked by a toolchain they do not have.

---

## Known limitations

Stated plainly, so nothing here is a surprise:

- **Message bodies render as text, not HTML.** Rendering sender-authored HTML means embedding
  a browser engine on four platforms and accepting its tracking and exploit surface. Links are
  detected and clickable; formatting is not preserved.
- **Sign in with Apple does not grant mailbox access** — Apple provides no mail API, so an
  Apple account signs in but has no mail to show. Only Gmail and Microsoft mailboxes sync.
- **No IMAP.** Signing in with an address and password was removed in favour of the browser
  flow, which also removed the app passwords each provider demanded. iCloud, Yahoo, Fastmail,
  Proton and self-hosted servers are therefore not supported.
- **The demo mailbox does not receive new mail.** Refresh honestly reports "No new mail".
- **Auth state is in memory.** PKCE sessions and handoff codes expire in minutes, so a restart
  costs one retry — but a multi-instance deployment needs sticky sessions or Redis.
- **Attachments are not cached**; every download goes to the provider.

---

## License

MIT — see [LICENSE](LICENSE).
