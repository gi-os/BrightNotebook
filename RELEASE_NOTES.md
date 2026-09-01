## BrightNotebook v1.57 — what the money did, and one row for the lock face

**The journal now knows what the day cost.** If BrightLedger (v1.5.6+) is installed, each
transaction appears on the day timeline at the minute the bank says it happened — merchant
verbatim, amount in cents-arithmetic dollars, "pending" while the bank is still deciding — and
the foot of the day carries one line: `SPENT $34.20 · 3`, with `$12.00 BACK` when something was
refunded and `AS OF MON 14:05` whenever the ledger's last sync doesn't cover the whole day shown.
Nothing is copied: the day asks BrightLedger's read-only `days` provider on arrival, both
calendar dates a 4am-to-4am journal day touches, and clips the rows to the real window — so the
half-past-midnight taxi lands on the night it belongs to. No BrightLedger, or nothing bought:
no section at all.

**Expected bills sit on the calendar the way tickets do.** Merged at read time from
`bills/upcoming`, never written into the database, never given a reminder. On the month grid a
bill day carries a small **hollow circle** — the written dot's shape, not yet filled in, because
the charge hasn't happened — alongside the filled dot (something written) and the hollow square
(something photographed). On the day and the agenda it is a plain row, `NETFLIX · $15.49
expected`, deliberately *not* the inverted white of a calendar entry: a bill is not somewhere
you have to be.

**One row for BrightControl's lock face.** A new exported read-only provider,
`content://com.gios.lightnotebook.nextup/next`, answers with at most one row — `startAt` (epoch
ms), `title`, `kind` (`event` | `reminder` | `ticket`), `allDay` (0/1) — the single next thing
inside 48 hours, from the same sources the NEXT UP screen merges: entries, imported calendars
with their series expanded, and LightPass tickets. A timed thing always beats an all-day one;
nothing coming up, and every failure, is an empty cursor. Writes to the calendar nudge
`notifyChange` on that URI, best-effort.

The windowing, money formatting and next-up choosing are Android-free in `util/Ledger.kt` and
`util/NextUp.kt`, with tests across the 4am boundary and both DST days.
