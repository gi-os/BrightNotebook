## Notebook v1.39 — calls, charging, and where the time went

**Calls are on the day.** "Called Alex, 12 min", "Alex called", "Missed call from Mum" — three
different facts, phrased three different ways, because only one of them is something you did. A
misdial of zero seconds is not a call and does not appear; a missed call of zero seconds is
entirely the point and does.

Nothing is recorded for this: the call log already holds weeks, so a day from last month answers
without the app having been running for it. It needs one grant, and **not** the one you might
expect — the dialler writes the name it resolved into the log at the time of the call, so
`READ_CALL_LOG` alone gives named calls and `READ_CONTACTS` would buy almost nothing in exchange
for read access to every contact you have.

    adb shell pm grant com.gios.lightnotebook android.permission.READ_CALL_LOG

**Charging, which is the closest this phone comes to knowing when you went to bed.** "Charged 7h
30m, until 07:10." A charge that began before the day says so rather than claiming to have started
at 4am, and one still going says "on the charger" and counts to now instead of guessing at
midnight. A cable knocked for two minutes is not a charge and is dropped.

This is the only recorded thing on the day screen, because Android keeps no history of it — but it
is nearly free: `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED` are exempt from the
implicit-broadcast ban, so a manifest receiver appends twenty bytes twice a day and **nothing runs
at all on a day the cable does not move**.

**Where the screen time went.** The day's footer already said "7 PICKED UP · 1H 12M ON"; it now
also says "38M CHAT · 12M PHONO". Same query, more of the answer.

### Less battery than the version before it, not more

Three features went in and the app's idle cost went *down*:

- **The hourly calendar refresh was an alarm and is now work with a network constraint.** The old
  `setAndAllowWhileIdle` woke the device every hour and tried to fetch whether or not there was a
  connection — and whether or not a single calendar had ever been imported. A fresh install woke
  the phone twenty-four times a day to do nothing. WorkManager can express "when there is a
  connection", so the system folds the wakeup in with work it was already doing, the schedule
  survives a reboot with no receiver to re-arm it, and re-scheduling on launch no longer pushes the
  next sync an hour away. Reminders are untouched: they are the one thing here that has to be on
  time, and they stay exact alarms.
- **The day's usage stats are one pass instead of three.** Screen time, pickups and the app
  breakdown were three separate `queryEvents` calls over the same window — three walks over the
  same few thousand events, on the screen that rebuilds every time you swipe to another day.

## Notebook v1.38 — a day in the order it happened

**Entries were being sorted against everything else in the wrong unit.** A day could read
"started at 11:17am, then 8am, then 1pm, then 2pm, then 6pm, then 2:30pm, then 7pm, then 3:30pm" —
every individual time correct, the order nonsense.

Two units had been quietly coexisting. Anything derived from an instant — a photograph, a place,
a pickup, a conversation — is measured in minutes from the journal day's 4am cutover, which is
what the now line, the bookends, the daylight band and the hour labels are all in. A calendar
entry is not derived from an instant at all: it is a time you typed or an importer resolved,
stored as minutes from midnight on purpose, so that no timezone can move an entry out of its
square. Sorted together without converting, every entry landed exactly four hours later than it
belonged, among things that were themselves in perfect order.

`JournalDay.fromClockMinutes` is now the one conversion, applied where the two meet: the
timeline's ordering, the now-line question of whether a thing has happened yet, the gap sizes and
their hour labels, the vertical position of an entry inside a month cell, and the activity line
that spans a day. The displayed time never changed and never was wrong.

Pinned by tests that reconstruct that exact day and assert the order, plus a property test that
the new conversion is the true inverse of the old one across all 1,440 minutes — a disagreement
between those two is a four-hour error that nothing on screen would show.

## Notebook v1.37 — holidays, and a time zone you can see

