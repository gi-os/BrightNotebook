## Notebook v1.25 — a flick instead of a shake, and the app reports itself

**Reporting a glitch now takes a flick of the wrist, the app volunteers its own failures, and
every release carries notes naming the report it closes.**

`RELEASE_NOTES.md` is the body of the GitHub release. It holds this release and only this one and
is rewritten each time; the running index stays in the README's version table, and the archive is
the list of releases. The line that matters for tracking is the one a fix adds:

    Fixes [light-reports#12] — the day screen scrolled to the wrong hour after a pinch.

So the history answers "what was wrong with the build I was running" and not only "what changed".

**Reporting a glitch** (landed in v1.22, written down here)

Flick the phone and Notebook asks whether you meant to send an error report. Answer
yes and it files an issue against the private `gi-os/light-reports` tracker carrying what went
wrong, the build and firmware it happened on, which screen you were on, how much space and heap
were left, and — only while the row stays ticked — a screenshot of the moment you started
shaking. The same sheet is on the settings screen under **SEND A REPORT**, for when shaking a
phone in public is not appealing.

If the app dies, the next launch offers the stack trace rather than losing it. Saying no throws
it away, so the question does not come back until something goes wrong again.

**The app also reports itself**

Waiting to be shaken only ever collects the failures somebody was annoyed enough to report, which
is a biased sample of exactly the wrong kind — the quiet ones that leave a screen looking ordinary
never arrive at all. So the places that already catch an error and put a sentence on the screen now
also offer to report it: a page Claude could not read comes with the model's own reason attached,
and a sync that reached none of your calendars says so.

The nagging is the part that had to be right. A calendar that has moved fails on every hourly
sync, and an app that asks to report it twelve times before lunch is an app whose reporting gets
switched off — so the same failure asks once an hour at most, never on top of a question already
being asked, and only while you are actually looking at the app.

**A flick, not a rattle**

The first version wanted six reversals past 0.55g, which on the phone turned out to be something
you had to mean, hard, twice, before anything happened — the honest report on it was "I shake it
and nothing happens". It now takes three reversals past 0.38g: a firm turn of the wrist out, back,
and out again.

What keeps that safe is the confirmation sheet. A gesture that asks before doing anything can
afford to fire when it is not sure, because a false positive costs one tap on NO — so it errs
towards firing. It is still a *reversal* count and not a force one, which is what separates it
from a bag or a dropped phone: a walk peaks around 0.3g and never gets looked at, and a drop is
one hard jolt rather than three turns. The arithmetic has no Android imports in it, so the unit
tests hold it to all of that, the new near-threshold flick included.

**And a way to see what it is measuring**

"I shook it and nothing happened" cannot be answered from outside a phone with no logcat attached,
so the settings screen now shows the accelerometer live: current g, the peak of the last two
seconds, turns counted so far, and how many shakes have fired. Shaking while it is on screen opens
the report sheet over the top, which answers the rest of the question.

The accelerometer is registered in `onResume` and dropped in `onPause`. A 50Hz stream is a real
battery cost, and shaking a phone that is showing something else is not a complaint about
Notebook.

**Three things that are easy to get wrong here**

- The screenshot is taken at the moment of the shake, not when the sheet asks for it — by then
  the sheet is what is on the screen. It is a `PixelCopy` of this app's own window, which is why
  no permission is involved.
- Reports queue on disk first and post afterwards, always, not as a fallback for being offline. A
  phone reporting a freeze is by definition one that was just misbehaving, and a report that
  exists only in flight is the one guaranteed to be lost. The queue drains on the next launch.
- The screenshot rides inside the issue body as base64, downscaled to 360px and desaturated.
  Attaching a file would need `contents: write` on a key that ships inside a sideloaded APK
  anyone can unzip; held to `issues: write`, the worst a lifted key can do is write junk into one
  private tracker.

A build with no key still compiles and still collects reports. They wait on the phone until a
build that has one installs over it.
