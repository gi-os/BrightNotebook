## Things that repeat, and checkboxes you can tick

Two things this app has been missing since the beginning.

**A weekly meeting is now a weekly meeting.** Open any entry and there is a *Repeats* row:
daily, weekly, monthly or yearly, every so many of those, which weekdays for a weekly one, and
an end — never, after a number of times, or on a date. The same thing works the other way
round: importing a `.ics` used to skip `RRULE` on purpose, so a standup exported out of Outlook
arrived exactly once and then never again. It arrives as a standup now.

Both halves are one engine. That was the whole point of doing them together — two separate
half-implementations of recurrence in one app would have been worse than the nothing that was
there before, because they would eventually disagree about which Tuesday something happens on.

A series is **one row in the database**, and the days it falls on are worked out for whatever
window is on screen. A ten-year daily meeting in an imported feed costs one row, not three and
a half thousand, and nothing has to reconcile those rows the next time the feed is re-fetched.
It also means the rule survives a LightSync backup for free: it is two columns on an entry, in
the database that was already being backed up.

Being honest about what it reads: `FREQ`, `INTERVAL`, `BYDAY` — including the "first Monday" and
"last Friday" forms — `BYMONTHDAY`, `COUNT`, `UNTIL` and `EXDATE`. It refuses `BYSETPOS`,
`BYWEEKNO`, `BYYEARDAY`, week starts other than Monday, and the sub-daily frequencies. A refused
rule is not dropped and not guessed at: the event shows once, on the day it really starts, and
the *Repeats* row admits the app cannot read it. That is a much smaller lie than scattering
something across the wrong days.

Monthly on the 31st **skips** February rather than sliding to the 28th. That is what RFC 5545
says and it is also the right answer — a thing that happens on the 31st does not happen in a
month with no 31st. If what you meant was the end of the month, the picker has *LAST* for that.

Deleting or editing one occurrence of a series always asks whether you mean that one or all of
them. Neither answer is assumed: a silent "all of them" throws away a year of standups, and a
silent "just this one" leaves the other fifty-one saying the wrong thing.

**Checkboxes.** Type `- [ ] milk` in a note and it draws a checkbox; tap it and the line becomes
`- [x] milk`. The note is still plain text — that is the point. There is no second, hidden list
model to keep in step with the words, nothing to migrate, and a checklist read anywhere else is
still the five characters you typed. Two identical `- [ ] milk` lines tick independently,
because the rewrite is addressed by line number and not by matching the text. And the checkbox
is its own tap target, so ticking something off does not drop the caret into the note or throw
the keyboard up over half the screen, which on this panel is the difference between a shopping
list and an argument.

One thing changed underneath to make that work: the note itself scrolls now, instead of the text
field scrolling inside it. A text field that scrolls itself will not say how far it has scrolled,
so the checkboxes would have slid off their lines in any note longer than a screen. Keeping the
cursor visible while typing is now done by hand, which it has to be — and the side effect is that
the wheel scrolls a long note, which it could never do before.

Upgrading keeps everything. The two new columns arrive by migration; nothing is rebuilt and
nothing is thrown away.
