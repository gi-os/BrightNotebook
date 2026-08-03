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
