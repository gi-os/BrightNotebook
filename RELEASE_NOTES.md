## BrightNotebook v1.52 — the bottom of a day stays at the bottom

**Reaching the end of a day bounced it and brought the bars back.** The chrome gets out of the way
as you read down a day, which makes the list taller — and a list already at its last pixel answers a
taller viewport by clamping its own scroll. The offset went *down* with no finger involved, the rule
read that as scrolling up, the bars came back, the list got shorter again, and the day you had just
reached the bottom of jumped. Every time.

The rule now says an up-scroll only counts while the list still has somewhere forward to go. At the
end of the list a drop in offset is the layout moving, not you, so nothing happens and the bottom of
a day stays where you put it. Scroll up a single notch and the bars come straight back, because at
that point the list has room below it again. The whole rule moved out of the screen and into
`ChromeScroll`, where it is nine tests instead of an argument — including the clamp, which is not
reproducible by reading the code and is obvious as a case.

**Tapping a recording opens it in BrightRecorder.** A clip row said you recorded something at 14:32
and how long it ran, and did nothing when pressed — the recording itself lives in the recorder, in
storage nothing else can read. It opens there now, on the right tape, with the head parked at that
clip and the moments list scrolled to it. Nothing plays on arrival; press play there.

**Needs BrightRecorder v1.18.** Older builds have no filter for the link, so the tap opens the
recorder on whatever tape it was last on rather than appearing to do nothing. A phone with no
recorder installed could not have drawn the row in the first place.

**Colour is BrightControl's job now, and it finally works without the adb grant.** This app already holds
the whole panel in colour while it is in front, but only on a phone where *it* was granted
`WRITE_SECURE_SETTINGS`. BrightControl v3.35 ships a colour preset for it, so the notebook
comes up in colour on a phone that has BrightControl set up and nothing granted here. Both writers
want the same thing while it is in front, so nothing flickers, and leaving the app puts the phone
back to grey exactly as before.

## BrightNotebook v1.51 — where you went, and what you read

**Trips from BrightWay.** "Walked to Union Square · 18 min", placed at the minute you set off. Three
different rows, because they are three different facts: **walked to**, **went to** for transit, and
**set off for** when navigation ended before the last step — a trip you abandoned is not a place you
got to, and pretending otherwise is the sort of thing that makes a diary untrustworthy.

**Reading from LightBooks.** A row per sitting: the book, how far it moved, how long it took.
Already coalesced at the other end — the reader writes progress several times a second at RSVP speed
and none of that is a diary — so what arrives is "you read this, from here to here, for this long".
Pages for a comic, words for a book, because a percentage means something different in every book.

Both count as evidence on the planner's activity line, so a day you only went somewhere on stops
looking like an empty square.

**They need the other apps updated**: BrightWay v1.8 and LightBooks v1.16 are the releases that
added the log and the provider. Neither app recorded any of this before — BrightWay knew every trip
and forgot it within the hour, and the shelf knew where you stopped but never when. Older builds
answer nothing, and a bridge with nothing behind it is silence rather than an error.

## BrightNotebook v1.50 — where the time went, app by app

**A screen-time section at the end of every day.** The day's own line of numbers already carried the
three biggest — "38M CHAT" says more about an afternoon than "2h 14m on the phone" does — and three
was all that fit there. This is the rest of the answer, in the place there is room for it: every app
worth a minute, biggest first, with its longest single sitting beside it.

**The longest run is the interesting number, when it is worth showing.** Thirty-four minutes of a
camera app across a day is a walk with a camera; thirty-four minutes in one sitting is something
else, and a total cannot tell those apart. It appears only when one sitting was at least half the
total, because below that it says "you picked it up a few times" — which is what its absence already
says.

Same query as before, so nothing new is asked of the phone: one walk over the usage events already
answered screen time, pickups and where the time went. It needs the usage appop, which the settings
screen carries the adb line for.

## BrightNotebook v1.49 — the day cannot crash on a duplicate row again

**"It closed itself" was two conversations with the same name.** The crash:

```
java.lang.IllegalArgumentException: Key "talked-Giovanni Lupo721" was already used.
```

Two threads named the same, starting in the same journal minute. The bridge dedupes conversations by
*millisecond*, the list keys them by *minute*, and a `LazyColumn` handed the same key twice throws
rather than drawing anything — so the day did not look wrong, it took the app down.

**Fixed where it can actually be fixed: in one place.** The day is assembled from a dozen
independent sources and none of them can see the others, so "keys are unique" is not a property any
one of them can guarantee. The key is now defined once, and the builder drops anything whose key it
has already emitted. The list asks for the same function.

This is the second time this file has had this bug — arrivals were the first, where a GPS flap
inside a named zone produced two fixes a second apart, which is one journal minute. That one was
patched at the source. This one is patched at the shape, which covers the next source too: a
photograph and a stay and a call landing on the same minute all go through the same door now.

Two tests, one for the reported case and one that asserts every key on a built day is distinct.

## BrightNotebook v1.48 — Light's own notes on the day, and all-day scrolls again

**All-day events are back inside the list.** Pinning them above the scroll cost a row of screen on
every day that has none and could not be read past on a day with four. They are still a labelled
ALL DAY section of full rows, still the first thing on the day — just part of it. And a day with a
birthday and nothing else no longer says "nothing on this day yet" over the top of the birthday.

**Notes and voice notes taken in Light's own tools now appear on the day.** LightOS writes them into
`Documents/` — `Notes`, `AudioNotes`, `MessageAudio`, and a `Temp` twin of each while something is
still being written — and there is no provider, no database and no intent to ask for them. So a day
with three voice notes on it looked, from here, like a day nothing happened on.

