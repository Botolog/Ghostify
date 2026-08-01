# Ghostify — MediaSessionService / Background Playback (T-093..T-108)

## Architecture

A single foreground `MediaSessionService` (`PlaybackService`) owns the `ExoPlayer`
and `MediaSession`. All playback state lives in the service — not the Activity —
so it survives Recents swipe (T-096), configuration changes (T-102), and battery
saver (T-106). The UI binds a `MediaController` to the session; the service drives
everything else.

```
PlaybackService (MediaSessionService)
  ├── ExoPlayer          (handleAudioFocus = false; focus owned by the controller)
  ├── MediaSession        ← MediaController (UI, BT, Auto, lockscreen)
  ├── AudioFocusController
  │     └── AndroidAudioFocusDriver  (AudioManager, API 26 AudioFocusRequest)
  ├── PlayerControlAdapter           (PlaybackControl ⇄ ExoPlayer bridge)
  ├── AudioBecomingNoisyReceiver     (BroadcastReceiver → pause)
  └── PlaybackNotificationProvider   (DefaultMediaNotificationProvider + Stop button)
```

### Design decisions

1. **`handleAudioFocus = false` on ExoPlayer.** The service manages audio focus
   explicitly via `AudioFocusController`, which calls back through
   `PlayerControlAdapter`. This avoids the player and the controller "fighting"
   over focus — the only correct way to implement ducking (volume scaling) in
   sync with focus-loss events.

2. **Pure-policy / Android-glue split.** All decision logic (`FocusPolicy`,
   `NoisyPolicy`, `NotificationActionPolicy`, `AudioFocusController`) lives in the
   `core` package with no Android imports, so it is unit-testable on the JVM.
   Only the drivers/receivers/providers are Android-specific.

3. **Stop button via `DefaultMediaNotificationProvider.getMediaButtons`.** Media3's
   default provider already handles foregrounding, channel creation, previous /
   play-pause / next, and auto-dismissal on idle. We subclass it and append a
   `STOP` command button (`Player.COMMAND_STOP`). The resulting idle state is
   observed by `onPlaybackStateChanged`, which calls `stopForeground(REMOVE)` +
   `stopSelf()` — no zombie notification (T-097, T-100).

4. **`onTaskRemoved` overridden to no-op.** The default `MediaSessionService`
   implementation calls `stopSelf()`, killing playback when the user swipes the
   task away. We intentionally suppress this so the foreground notification and
   playback survive (T-096).

5. **Session created lazily in `onGetSession`.** The player + session are built
   on the first controller connection, not `onCreate`, so resources are only
   allocated when actually needed.

## Build & test strategy

The module uses a multi-source-set Gradle build (see `build.gradle.kts`):

| Source set | Source | Purpose |
|---|---|---|
| `main` / `test` | `core/` + JUnit5 | Pure-JVM tests — **run on host** |
| `androidCheck` | full `main/` + `stubs/` | Compiles Android glue against `android.jar` + Media3 AARs (no aapt2) |
| `androidCheckTest` | full `main/` + `stubs/` + device instrumentation tests | **Compile-checked only** — device instrumentation |
| `robolectricTest` | Robolectric tests | **Compile-checked on host** — **runs on CI** (see note below) |

The AARs (Media3 1.5.1, Guava) are unpacked into `build/androidCheck/classes/`
and placed on the `compileOnly` classpath of every Android source set. This
avoids AGP/aapt2 entirely — no `com.android.application` plugin is applied.

### Robolectric on aarch64

Robolectric's native runtime (`nativeruntime-dist-compat`) ships `linux/x86_64`
and `mac/aarch64` but **not** `linux/aarch64`. The Robolectric tests
compile-check successfully on this host, but cannot execute here. They are
configured as a separate `robolectricTest` task that runs on CI (or any host with
the correct native binary). See `build.gradle.kts` for the `robolectricTest`
task definition.

### Test results (host, `gradle test --no-daemon`)

**BUILD SUCCESSFUL** — all 28 pure-JVM tests pass:

