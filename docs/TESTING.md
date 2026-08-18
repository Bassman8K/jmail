# Trying JMail

A walkthrough for someone reviewing the app rather than working on it. Takes about ten
minutes. You need Docker running and Java 17+; nothing else, and no accounts of any kind.

```bash
./run.sh          # starts the database and the API
./run.sh desktop  # or: ./run.sh web
```

Choose **"Explore the demo mailbox"** on the sign-in screen. You are now in a seeded inbox of
20 messages built to look like a real one — colleagues, a bank statement, a boarding pass, a
shipping confirmation, some marketing, a mailing list.

---

## What to try

### The inbox
- **Scan the list.** Unread messages are marked three ways at once — a heavier weight, an
  amber rail on the left, and a tinted row — because colour alone fails for a colour-blind
  reader and weight alone is easy to miss.
- **Hover a row** (desktop). The avatar turns into a checkbox where your pointer already is,
  rather than there being a permanently empty column of checkboxes.
- **Look at the date headers.** Today, Yesterday, This week, This month, Earlier — you can
  tell how far you have scrolled without reading a single date.
- **Star something.** It applies instantly; the request happens afterwards.

### Categories
- **Tabs across the top of the list** — All mail, Primary, Social, Promotions, Updates,
  Forums, Finance, Travel, Receipts — with live unread counts, the way Gmail and Apple Mail
  do it. The row scrolls sideways when the window is narrow. Everything you see was filed
  automatically by the rule engine when the mailbox was seeded.
- The sidebar is now only about *where mail lives* (Inbox, Starred, Sent, Drafts, Archive,
  Spam, Trash) and which accounts it comes from.
- **Click Travel** — the boarding pass should be there. **Receipts** should hold the order
  confirmation and the shipping notice. **Finance** should hold the bank statement and the
  Stripe invoice.
- Messages carry a small coloured category dot in the list, except in Primary (where a
  marker on nearly every row would mean nothing).

### Reading
- **Open a message.** On a wide window it opens beside the list; narrow the window and it
  takes over the pane instead. The same happens on a phone.
- **Open "Re: Thursday's design review"** — it is a three-message thread, and the rest of
  the conversation is listed under the message you opened.
- Notice **"to 1 recipient"** under the sender; click it to expand the full list.
- Attachments show as cards with their real size.

### Search
- Search for **boarding** — full-text search finds the flight message by a word in its body.
- Search for **meridian** — a partial sender match, which stemmed full-text search alone
  would miss; the trigram fallback catches it.
- Search for **z** — one character is refused with an explanation rather than running a
  useless query.

### Selection and undo
- **Check two or three messages.** The toolbar becomes a selection bar stating exactly how
  many are selected, so a destructive action is never ambiguous.
- **Archive them.** They disappear immediately and a snackbar offers **Undo** for six
  seconds. Press it; they come back.

### Composing
- **Compose.** Type an address and press comma or Enter — it becomes a chip, so an accepted
  address looks different from one you are still typing. Backspace on an empty field removes
  the last chip.
- Try to send with no recipient, or with no body: each is refused with a message pointing at
  the field.
- **Reply to a message with several recipients** and choose Reply all — everyone carries over
  except you.
- **Close the composer with unsent text.** It offers to save a draft rather than silently
  discarding your writing.

### Empty and error states
- **Filter to Unread**, then open a category with nothing unread in it. The empty state
  explains it is the filter and offers to clear it — different from the "You're all caught
  up" you get with a genuinely empty inbox.
- **Stop the backend** (`./run.sh down` in another terminal) and click around. You get
  "You're offline" with a Try again button, not a spinner forever or a raw error.

### Settings
- **Theme**: System, Light, Dark. Switch to Dark — surfaces lift with tonal elevation rather
  than going pure black, which is easier to read for long stretches.
- **Density**: Compact, Comfortable, Spacious. This changes row height only — text never
  shrinks, because shrinking type is an accessibility regression, not a density setting.

### Accessibility
Worth checking if you can: turn on VoiceOver (⌘F5 on macOS) and move through the list. Each
row is announced as **one** item — read state first, then sender, subject, time, and any
attachment or star — rather than as six disconnected fragments.

---

### Connecting your own mailbox
- Go back to the sign-in screen and choose **Use your email address**. You get a list of
  real services — Gmail, Outlook.com, iCloud, Yahoo, Fastmail, Proton, an Exchange server
  and so on.
- Pick **Gmail**. The servers are filled in for you, and the form tells you *before* you
  type anything that Gmail will not accept your normal password, with a button that opens
  the page where you create an app password.
- The same happens if you just type your address: JMail recognises the domain and adapts.
- Sign in with a real app password and your actual mail syncs in.
- Gmail, iCloud and Yahoo will not accept your account password — that is their policy, not
  JMail's. **[docs/CONNECTING-ACCOUNTS.md](CONNECTING-ACCOUNTS.md)** covers both ways round it.

## What is deliberately not there

Honest notes, so you are not hunting for things that were never built:

- **Message bodies render as text, not HTML.** JMail shows the plain-text form of every
  message rather than executing sender-authored HTML. Rendering arbitrary email HTML means
  embedding a browser engine on every platform and accepting the tracking and exploit
  surface that comes with it. Links are detected and clickable; formatting is not preserved.
- **Attachments show as metadata in the demo.** There are no real bytes behind the demo
  mailbox, so downloads are wired but have nothing to fetch. On a connected Gmail, Graph or
  IMAP account they download normally.
- **The demo mailbox does not receive new mail.** Pressing refresh checks and honestly
  reports "No new mail". Connect a real account to see sync do its thing.
- **Sending from the demo account goes nowhere.** It lands in Sent and is not transmitted.

## Running the tests

```bash
./run.sh test
```

461 tests: unit tests, integration tests against a real PostgreSQL, the Exchange sign-in path
against a real IMAP server, and Compose UI tests that drive the actual screens. Coverage is
enforced at 80% of lines and the report opens at
`build/reports/kover/html/index.html`.

## If something goes wrong

| Symptom | Fix |
|---|---|
| `Docker is installed but not running` | Start Docker Desktop and re-run |
| Port already in use | Change `BACKEND_PORT` or `WEB_PORT` in `.env` |
| The backend will not start | `./run.sh logs` shows the full log |
| Anything stuck | `./run.sh reset` deletes the database and starts fresh |

## Leaving feedback

The most useful things to note as you go: anywhere you hesitated, anything you expected to
be somewhere else, and any message that told you *what* happened without telling you *what
to do about it*.
