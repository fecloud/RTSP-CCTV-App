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

`app/src/main/java/com/zektopic/cctvapp/` is split into sub-packages by concern (it used
to be one flat package, but it grew past the point that stayed readable):

| Package | Files | Responsibility |
|---|---|---|
| *(root)* | `MainActivity.kt`, `BootReceiver.kt`, `CctvApplication.kt` | App entry points — the components Android launches by class name, plus process-wide init (Bugly, `AppLog`) in `CctvApplication.onCreate` |
| `.service` | `CctvServerService.kt`, `GalleryRecordingManager.kt`, `OverlayWindow.kt`, `ServiceNotificationUtil.kt` | The foreground service: camera/encoder/stream lifecycle, gallery recording, the overlay window, the notification |
| `.settings` | `AppPreferences.kt`, `ServiceSettings.kt`, `SettingsRepository.kt`, `SettingEffects.kt`, `SettingUpdateHandler.kt` | Settings persistence and the shared in-memory data source (see "Settings: one shared data source" below) |
| `.web` | `WebServer.kt`, `WebAuth.kt` | NanoHTTPD dashboard server + HTTP Basic auth |
| `.camera` | `CameraResolutionUtil.kt` | Camera2 supported-resolution querying, shared by the service, `GalleryRecordingManager`, and `MainActivity` |
| `.device` | `DeviceStatsUtil.kt`, `ThermalZoneUtil.kt` | Battery/CPU/Wi-Fi telemetry surfaced in `/status` and the timestamp overlay |
| `.log` | `AppLog.kt`, `LogLineFormatter.kt` | `android.util.Log`-compatible logger (import-aliased in at every call site) that also persists every line to a rotating file under app-specific external storage (falling back to internal storage if unavailable), since logcat isn't retrievable after the fact from a background service on someone else's phone |

`CctvServerService` no longer owns the whole pipeline itself — it composes the classes in
`.service` and reacts to `.settings`'s shared repository (see below). `MainActivity` is a
thin settings UI; it does **not** talk to a running service via `Intent` for settings
anymore (see "Settings: one shared data source").

### Data flow

One JPEG capture loop (`CctvServerService.startSnapshotLoop`, a coroutine) feeds the
dashboard's `/shot.jpg`. It runs at `ACTIVE_SNAPSHOT_INTERVAL_MS` (500ms) while a
dashboard viewer is active (tracked via `lastSnapshotRequestMs` / `VIEWER_IDLE_TIMEOUT_MS`),
and idles at `IDLE_SNAPSHOT_INTERVAL_MS` (3s) otherwise — capture+JPEG-encode is the
single biggest battery cost, so avoiding it when nobody is watching matters. The RTSP
stream itself comes straight off the hardware encoder via `RtspServerCamera2`
(RootEncoder/RTSP-Server), independent of the snapshot loop.

### Threading rules

`WebServer` callbacks arrive on NanoHTTPD worker threads, but the camera/overlay view
can only be touched from the main thread (`updateViewLayout` throws otherwise).
`CctvServerService.onMain { ... }` posts a block onto `serviceScope`
(`Dispatchers.Main.immediate`) to enforce that — launched from the main thread it runs
synchronously (no dispatch), launched from a worker thread it posts to the main looper.
Every periodic/delayed loop in the service (the snapshot loop, the timestamp ticker, the
post-stream-start reapply delay) is also a `serviceScope` coroutine, all cancelled
together in `onDestroy`. All state shared between the main thread and NanoHTTPD threads
that isn't routed through `SettingsRepository` (see below) is `@Volatile`.

### Settings: one shared data source

Every setting lives in one place: `SettingsRepository` (`.settings`), an in-process
singleton wrapping a `MutableStateFlow<ServiceSettings>`. `MainActivity`,
`WebServer`'s dashboard (via `SettingUpdateHandler`), and `CctvServerService` all read
and write it directly — safe because the service has no `android:process` of its own, so
everything runs in the same process. `SettingsRepository.update(context) { it.copy(...) }`
persists to `AppPreferences` and publishes the new snapshot in one call.

`CctvServerService.startSettingsEffectsCollector` is the *only* place a settings change
turns into a side effect (restarting the stream, reapplying the flashlight, resizing the
preview, ...): it diffs the old and new `ServiceSettings` snapshot and calls the matching
`SettingEffects` method. Nothing else should pair a settings write with an inline
`apply*`/`restart*` call — add the reaction to the collector instead. See its kdoc for why
value-application effects (safe to call anytime) and state-transition effects (must not
fire on the collector's first observed emission, to avoid a redundant restart right after
a cold start) are deliberately handled with different rules.

Because of this, starting/restarting the service (`MainActivity.ensureServiceStarted`,
`BootReceiver`) sends a **bare** `Intent` with no extras — `CctvServerService.onCreate`
loads current settings itself via `SettingsRepository.ensureLoaded`. When adding a new
setting: add the field to `ServiceSettings`, persist it in
`SettingsRepository.persist`, read/write it from `MainActivity` and
`SettingUpdateHandler`, and (if it should do something) add its diff check to
`startSettingsEffectsCollector` — plus the HTTP API table in `README.md`.

### Security model (read `WebAuth.kt` and the README "Security" section before touching auth)

- Dashboard auth is Basic, on by default, with a `SecureRandom`-generated password shown
  once on first run. `isAuthorized` intentionally fails *open* when auth is enabled but no
  credentials are configured yet, to avoid locking the owner out — `MainActivity` is
  responsible for seeding the generated password before that gap matters.
- `CctvServerService` reads username/password/`webAuthEnabled` **live from
  `SettingsRepository`** in the `WebServer` callbacks rather than from its own cached
  fields — a prior bug (from a cache populated in `onCreate()` before `MainActivity`
  seeded the first-run password) once served the dashboard unauthenticated. Reading
  through the shared repository keeps this safe post-`SettingsRepository`:
  `MainActivity` seeds and writes the generated password into it *before* it ever starts
  the service, and every subsequent read anywhere goes through the same singleton, so
  there is no separate cache left to go stale.
- `WebAuth.isOriginAllowed` blocks cross-origin requests (CSRF from a browser tab open on
  another site) while still allowing non-browser clients (curl, NVRs) that send no
  `Origin` header at all.
- RTSP auth is separate from dashboard auth and off by default.
- Neither server uses TLS; both are LAN-only by design (see README "Security").

### Testability boundary

Logic that can run without Android APIs is deliberately kept that way — `WebAuth`, and
`MainActivity`'s `parseResolution` (covered by `ResolutionParsingTest`) — specifically so
it has JVM unit test coverage (`app/src/test`) rather than requiring a device/emulator.
`app/src/androidTest` is for the few things that genuinely need a device/emulator (a real
socket, a real `SettingsRepository`, etc.) — currently just placeholder boilerplate. When
adding logic, prefer keeping it Android-API-free and under `app/src/test` if at all
possible.

### Known constraints (don't "fix" without reading the comment first)

- `packaging { jniLibs { useLegacyPackaging = true } }` in `app/build.gradle.kts` works
  around third-party native libs not yet 16KB-page-aligned.
- `isMinifyEnabled = false` for release builds is deliberate: RootEncoder resolves classes
  reflectively, and R8 needs a full on-device verification pass first.
- `rootEncoder` and `rtspServer` versions in `libs.versions.toml` must be bumped together
  — `RTSP-Server` pins a `RootEncoder` version transitively.
