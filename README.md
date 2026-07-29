# LightNotebook

A notes app and calendar for the **Light Phone III**, built in the LightOS design
language. Launcher label: **Notebook**.

Three buttons at the bottom, and that is the whole app: a list, a plus, a calendar. The
one you are on is lit and underscored; the others recede.

```
   ≡            +            ▤
  ───
```

## Notes

- Folders, and pinning to the top of the list. Long-press any note for pin / move /
  delete; long-press the folder chip you are in for rename / delete.
- **Bold**, bullets and numbered lists, from three keys — `B`, `•`, `1.` — which appear on
  the action bar only while text is selected, since that is the only time they apply.
- Press return on a list line and the marker carries down. Press return on an empty one
  and the list ends. No menus.
- Search across titles and bodies.
- Notes are stored as plain text with `**bold**`, `- ` and `1. ` markers, so a note is
  still readable anywhere it ends up. The editor styles them live without hiding them,
  which is why the cursor never lands in the wrong place.
- Open a note and it is already editable. Leave one untouched and it deletes itself
  instead of leaving an "Untitled" behind.

## Calendar

- A month at a time. Today is inverted, a day with anything on it carries a dot, and
  nothing else is marked.
- Tap a day and type. `9:30 dentist` files itself at half past nine; `dentist` is an
  all-day line. That is the entire time picker — and any row's time can be changed later
  from its own sheet.
- **NEXT UP** is a button, not a strip. It opens the agenda on its own screen: a day at a
  time, in order, times and labels legible, films included.
- Entries are also written to the phone's own calendar when there is a writable one
  (Settings → PHONE CALENDAR). The notebook is the source of truth; the mirror is a
  bonus, and deleting an entry removes both.

## Reminders

Anything with a time gets one. The lead time is Settings → REMIND ME (default ten minutes
before, or never), and any single entry can be changed from its row.

When one comes due you get three things: a notification, a buzz, and a box that lights the
panel. The alarm is `setExactAndAllowWhileIdle`, which is the only kind that fires on time
through Doze — `setAndAllowWhileIdle` is throttled to roughly once every nine minutes,
which for "tell me at 08:50" is the same as not firing. Alarms don't survive a reboot and a
force-stop clears them, so everything is re-armed at boot and again at every launch.

The box needs one adb grant, because LightOS has no settings screen for it and Android 14
uses that appop to decide whether a broadcast may start an activity:

```
adb shell appops set com.gios.lightnotebook SYSTEM_ALERT_WINDOW allow
```