Settings → **Light's own notes** → point at the Documents folder once. A row each on the day it was
made, with the file's own name; tapping one hands it to whatever plays or opens it.

**A folder grant, not a storage permission.** `Documents/` is not a media directory, so
`READ_MEDIA_*` does not reach it and `MediaStore` has nothing to say about most of what is in there.
The alternative is `MANAGE_EXTERNAL_STORAGE` — every file on the phone, granted from a Settings
screen this phone does not have. The document tree is both the narrow option and the only one that
works: one folder, a grant that survives a reboot, nothing else readable.

**Nothing is copied and nothing is read.** The row says a note happened, at a time, under its own
name. Light's text notes are deliberately not imported: copying them in would make this app a second
owner of notes still being edited somewhere else, and two owners of one note is the bug every bridge
in this collection is written to avoid.

**Times come from the file, not from its name.** Light's filenames carry a stamp, but the format is
Light's to change — and a filename this app misparses is a note filed under the wrong day with
nothing to notice it by. `lastModified` cannot be misread.

## BrightNotebook v1.47 — three things about a day, and the recordings that never arrived

**Recordings really do appear now.** v1.44 added the bridge and never got to use it: the manifest
had no `<queries>` entry for BrightRecorder, so on Android 11+ `contentResolver.query` answered null
however installed the recorder was — and a bridge that is not allowed to see its provider looks
exactly like a day you happened not to record on. The reader was right the whole time; the resolver
was never let near it.

**"Picked up 14 times" is a mention, not an event.** It was a full row with a glyph and a time,
which put it at the same weight as a doctor's appointment — and a day whose loudest line is how
often you looked at your phone is a day this app has misread. It is drawn like the music span now: a
short rule and one quiet line in the margin, no rule under it. Background, which is what it is.

**All-day events are a section at the top, and it stays put.** They were a wrapped strip of small
words under the date. That is the right *place* — a whole-day fact frames the day rather than
happening at a point in it — and the wrong weight: "Alex's birthday" and "Flying to Chicago" are
events, and at superfine size in a flow row they read as tags on the date. Now: a labelled ALL DAY
section, one full row each, above the scroll and outside it. Outside, because a frame that slides
away with the page is not a frame. Holidays keep their glyph, the same one the grid draws in the
corner of the cell.

**And the last row of a list is reachable.** The agenda and the day both ended flush against the
chrome, so the final entry sat behind the bar and the list read as one that would not finish
scrolling. A bar's worth of air under both.

## BrightNotebook v1.46 — the whole event, on a page of its own

**A full event editor, four tabs: WHEN · WHERE · REPEAT · ALERT.** The day's one-line field is
still the fast path and always will be — "9:30 standup" and press go. MORE beside it opens the
page, carrying whatever you had typed as the title, and every entry can be opened there from its
own sheet.

Four tabs rather than one long scroll because the four questions are independent, and eleven rows
in a column means reading all of them to answer one. The strip borrows the bottom bar's treatment,
a tracked label with a rule under the one you are on, because the SDK has no tab component and
inventing a pill for one screen would be inventing a widget vocabulary.

| tab | rows |
|---|---|
| **WHEN** | what it is, which day, starts, ends, runs until — plus send as invite, and delete |
| **WHERE** | the location, and Directions to hand it to whatever navigates |
| **REPEAT** | every day / every Tue / every other Tue / weekdays / monthly / yearly, and until when |
| **ALERT** | none, at the time, 5 through 120 minutes, a day before |

**There is no draft and no save button.** Every row writes through the same view model setter the
day's own sheet uses, so there is one code path per field instead of two, and nothing to lose by
pressing home. An event created for the editor and abandoned is deleted by the Delete row, which is
where anybody would look for it.

**Repeats are written as the calendar's own rule**, inside the subset this app can already expand —
`FREQ`, `INTERVAL`, `BYDAY`, `UNTIL`. The two halves have to agree: a rule written here that cannot
be expanded is a series that shows up once, and one a real calendar rejects is an export that
arrives as a single event. `BYDAY` on the weekly presets is not decoration either — without it,
"weekly" means the weekday `DTSTART` happens to fall on, and moving the event leaves the series
repeating on the old day.

**Changing how often does not silently drop until when.** They are separate rows, so the sheet for
one carries the other's `UNTIL` or `COUNT` across.

## Sending it out: iCal, Google, Outlook

**Send as invite writes a real .ics and hands it to the share sheet.** Google Calendar, Outlook and
Apple all offer to add one the moment they see it, which is how an event made on this phone reaches
any of them — without this app holding an account, a token, or write access to a calendar it could
corrupt. What it costs is that the copy stops being live, and that is the honest trade at this size.

Written to the subset the importer reads back, so nothing round-trips lossily: `UID`, `SUMMARY`,
`DTSTART`/`DTEND`, `LOCATION`, `RRULE`, `EXDATE`, and a `VALARM` for the alert. Two details that
are the usual bugs in this format — `DTEND` on an all-day event is the day *after* the last one, and
times are written floating with no zone, because an entry that says half past two means half past
two after a flight.

**And alerts now arrive from the other direction too.** An invite's own `VALARM` becomes the entry's
reminder, and it wins over the calendar's default lead: "fifteen minutes before" on a meeting is a
fact about that meeting. Absolute triggers, `RELATED=END` and positive offsets are refused rather
than misread — each one would put the alarm somewhere nobody asked for.

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