| Test class | Tests | Result |
|---|---|---|
| `AudioFocusControllerTest` | 9 | all pass |
| `FocusPolicyTest` + `AudioFocusLossTest` | 11 | all pass |
| `NoisyPolicyTest` | 3 | all pass |
| `NotificationActionPolicyTest` | 5 | all pass |

Total: **28 passed, 0 failed, 0 errors, 0 skipped.**

All Android glue source (`PlaybackService`, `PlayerControlAdapter`,
`AudioBecomingNoisyReceiver`, `AndroidAudioFocusDriver`,
`PlaybackNotificationProvider`) compiles under `androidCheck`. The device
instrumentation test (`PlaybackServiceMediaSessionTest`) and Robolectric test
(`AudioBecomingNoisyReceiverTest`) also compile-check successfully.

## Test coverage map (T-093..T-108)

### Runnable on host (pure JVM / JUnit5)

| ID | Description | Test | Status |
|---|---|---|---|
| — | AudioFocusController lifecycle | `AudioFocusControllerTest` | ✅ runs |
| — | FocusPolicy decisions | `FocusPolicyTest`, `AudioFocusLossTest` | ✅ runs |
| — | Noisy policy | `NoisyPolicyTest` | ✅ runs |
| — | Notification action order | `NotificationActionPolicyTest` | ✅ runs |

### Compile-checked on host (Robolectric — runs on CI)

| ID | Description | Test | Status |
|---|---|---|---|
| T-098 | Becoming-noisy receiver pauses | `AudioBecomingNoisyReceiverTest` | ✅ compiles / 🟡 CI only |

### Compile-checked on host (device instrumentation — runs on device)

| ID | Description | Test | Status |
|---|---|---|---|
| T-103 | Session state matches player | `PlaybackServiceMediaSessionTest` | ✅ compiles / 🔴 device only |

### Manual checklist (device)

The following require a physical device or emulator. Each is verified against
the implementation:

- **T-093** Play → screen off → music keeps playing. The service runs as a
  foreground service (`foregroundServiceType="mediaPlayback"`) with no
  `setWakeMode` needed since the foreground state keeps the process alive.
  Verify: start playback, press power button, confirm audio continues.

