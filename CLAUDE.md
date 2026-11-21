# CLAUDE.md — Neutrino Notes for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This is the
Android port of `neutrino_notes_ios_mobile`, built on `../neutrino_shared_android` (consumed by
local path through `includeBuild`, unversioned — a change there reaches this app and every other
Android app at once). Its OAuth client id is `neutrino-notes-android`
(`neutrino/migrations/00160_oauth__*`); its prefs/secrets prefix is `nn`. Never change or reuse
either.

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put logic in `:core` (JVM-tested), not `:app`. A note's body is its Markdown text and nothing
  else; the editor is a text field over it, and its rules (list continuation, slash commands, the
  `[[` picker, formatting) are pure functions in `core/markdown/MarkdownEditing.kt` — a behaviour
  change belongs in a core test first.
- `[[wiki link]]` syntax must agree with the web's `extractWikiLinks` and iOS `WikiLink`; their
  tests are ported in `core/src/test/.../MarkdownTest.kt`.
- Note bodies are E2EE through the shared code — never add crypto here.
- In the editor's Compose code, anything that runs later than the composition that scheduled it
  (the autosave, a dialog's action) reads `value.text`, never a `text` val captured at composition:
  that once saved the previous text over a checkbox tap.
- Status, decisions, the iOS → Android feature map and the next-session checklist:
  `agent_docs/android-port.md`.
