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
