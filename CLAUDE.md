# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Android app (`com.zektopic.cctvapp`) that turns a spare phone into an
RTSP security camera: an RTSP server on `:8554` and a web dashboard + HTTP API on
`:8081` — all in one foreground service, no cloud component.

## Commands

```bash
./gradlew testDebugUnitTest         # JVM unit tests (app/src/test) — run before any commit
./gradlew lintDebug                 # Android lint
./gradlew assembleDebug             # app/build/outputs/apk/debug/
./gradlew assembleDebugAndroidTest  # compiles src/androidTest without a device

# Single test class or method (standard Gradle test filtering):
./gradlew testDebugUnitTest --tests "com.zektopic.cctvapp.WebAuthTest"
./gradlew testDebugUnitTest --tests "com.zektopic.cctvapp.WebAuthTest.someMethod"
```

CI (`.github/workflows/ci.yml`) runs unit tests, lint, `assembleDebug`, and compiles
(but does not execute) instrumented tests, on every PR and push to `master`. Always run
`testDebugUnitTest` and `lintDebug` locally before considering a change done.

Releases (`.github/workflows/android-release.yml`) are cut only from a `v*` tag or a
manual dispatch — never from every `master` commit — because `versionCode` must be
monotonic for Android to accept an update. Signing needs `keystore.properties` (git-ignored,
repo root) or `RELEASE_KEYSTORE_*` env vars; without either, `assembleRelease` still
succeeds but produces an unsigned APK. Never introduce a `master-SNAPSHOT` dependency
coordinate (see `gradle/libs.versions.toml`) — it previously resolved to a newer
RootEncoder transitively and broke a build whose CI had already passed.

## Architecture

Everything lives under `app/src/main/java/com/zektopic/cctvapp/` (flat package, no
sub-packages). One `CctvServerService` (a `Service`, ~1100 lines) owns the entire
pipeline; `MainActivity` is a thin settings UI that starts/stops it via `Intent` extras,
mirrored by an HTTP-driven path through `WebServer`.

| File | Responsibility |
|---|---|
| `CctvServerService.kt` | Foreground service: camera, encoder, stream lifecycle, snapshot loop, overlays, flashlight/night mode, zoom |
| `WebServer.kt` | NanoHTTPD server: dashboard HTML, `/status`, `/action/*` — wires HTTP requests to callbacks passed in from the service |
| `WebAuth.kt` | HTTP Basic auth, Base64, constant-time compare, CSRF-style Origin check, HTML escaping — deliberately Android-API-free so it runs as plain JVM unit tests |
| `AppPreferences.kt` | SharedPreferences wrapper for every persisted setting |
| `MainActivity.kt` | Settings UI, permission requests, starts/stops the service |
| `BootReceiver.kt` | Optional start-on-boot (blocked by Android 14+ background-start restrictions; posts a "tap to resume" notification instead) |

### Data flow

One JPEG capture loop (`snapshotRunnable` in `CctvServerService`) feeds the dashboard's
`/shot.jpg`. It runs at `ACTIVE_SNAPSHOT_INTERVAL_MS` (500ms) while a dashboard viewer is
active (tracked via `lastSnapshotRequestMs` / `VIEWER_IDLE_TIMEOUT_MS`), and idles at
`IDLE_SNAPSHOT_INTERVAL_MS` (3s) otherwise — capture+JPEG-encode is the single biggest
battery cost, so avoiding it when nobody is watching matters. The RTSP stream itself
comes straight off the hardware encoder via `RtspServerCamera2` (RootEncoder/RTSP-Server),
independent of the snapshot loop.

### Threading rules

`WebServer` callbacks arrive on NanoHTTPD worker threads, but the camera/overlay view
can only be touched from the main thread (`updateViewLayout` throws otherwise). The
pattern throughout `CctvServerService` is: **assign state fields synchronously** on the
calling thread (so an immediate `/status` poll reflects the change), then **post only the
side effect** via `onMain { ... }`. All state shared between the main thread and NanoHTTPD
threads is `@Volatile`.

### Settings duplication

Every setting can be changed two ways — `MainActivity` restarting the service with new
`Intent` extras (`onStartCommand`), or the dashboard hitting `WebServer`'s
`onSettingUpdate` callback — and both paths write through `AppPreferences` so they stay
in sync. When adding a new setting, wire it in both places plus the HTTP API table in
`README.md`.

### Security model (read `WebAuth.kt` and the README "Security" section before touching auth)

- Dashboard auth is Basic, on by default, with a `SecureRandom`-generated password shown
  once on first run. `isAuthorized` intentionally fails *open* when auth is enabled but no
  credentials are configured yet, to avoid locking the owner out — `MainActivity` is
  responsible for seeding the generated password before that gap matters.
- `CctvServerService` reads username/password **live from `AppPreferences`** in the
  `WebServer` callbacks rather than from its own cached fields, because the cache is
  populated in `onCreate()` before `MainActivity` seeds the first-run password — a prior
  bug from this order once served the dashboard unauthenticated.
- `WebAuth.isOriginAllowed` blocks cross-origin requests (CSRF from a browser tab open on
  another site) while still allowing non-browser clients (curl, NVRs) that send no
  `Origin` header at all.
- RTSP auth is separate from dashboard auth and off by default.
- Neither server uses TLS; both are LAN-only by design (see README "Security").

### Testability boundary

Logic that can run without Android APIs is deliberately kept that way — `WebAuth`, and
`MainActivity`'s `parseResolution` (covered by `ResolutionParsingTest`) — specifically so
it has JVM unit test coverage (`app/src/test`) rather than requiring a device/emulator.
`app/src/androidTest` is for the few things that genuinely need one (e.g.
`WebServerAuthInstrumentedTest`). When adding logic, prefer keeping it
Android-API-free and under `app/src/test` if at all possible.

### Known constraints (don't "fix" without reading the comment first)

- `packaging { jniLibs { useLegacyPackaging = true } }` in `app/build.gradle.kts` works
  around third-party native libs not yet 16KB-page-aligned.
- `isMinifyEnabled = false` for release builds is deliberate: RootEncoder resolves classes
  reflectively, and R8 needs a full on-device verification pass first.
- `rootEncoder` and `rtspServer` versions in `libs.versions.toml` must be bumped together
  — `RTSP-Server` pins a `RootEncoder` version transitively.
