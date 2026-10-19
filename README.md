# Quiet Reader

A feed reader that informs without demanding attention.

You open it, it tells you how many posts are waiting, you read them, and you reach the
end. There is no infinite scroll, no unread badge climbing into the thousands, no
notification, and no account. When you're done, you're done.

## Running it

```
run.cmd          (Windows)
./run.sh         (macOS, Linux)
```

Then open <http://localhost:7777>.

The first run builds the program, which takes a minute; after that it starts in under a
second. The scripts use `JAVA_HOME` if you have one, and otherwise look for the JDK that
ships inside IntelliJ IDEA. If neither is there they say so and tell you what to do.

Your reading lives in one file: `~/.quiet-reader/reading.db`. Copy it to move machines,
delete it to start over. Nothing leaves your computer except requests to the feeds
themselves.

Options: `--port 7778` to move it, `READER_FILE=/some/path.db` to put the file elsewhere.

## Using it

**Adding a feed.** One box, and it takes anything: `stratechery.com`,
`https://theverge.com`, or a feed address directly. If a site offers several feeds you
pick one by name. If you're coming from another reader, export your subscriptions and
use the file picker underneath.

**A sitting** is a fixed set of posts — twenty by default — decided the moment you press
*Begin reading*. Posts that arrive while you're reading wait for next time. That's the
whole idea: the pile in front of you can only shrink.

**While reading:** `Next` is the only prominent button. `Save` keeps a post, `← Back`
undoes a step you didn't mean to take, and `Leave` ends the sitting with everything you
haven't seen still unread. With JavaScript on, `space` or `j` moves on, `s` saves,
`Backspace` goes back and `Esc` leaves.

**Images don't load.** Each one becomes a small placeholder carrying its alt text, and
fetches only when you click it. Open your browser's network panel while reading and you
will see no image requests at all.

**Settings** has three controls and nothing else:

| Setting | Options | Default |
|---|---|---|
| Posts per sitting | 10 · 20 · 30 | 20 |
| Order | Newest first · Oldest first | Newest first |
| Forget unread posts after | Never · 1 week · 2 weeks · 1 month | Never |

Nothing is ever deleted unless you choose that last one.

## What it deliberately doesn't do

**Full text for summary-only feeds.** Plenty of feeds publish a paragraph and a link.
Rather than pretend otherwise, those posts are labelled "This feed only publishes
summaries" and offer *Read on site*. Extracting article text properly is a readability
algorithm and a project of its own; doing it badly would be worse than being honest.

**Sync, accounts, mobile apps, folders, tags, search, starring with colours.** One
person, one machine, one file.

## How it's built

Server-rendered HTML on the JDK's built-in web server. No framework, no frontend build,
no JavaScript required for anything — every action is a real form or link. The jar is
self-contained.

```
src/main/java/reader/
  Main.java                  wiring and the process
  http/     Server Router Assets      routing, forms, static files
  view/     Pages Html Copy           every screen; Copy holds every word
  store/    Db FeedStore ItemStore SessionStore Settings
  feed/     Fetcher Parser Sanitizer Discovery Opml Refresher Scheduler Sweeper
  model/    Feed Item Sitting
src/main/resources/  schema.sql, static/reader.css, static/reader.js
```

Two pieces carry most of the design:

- **`view/Copy.java`** holds every user-facing string as a named constant, so the voice
  is reviewable in one file. A test asserts that none of them says *session*, *RSS*,
  *URL*, *XML* or *HTTP* — the reader is never shown the plumbing.
- **`store/SessionStore.java`** makes the sitting a database object. Beginning one
  snapshots N unread posts; nothing can join it afterwards. Without that snapshot,
  "done" would only be a scroll position that refills behind you.

Feed HTML is untrusted input, so it's run through an allowlist at ingest — scripts,
frames, event handlers and tracking pixels are removed, relative links are resolved, and
every link is set to open in a new tab.

Checking feeds happens on a background pool every thirty minutes and at startup. A
manual check hands the work off and returns immediately to a *Checking your feeds…*
page that polls itself with a meta refresh, so a slow feed can never hold a page open.

## Tests

```
mvn test        # 93 tests
mvn package     # target/rss-reader.jar
```

Neither needs a network: tests that exercise fetching run against a throwaway local
server on a free port.

## Two notes on the plan this was built from

- The plan named the sitting routes `/session`, while its own acceptance criteria say
  the word *session* must appear nowhere in the rendered output. The criteria won:
  the routes are `/sitting` and `/sitting/end`. The database tables keep the planned
  names, since those are never rendered.
- The plan asks for exactly one prominent control in the reading view (`Next`) and also
  calls *Read on site* prominent for summary-only posts. `Next` stays the only filled
  button; *Read on site* is outlined — clearly the action for a thin post, without
  competing with `Next`.
