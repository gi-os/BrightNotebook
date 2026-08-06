package com.gios.lightnotebook.backup

import com.gios.light.common.sync.Contents
import com.gios.light.common.sync.FileStore
import com.gios.light.common.sync.LightSyncBackup

/**
 * What LightSync takes off this phone, and what it deliberately leaves behind.
 *
 * The rule applied throughout: back up what was *written here* and cannot be produced again,
 * skip what is a cache of something a server still holds. A backup that carries a re-fetchable
 * cache is not wrong, it is just slower and larger every night for no gain, and the first time
 * a restore is needed the thing that matters is that the notes are there.
 *
 * Four stores rather than one flat list, because the subsystems have genuinely different
 * answers. Notes, the calendar and day data are three things that happen to share an app, and a
 * single list would force one answer — usually the most cautious one — onto all of them.
 *
 * **Backed up**
 *  - `notebook` — the SQLite file. Folders, notes, the calendar subscription list and day
 *    entries all live in `lightnotebook.db`, so this is one store and not three: a database is
 *    one file and cannot be split per table without exporting rows, which would mean owning a
 *    format forever. Worth noting *why* the subscription list belongs in a backup even though
 *    the events behind it do not: an ICS feed's contents are re-fetchable from its URL, but the
 *    fact that you subscribed to that URL, called it "Work" and left it visible exists nowhere
 *    but this table. Losing it means going and finding every feed address again.
 *
 *    Recurrence needs nothing new here, and that was checked rather than assumed: a repeat rule
 *    and its exceptions are two columns on `day_entries` — the whole series is one row — so they
 *    are inside this same file. A repeating event survives a restore because the rule does, and
 *    the days it lands on are worked out again on the phone it lands on.
 *  - `settings` — the `lightnotebook` preferences: the Anthropic key, the location the day
 *    screen uses, the calendar timezone override, the system-calendar mirror toggle and the
 *    default reminder lead. Small, typed in by hand, and irritating to reconstruct.
 *  - `captures` — the photographs notes and entries were transcribed from. These are kept on
 *    purpose rather than deleted after Claude reads them, so a wrong word can be checked
 *    against the page; they are private files and not MediaStore images, so nothing else on the
 *    phone has a second copy. Rows in the database point at these paths, so restoring the
 *    database without them would leave every transcription unverifiable.
 *  - `day` — step counts (the `lightnotebook_steps` preferences) and the charging log under
 *    `charge/`. Observations of a particular phone on a particular day. No API can return them
 *    and nothing regenerates them; once gone, that history is simply missing.
 *
 * **Left out, on purpose**
 *  - `weather/` — one small file per day, but every one of them came from open-meteo and can be
 *    fetched again from the date and the coordinates, which *are* backed up. The archive worker
 *    already backfills on its own.
 *  - `places/` — the reverse-geocode cache. Same argument: keyed by coordinate, refilled from
 *    Nominatim/Overpass the next time a zone needs a name.
 *  - Holidays — not stored at all. They are computed for the year on demand and injected into
 *    the agenda as ordinary rows, so there is no file to carry and a restore onto a phone in
 *    another country gets that country's holidays, which is the better outcome anyway.
 *  - `reports/` and `last-crash.txt` — the shake-to-report outbox and the last crash. Outbound
 *    and short-lived; restoring a queue of stale reports onto a new phone would file them
 *    against a device that never had the bug.
 *  - Events mirrored into the phone's own calendar — owned by the platform provider, not by
 *    this app, and outside its private data directory entirely.
 */
class Backup : LightSyncBackup() {

    override fun label() = "Notebook"

    override fun stores() = listOf(
        FileStore(
            "notebook",
            Contents(databases = listOf("lightnotebook.db")),
        ),
        FileStore(
            "settings",
            Contents(prefs = listOf("lightnotebook")),
        ),
        FileStore(
            "captures",
            Contents(files = listOf("captures")),
        ),
        FileStore(
            "day",
            Contents(prefs = listOf("lightnotebook_steps"), files = listOf("charge")),
        ),
    )
}