Without it the notification and the buzz still arrive; only the panel stays dark. The
notifier, the wake-the-screen activity and the buzz pattern are ported from
[LightChat](https://github.com/gi-os/LightChat).

## Calendars and importing

Settings → CALENDARS. Each one carries a label, can be hidden from the grid, and can be
removed with its events.

- **Import a .ics file** — an export or an invite, from anywhere. The parser reads
  `VEVENT` blocks and unfolds long lines properly; `TZID` and UTC stamps are converted to
  the phone's own day, so a 01:30 UTC call doesn't land on tomorrow. Recurrence is
  deliberately not expanded: a weekly meeting arrives as its first occurrence rather than
  as a series this parser would get subtly wrong.
- **Import from this phone** — whatever LightOS already syncs, read through
  `CalendarContract.Instances`, so the provider's own recurrence expansion is used instead.

Imports are snapshots. Re-importing the same source **replaces** its events, so something
moved at the source moves here rather than appearing twice.

## Films from LightPass

If [Movie Tickets](https://github.com/gi-os/LightPass) is installed, its tickets show up on
the day they screen — a dot on the grid, a ticket-stub row on the day, a line in NEXT UP —
and tapping one opens the stub in LightPass, where the barcode is.

Nothing is copied. LightNotebook reads LightPass's content provider (title, cinema, seat,
day, start and end), so a ticket deleted or re-dated over there is right over here on the
next look, and the film rows are not editable in the notebook. If LightPass isn't
installed, or is an older build with no provider, the calendar is simply films-free — no
message, no setup step.

## Camera

ADD → Camera photographs a page and Claude Haiku reads it. One request does the
classification and the extraction:

- **A page of writing** → transcribed, with bullets, numbering and emphasis preserved.
  Then it goes into a new note, or onto the end of any note you pick.
- **A calendar, planner or list of dates** → parsed into events, each with a date and an
  optional time. Everything is kept by default; tap a line to drop it. Confirmed events
  land on the calendar, and in the phone's calendar too.

Photographing from the CALENDAR tab tells the model to expect dates. From NOTES it
decides for itself.

The key is yours and stays on the phone. Settings → paste it, or **SCAN QR**: put your key
into <https://gi-os.github.io/LightNotebook/> (client-side, the key never leaves the page)
and point the phone at the code rather than typing a hundred characters on a phone
keyboard.

The scanner is the one from [gi-os/LightQR](https://github.com/gi-os/LightQR) — a CameraX
analyzer decoding the luminance plane with ZXing core, wrapped in a Light-styled screen
with a reticle and nothing else. No Play Services, and no borrowed Material activity
flashing up mid-flow. A code that isn't shaped like a key is refused with a line of text
and the camera keeps scanning, so a poster in frame can't be saved as your API key.

Typing and the calendar work with no key at all — only the camera needs one, at a fraction
of a cent a page.

## Design

The look is ported from Light's own SDK rather than approximated:

- **A 27 × 31 grid.** Bar heights, insets and icon sizes are fractions of the screen, not
  fixed dp.
- **LightOS's named type scale** (`title` … `micro`), scaled against a 600px baseline, set
  in Akkurat pulled out of `SystemFonts` so the app matches the system chrome.
- **Three colours**: background, content, secondary. State is carried by inversion and by
  brackets around the active label, because a tint does not read on a greyscale matte
  panel.
- **No ripples.** Taps buzz on finger-down, 45ms, tuned for the LP3's slow motor.
- LightOS's own icons, from the SDK.

`app/src/main/kotlin/.../ui/theme/` holds the ported design system —
`Theme.kt` (grid, tokens, haptics), `LightText.kt`, `LightIcons.kt`, `LightBars.kt`.
Vector drawables in `res/drawable/ic_*` and the design tokens come from
[lightphone/light-sdk](https://github.com/lightphone/light-sdk), MIT licensed; see
`LICENSE-light-sdk`.

This is a **plain sideloaded APK, not an SDK tool** — the SDK's dependency allowlist has
no CameraX, so nothing that photographs a page can be built against it.

## Install

Every push to `main` publishes a signed release APK. Point Obtainium at this repo, or:

```
adb install -r LightNotebook-v1.0.<run>.apk
```

The keystore is committed at `keystore/lightnotebook.jks` on purpose: one stable key
means every build upgrades in place instead of failing with Obtainium's opaque
`Failure: Invalid`. CI pins the certificate SHA-256 in `signing-fingerprint.txt` and
fails if it ever drifts.

## Build

```
./gradlew :app:assembleRelease        # APK
./gradlew :app:testDebugUnitTest      # markdown + date logic
python3 scripts/generate_icon.py      # launcher icon, needs Pillow
```

The markdown, date, QR-payload, iCalendar and reminder-timing code is deliberately free of
Android imports, so all of it is unit tested off-device — 58 tests covering list
round-trips, return-key behaviour, month grids, clock parsing, which scanned payloads count
as a key, timezone handling and line folding in .ics files, and when an alarm should fire.

The database migrates rather than resets: version 2 added calendars, reminders and import
provenance as a real `Migration`, because `fallbackToDestructiveMigration` is only harmless
until somebody's notes are in there.

Requires the camera, and calendar read/write only if mirroring is on. Everything else is
local: Room on the phone, no account, no sync.
