# Neutrino Notes for Android

The Android port of `neutrino_notes_ios_mobile`: end-to-end encrypted Markdown notes stored in
Neutrino Drive, with a Markdown editor and preview, `[[wiki links]]` and backlinks, tags, sharing,
version history and offline editing. Notes written here open on the web and iOS, and the other
way round. Everything goes through [`wcherry/neutrino`](https://github.com/wcherry/neutrino)
(`/api/v1`); the app has no backend of its own.

- `core/` — plain Kotlin/JVM: the Notes API client, the encrypted note body, browsing, the offline
  cache and its sync, version history, tags, sharing, the link graph, the file-events relay, the
  Markdown parser the preview draws, and the editor's typing rules.
- `app/` — the Android app (Jetpack Compose).

## Layout

This repository must sit next to `neutrino_shared_android`, which it builds from source:

```
getneutrino.app/
├── neutrino_shared_android/
└── neutrino_notes_android_mobile/   ← this repo
```

## Build

To install the app on an emulator or phone, see [`INSTALL.md`](INSTALL.md).

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test             # Markdown, typing rules, services against a fake server
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
NEUTRINO_LIVE_URL=http://localhost:8080 ./gradlew :core:test --tests '*LiveServerTest*'
```

The live test registers two fresh accounts each run — point it at a throwaway database.

## Platform registration

- **OAuth client** `neutrino-notes-android` is seeded by `neutrino/migrations/00160_oauth__*`.
  Sign-in fails against a server without it.
- **App config and brand** are `NeutrinoApps.notes` (shared) and `NotesBrand`
  (`app/.../ui/Components.kt`). The prefs/secrets prefix is `nn`, the iOS app's. Never change or
  reuse it.

From the emulator, a server on your computer is `http://10.0.2.2:8080`. See
`agent_docs/android-port.md` for decisions, what is verified, and what is left.