- **T-094** Lockscreen shows media notification with title/artist/art. Provided by
  `MediaSession` + `MediaMetadataCompat` (set by the app's UI layer on the session).
  Verify: play, lock device, check lockscreen media card.

- **T-095** Lockscreen controls: play/pause, next, prev, seek work. All transport
  commands flow through `MediaSession` → `ExoPlayer` (T-103 verifies sync).
  Verify: lock screen, press next/prev/seek, confirm player updates.

- **T-096** Swiping away app from recents → music continues.
  `onTaskRemoved` is intentionally a no-op (doesn't call `super` which would
  `stopSelf()`). The foreground notification survives task removal.
  Verify: play, swipe app from Recents, confirm audio + notification persist.

- **T-097** Notification stop button → playback stops, service stops, no zombie
  notification. The Stop `CommandButton` calls `Player.COMMAND_STOP` → idle state →
  `onPlaybackStateChanged(STATE_IDLE)` → `stopForeground(REMOVE)` + `stopSelf()`.
  Verify: play, tap Stop in notification, confirm player stops + service dies.

- **T-098** Headphones unplugged → playback pauses.
  `AudioBecomingNoisyReceiver` listens for `ACTION_AUDIO_BECOMING_NOISY` and calls
  `PlaybackControl.pause()`. (Robolectric test verifies the glue contract.)
  Verify: play on wired headphones, unplug cable, confirm pause.

- **T-099** Phone call / another app requests audio focus → pause or duck; resume
  after. `AudioFocusController` uses `FocusPolicy.decide()`: permanent LOSS → pause
  + abandon; transient LOSS → pause (resume on GAIN); can-duck LOSS → volume duck
  (restore on GAIN). (Policy covered by pure tests.)
  Verify: play, start another app's audio, confirm duck/pause; regain focus,
  confirm resume.

- **T-100** Notification actions (play/pause/next/prev) work from notification.
  Media3's `DefaultMediaNotificationProvider` wires standard actions; Stop is added
  (T-097). All route through `MediaSession`.
  Verify: expand notification, tap play/pause/next/prev, confirm state changes.

- **T-101** Bluetooth headphone media buttons control the app. Media buttons arrive
  via `MediaSession` (which `MediaSessionService` routes through `onGetSession`).
  ExoPlayer handles the `KEYCODE_MEDIA_*` commands natively.
  Verify: play, press BT pause/play/next/prev, confirm player reacts.

- **T-102** Device rotation during playback → state preserved, no restart. The
  player lives in the service; rotation only rebinds the `MediaController` in the
  Activity.
  Verify: play, rotate device, confirm playback position + playing state unchanged.

- **T-104** Volume keys adjust player volume. `setVolumeControlStream(AudioManager.STREAM_MUSIC)`
  on the player Activity routes volume keys to the media stream.
  Verify: play, press volume up/down, confirm ExoPlayer volume changes.

- **T-105** Android Auto / car head unit shows metadata. Requires the app to
  enable `android.media.browse.MediaBrowserServiceCompat` (beyond this component's
  scope); the `MediaSession` provides the playback state the car display reads.
  Verify: connect to Android Auto, browse to app, confirm metadata + controls.

- **T-106** Battery saver on → playback continues. Foreground service with
  `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` is exempt from battery-saver
  throttling.
  Verify: enable battery saver, play, confirm audio continues in background.

- **T-107** Multiple audio apps — focus released/regained correctly. When another
  app takes focus, we pause/duck and abandon; on GAIN we resume/restore.
  Verify: play app A, start app B, confirm A ducks/pauses; stop B, confirm A
  resumes.

- **T-108** Force-stop from settings → no crash on next launch. The service has no
  persistent state between runs; `onDestroy` releases player + session + focus +
  unregisters the noisy receiver cleanly. On next launch, `onGetSession` creates
  fresh instances.
  Verify: play, force-stop from Settings, relaunch, confirm clean start + no crash.

## Files

```
background-playback/
├── build.gradle.kts          # 5 source sets, AAR unpacking, test wiring
├── settings.gradle.kts
├── gradle.properties         # --no-daemon, -Xmx1024m
├── TESTS.md                  # (given)
├── solution.md               # (this file)
├── android/app/src/main/
│   ├── AndroidManifest.xml   # <service> + FOREGROUND_SERVICE_MEDIA_PLAYBACK
│   ├── res/drawable/ic_notification.xml
│   └── java/com/ghostify/background/
│       ├── core/             # pure-JVM policy (no Android deps)
│       │   ├── PlaybackControl.kt
│       │   ├── PlaybackAction.kt
│       │   ├── NotificationActionPolicy.kt
│       │   ├── FocusPolicy.kt
│       │   ├── AudioFocusDriver.kt
│       │   ├── AudioFocusController.kt
│       │   └── NoisyPolicy.kt
│       ├── AudioBecomingNoisyReceiver.kt   # glue: BroadcastReceiver → core
│       ├── AndroidAudioFocusDriver.kt     # glue: AudioManager → core
│       ├── PlayerControlAdapter.kt         # glue: ExoPlayer → core
│       ├── PlaybackNotificationProvider.kt # glue: DefaultMediaNotificationProvider + Stop
│       └── PlaybackService.kt              # foreground MediaSessionService
├── stubs/com/ghostify/R.kt   # compile-time-only R.drawable.ic_notification
└── tests/
    ├── unit/com/ghostify/background/core/         # JUnit5 (runs on host)
    │   ├── AudioFocusControllerTest.kt
    │   ├── FocusPolicyTest.kt
    │   ├── NotificationActionPolicyTest.kt
    │   └── NoisyPolicyTest.kt
    ├── robolectric/com/ghostify/background/      # Robolectric (CI)
    │   └── AudioBecomingNoisyReceiverTest.kt
    └── androidInstrumented/com/ghostify/background/  # Device instrumented (T-103)
        └── PlaybackServiceMediaSessionTest.kt
```
