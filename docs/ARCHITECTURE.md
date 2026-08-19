# How JMail is put together

## The shape of it

```
┌───────────────────────────────────────────────────────────┐
│  composeApp   Compose Multiplatform UI                    │
│               desktop · web (wasm) · Android · iOS         │
├───────────────────────────────────────────────────────────┤
│  shared       Kotlin Multiplatform                        │
│               API client · repositories · state holders    │
└──────────────────────────┬────────────────────────────────┘
                           │  HTTPS, bearer tokens
┌──────────────────────────┴────────────────────────────────┐
│  backend      Spring Boot                                 │
│               auth · sync · categorisation · REST API      │
└──────────┬───────────────────────────┬────────────────────┘
           │                           │
   ┌───────┴────────┐        ┌─────────┴──────────────────┐
   │  PostgreSQL    │        │    Gmail · MS Graph        │
   └────────────────┘        └────────────────────────────┘
```

## Decisions worth knowing

### The client never holds a provider credential

Every Google, Microsoft and Apple token lives on the server. The client authenticates to
JMail with JMail's own short-lived token and knows nothing about Gmail or Graph. That means
a lost phone exposes one revocable session rather than a mailbox, and revoking access is a
single server-side action.

It also means the OAuth flow has an extra leg: the provider redirects to the *backend*,
which redirects the browser onward to the client with a single-use **handoff code**. The
client exchanges that code for tokens over POST. Tokens never appear in a URL, where they
would persist in browser history, referrer headers and proxy logs.

### One UI, laid out three ways

There is no phone UI and desktop UI. `MailboxScreen` reads its available width and picks
one of three arrangements — one pane, list plus reader, or sidebar plus list plus reader.
Dragging a desktop window narrow produces exactly the phone layout, because it is the same
code taking the same branch.

### State lives in `shared`, not in composables

Every screen's behaviour — what is selected, what an action does, when to load the next page
— lives in a store in `shared/state`. The composables render state and emit intent. That is
what makes the behaviour testable without a UI toolkit, and what lets Android, iOS, desktop
and web behave identically rather than approximately.

### Actions are optimistic, with rollback

Archiving removes the row immediately and issues the request afterwards; a rejection puts it
back and surfaces the error. Waiting for a round trip before the row moves makes the whole
app feel broken on a slow connection, and mail apps are used on slow connections constantly.

### Categories are scored, not matched

A message can look like both a receipt and a promotion. Rather than first-match-wins, every
rule that matches contributes its weight to a category, and the highest total takes it. The
normalised score becomes the confidence the UI can show. A message a user files by hand is
pinned and never re-categorised — overruling an explicit human decision is the fastest way
to lose trust in automation.

### Message HTML is never executed

Bodies are sanitised server-side with a strict allow-list (no scripts, styles, iframes,
event handlers or `javascript:` URLs) and remote images are stripped, their sources kept in
`data-jmail-src` for an explicit "show images". The client then renders the *plain-text*
form. Rendering sender-authored HTML would mean embedding a browser engine on four platforms
and accepting its tracking and exploit surface; the trade is formatting for safety, and the
backend keeps a clean text rendition so nothing is lost but presentation.

### Ownership is enforced in the query

Every read is scoped to the caller's own account ids in the `WHERE` clause, not filtered
afterwards. A guessed message id returns "not found", which is also what another user's
message returns — the response cannot be used to probe what exists.

### Plain foreign keys, no lazy associations

Entities carry `accountId` / `folderId` as UUID columns rather than JPA associations. Every
read path is an explicit owner-scoped query, so associations would only add proxy
initialisation and N+1 risk to the hottest query in the product. Recipient lists are stored
as JSON in the message row: they are always read with their message and never queried by
element, so a child table would add a join for nothing.

### Flyway owns the schema

Hibernate's `ddl-auto` is `none`. Its validator reports false mismatches for `TIMESTAMPTZ`
and generated columns on PostgreSQL, so `PersistenceMappingIntegrationTest` round-trips every
entity against the real schema instead — which catches genuine drift the validator would
miss anyway.

### Optional platforms are detected, not required

`settings.gradle.kts` enables the Android target when an SDK is present and the iOS targets
when Xcode is. A contributor with neither still gets a working build of everything else, and
the Android Gradle Plugin is not even placed on the build classpath.

## Data model

| Table | Holds |
|---|---|
| `users` | The person, and their display preferences |
| `mail_accounts` | Linked mailboxes; encrypted tokens or credentials |
| `refresh_tokens` | SHA-256 hashes only, rotated on every use |
| `categories` / `category_rules` | Shared built-ins (`user_id IS NULL`) plus each user's own |
| `folders` | The provider's own hierarchy, mapped onto one vocabulary |
| `messages` | Denormalised for the list, with a generated `tsvector` for search |
| `attachments` | Metadata; bytes are fetched from the provider on demand |
| `sync_runs` | What each sync did, and what went wrong |

## Where the seams are

Things that would need attention before this served real users at scale:

- **Auth state is in memory.** PKCE sessions and handoff codes live in a `ConcurrentHashMap`
  with a TTL. They expire in minutes, so losing them on restart costs a user one retry — but
  a multi-instance deployment needs sticky sessions for the callback leg, or this moved to
  Redis.
- **Sync is a single scheduled loop.** One thread walks accounts due for sync, deliberately,
  because providers rate-limit per application as well as per account. Beyond a few thousand
  mailboxes this wants a work queue.
- **Attachments are not cached.** Every download goes to the provider.
