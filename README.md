# LightNotebook

Notes and a calendar for the Light Phone III, built against the real LightOS design
tokens rather than an approximation of them. Launcher label **Notebook**, package
`com.gios.lightnotebook`. Current release: **v1.0.16**.

Three buttons at the bottom and nothing else: a list, a plus, a calendar. Notes are
plain text carrying their own markers (`**bold**`, `- `, `1. `) so a note stays readable
anywhere it ends up. The calendar is a single zoomable wall planner — weeks running
downward, epoch-day entries, pinch to zoom between Month/Week/Day — rather than a page
of months. A camera button feeds a photographed page or planner to Claude Haiku, which
either transcribes it into a note or extracts it into calendar events. If
[LightPass](https://github.com/gi-os/LightPass) is installed, its ticket stubs show up
on the day they screen.

This is a **plain sideloaded APK, not a Light SDK tool** — the SDK's dependency
allowlist has no CameraX, so nothing that photographs a page can be built against it.
See [gi-os/LightPass](https://github.com/gi-os/LightPass) for the shared skeleton this
repo (and every other camera-carrying Light* app) is built from.

## Quick start

```sh
git clone https://github.com/gi-os/LightNotebook.git
cd LightNotebook
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

You need JDK 17 and the Android SDK (`compileSdk` 35, `minSdk` 29). The keystore is
committed at `keystore/lightnotebook.jks` on purpose — see
[Signing](#signing-and-releases) below — so a release build made from a fresh checkout
installs over an existing one instead of failing with Obtainium's `Failure: Invalid`.

To pick up notes, calendar entries and Claude vision parsing with no further setup, that
first install is already the whole app. The camera page needs an Anthropic key (see
[Configuration](#configuration)); everything else — notes, the wall planner, reminders,
importing other calendars — works with none.

## Configuration

- **Anthropic API key.** Settings → paste it, or **SCAN QR**: put your key into
  <https://gi-os.github.io/LightNotebook/> (client-side, generated in the browser, the
  key never leaves the page) and point the camera at the resulting code. The scanner is
  [gi-os/LightQR](https://github.com/gi-os/LightQR)'s CameraX analyzer, wrapped in a
  Light-styled screen. Typing and the calendar work with no key at all; only the camera
  page needs one, at roughly a fraction of a cent per photograph.
- **Reminders.** Settings → REMIND ME sets the default lead time (ten minutes, or
  never); any entry's own row can override it. Reminders are
  `setExactAndAllowWhileIdle` (the throttled `setAndAllowWhileIdle` fires roughly once
  every nine minutes under Doze, which is useless for a fixed time), re-armed at boot
  and at every launch since alarms don't survive a reboot or a force-stop. The
  notification box that lights the panel needs one manual grant, because Android 14
  gates it behind an appop LightOS has no settings screen for:

  ```sh
  adb shell appops set com.gios.lightnotebook SYSTEM_ALERT_WINDOW allow
  ```

  Without it the notification and the buzz still arrive; only the panel stays dark.
- **Importing calendars.** Settings → CALENDARS. Import a `.ics` file (parsed directly,
  recurrence deliberately not expanded — a weekly meeting arrives as its first
  occurrence) or import from the phone's own calendar provider via
  `CalendarContract.Instances` (recurrence expansion comes from the provider itself).
  Each import is a labelled, hideable, removable source; re-importing the same source
  **replaces** its events rather than duplicating them.
- **Mirroring to the phone's calendar.** Entries are written into a writable
  `CalendarContract` calendar when one exists; the notebook stays the source of truth,
  so deleting an entry removes both copies. The LPIII has no Play Services, so this is
  silently a no-op if there is nothing to write to.
- **LightControl** (optional, separate app) rebinds the wheel click and camera button
  phone-wide — brightness, flashlight, camera — and passes bare wheel turns straight
  through to `com.gios.*` so LightNotebook keeps handling its own scrolling and planner
  panning:

  ```sh
  adb install -r LightControl-v1.0.x.apk
  adb shell settings put secure enabled_accessibility_services \
    com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
  adb shell settings put secure accessibility_enabled 1
  adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
  adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
  ```

## Usage notes worth knowing

- The wheel scrolls whatever is on screen — notes, a day, the agenda, settings, the
  list of calendars, a photographed page's transcription — and pans the wall planner
  when nothing is being pinched. It works with nothing else installed: LightOS relabels
  the optical sensor's scancodes `WHEEL_CCW`/`WHEEL_CW` in a patched
  `Generic.kl`, and the app claims them in `dispatchKeyEvent`, ahead of the view
  hierarchy, so a turn still pans the day while a text field has focus.
- The calendar's Month/Week/Day zoom stops are snapped to, not free scroll; the open day
  is composed into the exact cell you pinched, so pinching out returns to that same
  square instead of navigating to a separate screen.
- Typing `9:30 dentist` sets a time; a bare leading number is deliberately not a time
  (`3 loads of laundry` is not an appointment at three).
- Photographing from the CALENDAR tab tells the model to expect dates; from NOTES it
  decides for itself between transcription and event extraction.

## Build and test

```sh
./gradlew :app:assembleRelease        # signed release APK
./gradlew :app:testDebugUnitTest      # markdown, date, planner-geometry logic
python3 scripts/generate_icon.py      # regenerate the launcher icon (needs Pillow)
```

97 unit tests cover the markdown/list round-trip, date and reminder-timing parsing,
QR-payload validation, `.ics` folding and timezone handling, and the planner's zoom
arithmetic — all of it in code deliberately free of Android imports
(`util/NoteMarkdown.kt`, `util/NoteDates.kt`, `util/CanvasMath.kt`) so it runs off-device.
CI runs this suite before assembling, which is also what exercises Room's KSP codegen.

The Room schema migrates rather than resets — version 2 added calendars, reminders and
import provenance as a real `Migration` — because `fallbackToDestructiveMigration` is
only harmless until somebody's notes are in the database.

## Signing and releases

Every push to `main` runs `:app:testDebugUnitTest`, assembles, verifies the signing
certificate against `signing-fingerprint.txt`, verifies a launcher icon is present, and
publishes a signed GitHub Release — **a push is a release trigger, not a cosmetic
action.** `versionCode`/`versionName` are not fixed in the committed
`app/build.gradle.kts` (which carries a `1.0.0` placeholder); CI stamps
`versionCode = <run number>` and `versionName = 1.0.<run number>` on every run, which is
why release tags are `v1.0.<n>` in strict sequence. Point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repo, or install by hand:

```sh
adb install -r LightNotebook-v1.0.<run>.apk
```

## Contributing

Issues and pull requests welcome. Keep new logic that doesn't need `android.*` (parsing,
date math, geometry) in the Android-free `util/` files so it stays unit-testable
off-device, and add tests alongside it. Don't reintroduce
`fallbackToDestructiveMigration` on the Room schema. If you touch the calendar's
gesture handling, note that the pan/zoom loop is hand-rolled (not
`detectTransformGestures`) specifically because that API has no end-of-gesture hook and
the snap-to-stop behavior needs one.

## Version history

Tags are stamped by CI (`v1.0.<run number>`) on every push to `main`; each one below is
a real tag against the commit shown.

| Version | Commit | Change |
| --- | --- | --- |
| v1.0.16 | `dd4a3c2` | Separate what the notebook does with the wheel from what LightControl does |
| v1.0.15 | `74028be` | Weekday letters live on the surface, and detail arrives earlier |
| v1.0.14 | `398578f` | Drop the NEXT UP footer, and fix the bars vanishing after a pinch out |
| v1.0.13 | `e601d6d` | Dim the neighbouring months by half |
| v1.0.12 | `40897d5` | Scroll with the wheel |
| v1.0.11 | `8cd4273` | Sliding between days moves the planner, not just the contents |
| v1.0.10 | `0923bf4` | Opening a day no longer throws the planner back out to the month |
| v1.0.9  | `525ead5` | The cell becomes the day: no second screen, sliding between days works |
| v1.0.8  | `ad2a13c` | Make the zoom carry through, float the bars, fix back landing on Notes |
| v1.0.7  | `b8641bd` | The calendar is a zoomable wall planner |
| v1.0.6  | `022c641` | Fix the agenda crash, fold tickets into their calendar entries, sync hourly |
| v1.0.5  | `db438ae` | Times with reminders, labelled calendars you can import into, agenda screen |
| v1.0.4  | `d4f52d6` | Show LightPass films on the calendar, open the stub when tapped |
| v1.0.3  | `6a1ca4d` | Icons on the bottom bar, and highlight the day you are looking at |
| v1.0.2  | `253b8f7` | Scan the API key in-app, with LightQR's scanner |
| v1.0.1  | `6106690` | Initial release: notes, folders and a calendar for the Light Phone III |

## License

MIT. Vector drawables and design tokens in `ui/theme/` are ported from
[lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT); see
`LICENSE-light-sdk`.
