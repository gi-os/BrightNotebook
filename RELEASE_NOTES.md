## BrightNotebook v1.45 — tap where it is, and go

**Entries carry a location now, and it opens directions.** Outlook and Google both put an address
on an event; so does every invite that arrives as an .ics. None of it was being read, so the one
thing you want from a calendar entry on the way out of the door — where — was the thing this app
could not tell you.

- **From the phone's calendars**, `EVENT_LOCATION`, which was free for the asking: `Instances`
  inherits the Events columns and `READ_CALENDAR` was already held.
- **From a feed or a file**, the ICS `LOCATION` property. Its unescaping was already written, and
  an address is the one field in an invite that reliably contains the commas ICS escapes.
- **By hand**, on any entry that is not imported: the action sheet has a Location row next to Time
  and Day. An imported entry's own words belong to the calendar they came from — typing over them
  would last until the next sync and not say so.

**Directions is the first row on the sheet when there is somewhere to go**, and it hands the words
to whatever navigates on this phone:

```
BrightWay   brightway://go?q=…      the one on this phone (needs BrightWay v1.5)
Waze        waze://?q=…&navigate=yes
anything    geo:0,0?q=…             Google Maps, HERE, whatever else is installed
```

Waze is asked for by its own scheme rather than through `geo:`, because `navigate=yes` is the
difference between a map of where you are going and being told to turn left — and traffic is the
reason somebody installed Waze. With more than one app installed a small sheet asks which, in this
app's own language; with exactly one, the tap goes straight there, because a chooser with a single
row is a question with one answer.

**Nothing is parsed.** A location is words: a room name, a postal address, a Teams link, "moms".
Every one of those is the correct answer to "where is this" for somebody, and the only thing
downstream is a maps search, which is built for exactly that.

**A ticket gets it for free.** LightPass already knows which cinema, and a venue is a location.

Also: the location rides along when an entry is mirrored into the phone's calendar, so an event
written here says where it is when it is read on a laptop. Database is version 7 — one nullable
column, migrated in place.

## BrightNotebook v1.44 — what you recorded is on the day

**Recordings from BrightRecorder now appear on the timeline.** A row where the clip started, with
the place you typed on the tape and how long it ran — placed on the same axis as a photograph, a
call, a stay and a song, and sorted in among them by when it happened.

**A row each, not a summary.** Grouping is right for music: "an hour of Talk Talk" is what was true
of an afternoon, and a day of individual tracks would drown everything else on it. It is wrong for
this. You made three recordings today on purpose, and each one is a thing you did rather than a
background the day had.

**Placed by when it started.** A clip that ran past four in the morning still belongs to the day it
began on — a recording is a thing you did at a time, and the time it began is the one you would look
for it under. Both calendar dates are fetched and filtered to the journal window, the same as every
other bridge, because a day here does not start at midnight.

**A day you only recorded on stops looking empty.** The activity line under the planner counts
recordings as evidence now, alongside stays, plays, arrivals and conversations.

The clip's tape and filename are carried on the row even though nothing plays yet: the recorder
serves the audio itself at `content://com.gios.brightrecorder.clips/clip/…`, so playing a recording
from the day it happened is a small step from here rather than a new bridge.

It needs BrightRecorder v1.17 or newer, which is the release that added the provider. Older builds
answer nothing, and a bridge with nothing behind it is silence rather than an error — the same as
every other app in the collection this one reads.

**And a cassette glyph, drawn for this app.** The SDK icon set has no tape and no microphone, and
the alarm glyph already means a reminder here.

## Notebook v1.43 — The first page scan asks for the camera instead of crashing

**Scanning a page for the first time on a fresh install closed the app.** Photographing a page is
Roll's job, and handing it a capture request needs no camera permission of Roll — but Android has
a stricter rule about the app doing the asking: an app that *declares* the camera permission in
its manifest, as this one does for its built-in fallback camera, is not allowed to fire a capture
intent at anyone until that permission has actually been granted. The very first scan is exactly
the moment nothing has asked for it yet, so the launch came back as a security error and took the
app down.

The scan path now checks its own permission before preferring Roll. Without it, the first scan
goes to the in-app camera instead — the screen that asks for the camera permission properly — and
every scan after that reaches Roll the way it always did. Nothing changes on a phone where the
permission was already granted.

Fixes [light-reports#15] — "It closed itself", launching the page scan feature for the first time.
