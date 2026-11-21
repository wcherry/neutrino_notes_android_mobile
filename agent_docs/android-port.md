# Neutrino Notes for Android — port notes and handoff

**Started:** 2026-11-20. **Status:** the iOS Notes app's features, less the ones listed under "Not
ported"; run on an Android 14 emulator against a real local server. Never yet on a physical
device or the hosted server.

## 1. Decisions

| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36` | Same toolkit and floor as Drive, Calendar and Docs for Android, so the shared library has one target. |
| `:core` is plain Kotlin/JVM; `:app` is Android | The API client, Markdown parser, typing rules, offline cache, sync, tags, sharing and links are tested on the JVM in seconds against `FakeServer` from the shared test fixtures, and once against a real server (`LiveServerTest`). |
| A note's body is its Markdown text, UTF-8, under secretstream with a per-file DEK | What iOS and the web store (`text/markdown`, Drive's `type=note` filter). Names get `.md`, as iOS's do; the web's have none, so link titles travel in both spellings. |
| Parser: commonmark-java + GFM tables, strikethrough, task lists | iOS uses swift-markdown (cmark-gfm). Footnotes and `[[wiki links]]` are the iOS pre/post pass, ported. Block source spans give each task item its line, so a tapped checkbox flips the right `[ ]`. |
| Editor: a `BasicTextField` over the text; rules are pure functions in `core/markdown/MarkdownEditing.kt` | The text is the note, so there is no model to keep in step (unlike Docs). Return is detected as a lone newline at the caret and handed to the list rule; the slash menu and `[[` picker are tokens computed from the caret. |
| Autosave 1.5 s after typing stops, guarded by `expectedContentVersion` | iOS's interval. iOS saves unguarded; the guard here turns a silent overwrite of another device's edit into a "Use theirs / Keep mine" choice. |
| OAuth client `neutrino-notes-android`, migration `00160` | Root CLAUDE.md rule 3. The same migration registers `neutrino-sheets-android` and `neutrino-photos-android`, so those ports need none of their own. **Sign-in fails against any server without it.** |
| Prefix `nn` | Same namespace as iOS Notes. |
| Link titles go to `PATCH /links/{id}` after every online save and every synced offline edit | The iOS rule. A deliberate, narrow disclosure of titles from an encrypted body; skipped when the set hasn't changed. |
| Live updates over the file-events relay (`/api/v1/files/{id}/ws`) | A doorbell, never content; a peer's save reloads the note when nothing local is unsaved, and raises a banner otherwise. The OkHttp socket has no read timeout and pings every 30 s. |
| Background sync: 15-minute periodic WorkManager job | Android's equivalent of iOS background refresh. |

## 2. Feature map (iOS → Android)

Ported: sign-in/registration, key setup, pairing, recovery kit, key code · browser (My Notes,
Favorites, Shared, Recent, Trash; folders; sort; create, rename, duplicate, move, star, trash,
restore, delete forever, empty trash) · editor with undo/redo, find & replace, slash commands,
formatting bar, list continuation, word/character/reading-time stats · preview: headings, lists,
task lists, tables, code, quotes, rules, links, footnotes, `[[wiki links]]` (resolved and
unresolved), "Linked from" backlinks · following a `[[link]]`, a relative `Note.md` link or an
`/open/note/` link; creating the note an unresolved link names · tags (Tags tab, tagged notes,
per-note picker, create/rename/delete) · sharing (people, roles, key delivery, "send key again",
folders shared note by note) · version history (save named, read, compare, restore) · offline
copies, offline edits, conflicts, queued deletes/moves/renames · live file events · settings
(theme, editor font and size, list continuation, open in preview, sync, cache, devices, app lock,
report a bug) · send a copy as `.md`, copy link.

Mapped to Android equivalents:

| iOS | Android |
|---|---|
| `UITextView` + find navigator | `BasicTextField` + `FindBar` |
| Floating slash / `[[` menus | A suggestion bar above the keyboard |
| Universal links, custom scheme | App Links (`/open/note/<id>`, see `deploy/assetlinks.json`) and `neutrinonotes://note/<id>` |
| `BGAppRefreshTask` | WorkManager |

**Not ported:** the Drive preview picture of page one (`NotePreviewer`) — the Drive grid shows a
generic icon for notes written here; device-local pins (`PinStore`; Favorites is the synced
equivalent); printing; images inside notes are shown as `[alt]`, not fetched.

## 3. Deviations from iOS

- **Lists.** Return continues bullets and numbers as well as task lists (iOS: task lists only).
  Return on an empty item ends the list *and leaves a blank line*: without it the next line is a
  CommonMark lazy continuation of the last item, which is what iOS does today — **iOS still has
  this**.
- **Checkboxes** in the preview flip when the note is writable (iOS draws them read-only).
- **Autosave is version-guarded** (see §1); iOS's is not.

## 4. What is verified (2026-11-20, macOS host)

- `./gradlew :core:test` — 30 tests (+ the live test, skipped without a server): wiki-link rules
  ported from the web/iOS tests, typing rules, the parser (tasks with source lines, tables,
  footnotes), diff, file-event frames, and the services against a fake backend with real crypto
  (create/save/guarded conflict, duplicate, offline edit drained with its links, a queued edit
  never overwriting a remote one, offline rename/delete replay, versions, tags, sharing with a key
  the recipient can open).
- `LiveServerTest` against a locally built server with migration 00160: create, save under the
  guard and see a stale save refused, backlinks, tags, versions (save/read/restore), an offline
  edit drained by the sync engine, sharing to a second account who decrypts the note, rename keeps
  `.md`, trash.
- On the emulator (Pixel 3a image, API 34) against that server: registration with a custom server,
  key setup, browser, creating a note, typing with list continuation and the `[[` picker, preview,
  ticking a checkbox (persists across reopen), creating a note from an unresolved link, backlinks,
  tagging, the Tags tab, saving/listing/comparing a version, making a note available offline,
  editing with the network off ("Saved offline"), sync on reconnect, the share dialog, Settings.
- `neutrino_shared_android` core tests and the Drive, Calendar and Docs builds pass on the changed
  library.

**Not verified:** a physical device; the hosted server; a real on-screen keyboard composing words
(the emulator was fed key events); opening a note written on the web or iOS through the UI (the
format is plain Markdown under the shared crypto, and the live test reads what it writes through
the same paths); app links (need `assetlinks.json` served); app lock with biometrics; release (R8)
build at run time; two devices on one note over the relay.

## 5. Next session checklist

1. Merge/deploy `neutrino` `feature/android-notes-client` (migration 00160).
2. Physical device with Gboard: typing, Return in lists, `/` and `[[` menus.
3. Open a note written on the web and one written on iOS; edit; reopen on both.
4. Two devices on one note: a save on one reloads the other.
5. Serve `deploy/assetlinks.json` with the release certificate; check `/open/note/<id>` opens here.
6. `./gradlew :app:assembleRelease` with a signing config; run it (R8 + kotlinx serialization + commonmark).
7. Port the blank-line-after-list fix (§3) to iOS `MarkdownChecklist`.
