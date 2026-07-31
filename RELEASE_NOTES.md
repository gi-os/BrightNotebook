## Notebook v1.25 — releases that say what was fixed

**Every release now carries its own notes, and a release that fixes a reported glitch names the
report it closes.**

`RELEASE_NOTES.md` is the body of the GitHub release. It holds this release and only this one and
is rewritten each time; the running index stays in the README's version table, and the archive is
the list of releases. The line that matters for tracking is the one a fix adds:

    Fixes [light-reports#12] — the day screen scrolled to the wrong hour after a pinch.

So the history answers "what was wrong with the build I was running" and not only "what changed".

**Shake the phone to report a glitch** (landed in v1.22, written down here)

Shake it hard, three times, and Notebook asks whether you meant to send an error report. Answer
yes and it files an issue against the private `gi-os/light-reports` tracker carrying what went
wrong, the build and firmware it happened on, which screen you were on, how much space and heap
were left, and — only while the row stays ticked — a screenshot of the moment you started
shaking. The same sheet is on the settings screen under **SEND A REPORT**, for when shaking a
phone in public is not appealing.

If the app dies, the next launch offers the stack trace rather than losing it. Saying no throws
it away, so the question does not come back until something goes wrong again.

**A shake has to be a shake, not a pocket**

A bag, a run and a set of keys all clear any threshold a real shake clears, so force alone cannot
tell them apart. What only a shake does is *reverse*: six alternations of the deviation from
rest, each within 400ms of the last. A dropped phone is one reversal and walking never leaves the
threshold at all. The arithmetic has no Android imports in it, so the unit tests hold it to that —
including the drop, the walk and the slow wave.

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
