# LightNotebook

Notes and a calendar for the Light Phone III, built against the real LightOS design
tokens rather than an approximation of them. Launcher label **Notebook**, package
`com.gios.lightnotebook`. Current release: **v1.1.20**.

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

- **A day reads as a diary once it has happened, and as a calendar until then.** There is no
  diary mode and no calendar mode: there is a *now line*, and everything follows from which
  side of it a thing falls on. A day in the past is entirely behind the line and reads as a
  page of what happened; a day ahead is entirely in front of it and reads as a plan; **today is
  both**, split by a `NOW` rule wherever the clock is. Photographs sit in the column in the
  order they were taken, among the things written that day.

  **Notes you wrote or came back to appear on the day too**, at the time you touched them,
  marked "Wrote this" or "Came back to this" and opening the note on a tap. This needs no bridge
  and no permission — `NoteEntity` already carries `createdAt` and `updatedAt`. One row per note
  per day: a note written *and* returned to on the same day counts as the writing, because a
  second row saying you also edited the note you had just written is noise. Only the last edit of
  a day is knowable, `updatedAt` being one column, which is a limit of the schema rather than a
  choice.

  A reminder is not offered on something that has already happened — it counts back from a
  time, and there is nothing left to count back to — and a new entry on a past day does not
  take the default one. Times stay either way, because "we ate at eight" is a real thing to
  write about a day that has gone.

  Photographs taken within twenty minutes of each other collapse into **one moment**. That is
  not a nicety: a single picture is drawn full width the way a photograph in a diary is, and
  eleven shots of the same thing drawn that way would be eleven screens of scrolling with
  everything written that day pushed out of reach underneath. A burst becomes a row of
  thumbnails instead, which keeps a heavy day bounded. The gap is measured from the previous
  photograph rather than from the run's start, so a long afternoon of steady shooting stays one
  moment instead of breaking into arbitrary twenty-minute blocks.

  **Zoom in one stop and the cells carry the photograph itself** — the day's first, centre-cropped
  behind whatever is written on it and knocked back hard with black, so it reads as texture
  rather than as a picture. The scrim is not a nicety: at full brightness a photograph and white
  text are the same luminance in patches, and on a greyscale panel there is no colour left to
  separate them. Never on today, which is the inverted block — the one cell whose state has to be
  unmistakable. **Days that have gone are struck through** with a single faint diagonal, an X
  reading as cancelled where one stroke reads as spent; suppressed over a photograph, where a
  line across the cell reads as damage to the picture.

  This is the one place the "no bitmaps in the planner" rule bends, and the arithmetic is why:
  zoomed out there are forty-two cells, but once they carry entries there are about a dozen, and
  a dozen small thumbnails decoded **once, off the draw path** and held as `ImageBitmap` cost
  nothing per frame. The draw only ever reads an already-decoded map; a day whose picture has not
  arrived draws no picture, and zooming back out drops them all.

  In the month grid a day with photographs carries a hollow square, next to the filled dot that
  means something is written there. A filled mark for *written*, an outlined one for
  *photographed* — a frame, which is what a picture is, and the two stay legible on a cell three
  millimetres wide in a way that two dots would not.

  There is **no bridge to Roll** doing this, and that is the point. [Roll](https://github.com/gi-os/LightCamera)
  writes every exposure to MediaStore because that is what a camera does, so asking the
  system what was photographed on a day answers with Roll's pictures, the stock camera's, a
  screenshot, and anything else — with no content provider, no `<queries>` entry, and no
  release of the two apps in step. The cheapest integration is the one where neither app
  knows the other exists.

  Long-press a frame to file it against the day as an entry, which is what lets a
  photograph be given a time, a reminder, or moved to another day like anything else here.
  Read-only: filing a photograph stores its uri, and never copies, moves or edits the file.
  Reading the library needs `READ_MEDIA_IMAGES`, asked for from the strip itself rather than
  at first launch, so the reason is on screen when the dialog is.

  The month grid gets a mark and not a thumbnail on purpose. Forty-two cells re-measuring
  text per drag frame is the whole reason the planner is one `Canvas` instead of
  composables, and decoding bitmaps into it would undo that at the first pinch. The grid
  says *whether*; the day screen shows *what*.
- **Pages are photographed by Roll, plainly.** `ADD → Camera` hands the shot to Roll when
  it is installed and falls back to the camera built in here when it isn't. Roll pins its
  capture to 4000x3000 rather than the sensor's full 50MP, which is the difference between a
  shutter that answers and the one-to-three-second lag this phone is known for.

  It is served **with no filter**, and that needed a change on Roll's side: a capture
  request is plain unless the caller passes `EXTRA_ALLOW_FILTER`. Roll's dial might have
  been resting on Game Boy three days ago, a dithered page is illegible to Claude, and the
  failure would have surfaced here as "the notebook can't read my handwriting" with nothing
  on screen to explain it. Attaching a photograph to a note is the case that will opt in.

- **Another app can keep a note here.** `lightnotebook://note/<key>?title=<label>` opens
  the note filed under `<key>`, and makes it if this is the first ask. The key is opaque
  to this app — it only has to find the same row again — and is held in a unique
  column, so two taps at once cannot produce two notes.
  [LightChat](https://github.com/gi-os/LightChat)'s contact page is the caller today: it
  keeps one note per iMessage conversation and asks by the conversation's normalised
  handles, never by a chat guid, so restoring a Mac backup does not strand every note. The
  filter is `DEFAULT` and deliberately not `BROWSABLE`: it creates a database row from an
  unauthenticated intent, and a web page has no business doing that.
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
- The camera is a real capture, not a grab of the preview frame: `camera/PageCamera.kt` is
  LightCamera's engine cut down to this one job — an `ImageCapture` asking for a ~3000px
  long edge, tap-to-focus with the bracket driven by the camera's own `CONTROL_AF_STATE`,
  and flash off/auto/on. The old path grabbed `PreviewView.bitmap`, which is preview
  resolution focused on whatever the camera guessed at, and is why biro came back `[?]`.
  Deliberately not `CAPTURE_MODE_ZERO_SHUTTER_LAG`: this camera accepts it, binds, then
  fails every `takePicture`.
- **The photograph is kept.** It used to be deleted the moment Claude had read it, which
  made a misread word impossible to settle. Any note or event that came from the camera
  can show the page it was read off — from its actions sheet, or the camera icon while
  reviewing what was found. The file is deleted only when nothing points at it any more.
- Parsed events are editable before they land: tap one to fix what it says, which day it
  is on, or what time. Long-press keeps or drops it. A model reading biro gets one of
  those wrong often enough that "drop it and retype it" is the wrong only option.
- A day entry can be moved to another day after the fact, and a note can become an event
  from MORE → *Put it on the calendar*, which asks for a day and a time and leaves the
  note where it is.
- The vision prompt is written for handwriting first: cursive, print and a mix; the
  writer's own abbreviations kept as written; crossed-out words skipped; margin
  insertions placed where the arrow points; and `[?]` only for a word that genuinely
  cannot be read, never a plausible guess. Captures are sent at a higher resolution than
  a printed page would need, because the difference between a 3 and an 8 in biro is a
  few pixels.

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

Tags are stamped by CI (`v<major>.<minor>.<run number>`) on every push to `main`; each
one below is a real tag against the commit shown. A branch that is not `main` runs
`check.yml` instead, which compiles and tests and publishes nothing.

| Version | Commit | Change |
| --- | --- | --- |
| v1.5.0 | this commit | Notes you wrote or came back to are part of the day |
| v1.4.23 | `23310f9` | The planner's cells carry the day's photograph, and days gone are struck through |
| v1.3.22 | `3b164d8` | A day past is a diary, a day ahead is a calendar, today is both |
| v1.2.21 | `069462c` | Photographs on the calendar, and pages photographed by Roll |
| v1.1.20 | `23aff15` | One note per thing another app owns: `lightnotebook://note/<key>` |
| v1.0.17 – v1.0.19 | | Keep the photograph and let a transcription be corrected; LightCamera's capture engine |
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
