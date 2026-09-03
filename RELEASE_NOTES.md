## BrightNotebook v1.61 — today, served to the news

**The notebook's page for today can be read by another app now.** BrightNews' Daily Briefing
opens with the day — the date, the weather, what is on the calendar — and that is the notebook's
to say. Two new paths on the provider that already serves BrightControl's lock face:

`content://com.gios.lightnotebook.nextup/day` — one row per item on the current journal day
(4 am to 4 am, the same day the planner shows): title, start and end clock minutes, whether it
is all-day, and its kind (event, reminder, ticket, holiday). Entries, imported calendars,
repeating series, LightPass tickets and US holidays, in the order the agenda lists them.

`content://com.gios.lightnotebook.nextup/weather` — at most one row for the same day: the WMO
code and its kind, the high and low, whether it is observed or forecast, and sunrise and sunset
in clock minutes from the home coordinates. Nothing cached yet reads as an empty cursor, never
an error.

Read-only, exported because the caller is another app, and every failure is an empty cursor:
a reader that cannot see the day sees a day with nothing on it.

## BrightNotebook v1.60 — what you watched, from BrightRemote

**An evening in front of the television is part of the day, and the notebook can say so now.**
BrightRemote sits on the Apple TV's now-playing state all evening anyway; it now serves what it
saw — one row per session, with the show, the episode and how long it ran — and the day draws
each one at the minute you pressed play. "Watched Slow Horses · Failure's Contagious · 42 min",
between the dinner entry and the photographs, on the same axis as everything else.

A row per session rather than a summary, deliberately. The grouping treatment is for what ran
alongside the day — music, pickups — and an episode is not a background, it is something you sat
down for, the way a book is. Two episodes back to back are two rows, because that is how you
would tell the evening.

The same bus rules as every other source: ask the provider, take what comes, and every failure
is nothing. A remote that predates the provider — or no remote at all — reads as a day with no
television on it, which costs no message anywhere. Journal days run four to four, so both
calendar dates are fetched and clipped to the window by timestamp; a film that ran past four in
the morning still belongs to the evening it began on. Sessions also feed the planner's activity
line, so an evening you only watched something on still shows as an evening the day can see.

## BrightNotebook v1.59 — your journal, in your hand

**Settings → Backup: save everything as one file, and load one back.** For the person whose
Light desktop tool stopped working and who does not run a home server — which is most people.

LightSync has carried these exact stores onto a home server nightly since it shipped, and that
half stays. This is the other half: the same list of what matters — the notes database, the
calendar subscriptions, day entries, the capture photographs, the charging log, the settings —
zipped into one file through the system file picker. Same store list as LightSync on purpose;
the two halves must never disagree about what "everything" means. The file is a plain zip of
the app's own files, not an invented format: a database is one file, and even with this app
gone the notes inside are a `sqlite3` command away.

The export checkpoints the write-ahead log first, so the database file alone is the whole
database — without that, the newest notes live only in the WAL and the backup silently misses
exactly the writing you did today, which is the worst property a backup can have.

**Loading replaces.** A restore is a restore, not a merge — merging two journals across primary
keys is a promise nobody can keep. What makes replacement safe to offer is the order of
operations: the file is unpacked and validated in staging before a single live file moves (a
truncated zip, someone else's zip, a zip with no database — all refused with nothing changed),
and the data about to be replaced is first written to a safety copy inside the app, so loading
last month's backup over this morning's writing is one more load away from undone. Then
Notebook closes itself — the open database handle and the in-memory settings are both stale at
that point, and a clean start is the only honest one.

Fixes [light-reports#42] — no way to export/import any backup; the Light manager tool is broken.