**The US federal holidays are on the grid, with a glyph each.** A tree on Christmas, an
eight-pointed firework on the 4th of July, a five-pointed star for Juneteenth, a bell for Martin
Luther King Jr. Day, a hard hat for Labor Day. They are worked out on the phone from the rules —
eleven dates a year is arithmetic, not an API call — so they are right offline, in every year,
forever, and there is no cache to go stale.

**Observed dates are their own entry.** When Independence Day falls on a Saturday the fireworks
are on the Saturday and the day off is the Friday. Those are two different facts about your week,
and neither can be inferred from the other, so the grid shows both: "Independence Day" on the 4th,
"Independence Day (observed)" on the 3rd.

The glyphs are drawn for this app in LightOS's own idiom — a 30-unit viewport, one filled path, no
outlines — and chosen to stay apart from each other at about twelve pixels, which is what a month
cell actually gives them. That is why the firework has eight points and the Juneteenth star has
five: at that size, point count is the only difference the eye can still use.

**Settings → CALENDARS → TIME ZONE.** An imported calendar carries instants — `20260804T130000Z` —
and turning one into "9:30, Tuesday" needs a time zone, while everything you type here is already
local and needs none. So a phone that reports the wrong zone shifts every imported meeting by
hours and leaves everything you wrote alone, which looks like the calendar being randomly wrong.
The row now shows the zone the app actually resolved, which makes it a readout as much as a
setting, and it can be overridden when the phone is the thing that is wrong. Changing it re-reads
every subscribed calendar on the spot, because the rows already stored were converted with the old
one.

## Notebook v1.36 — a work calendar can live here now

**Settings → CALENDARS → Subscribe to a URL.** Point it at a published `.ics` feed and the
calendar arrives on the grid alongside everything you typed, refreshed by the same hourly pass
that refreshes an imported file. `webcal://` links work too — they are the same feed under a
scheme no HTTP client accepts, so they get rewritten rather than refused.

**Scan the address, don't type it.** A feed URL is a hundred-odd characters with a random secret
in the middle, which is not a thing to enter with a thumb on a 3.92" screen. The QR field on
[gi-os.github.io/LightNotebook](https://gi-os.github.io/LightNotebook/) turns one into a code in
your browser, and the scanner is the same in-app one the API key uses. Typing it is still there
for a short address on your own network.

**Why this is the answer for a work calendar.** Microsoft 365 and Google both want an OAuth client
the Light Phone III cannot run — no Play Services, and no browser that satisfies the OAuth
libraries. So the account lives somewhere else: LightSync's new `calendar_bridge.py` holds the
Microsoft credential on a server and publishes one file the phone simply fetches. Because it reads
Graph's `calendarView` rather than an export, **recurring meetings arrive as individual
instances** — a weekly standup shows up every week, which importing a raw `.ics` cannot do, since
the parser here deliberately does not expand `RRULE`.

The first fetch happens the moment you add it, so a wrong address says so immediately instead of
failing quietly overnight. A feed that cannot be reached later leaves the events already on the
grid alone.

## Notebook v1.35 — the report offer gets out of the way

**A small SEND ERROR? chip in the corner for four seconds, instead of a sheet across the screen.**

The gesture that raises it is one the phone can misread, which means the cost of being wrong is
paid every single time it is wrong. A modal sheet covering what you were reading, on a 3.92"
screen, to ask about something you did not ask about, is a bad trade against a report that might
not have existed. So the offer is small, it sits above the bottom bar out of the way, and it fades
after four seconds — eight for a crash, which is worth a longer look.

**Silence is an answer, and it is the safe one.** Letting the chip fade deletes nothing: the same
report is always available from SEND A REPORT on the settings screen, and an unsent crash log is
offered again on the next launch. Only opening the sheet and cancelling throws a crash log away,
because that is a decision rather than an absence of one.

The sheet itself lost its first step along with all this. It used to open by asking whether you
meant to send a report at all; the chip is that question now, so by the time the sheet is up the
answer is already yes and it can begin with the part that carries information.
