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
