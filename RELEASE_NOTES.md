## Notebook v1.40 — the day screen stops closing itself

**Opening a day you had arrived at work on could kill the app instead of drawing it.** Scrolling
the home screen threw `IllegalArgumentException: Key "arrived-work778" was already used`, which
Compose raises rather than draw a list that has handed it the same key twice.

Minute 778 is 16:58 on a journal day, and there were two arrivals at work on it. The recorder
already refuses to file the same arrival twice — it drops duplicates by millisecond — but a
millisecond is not the granularity anything downstream of it uses. The timeline places an arrival
by journal minute and the list keys it by journal minute, so two fixes seconds apart on the edge of
a named zone, which is exactly what a GPS flap looks like, arrive as two separate facts and become
two rows fighting over one key.

The timeline now keeps one arrival per zone per minute. That is the granularity it displays at
anyway: two "Went to work" lines forty seconds apart were never two things that happened, and the
second one carried no information the first did not. Two arrivals at the same minute in *different*
zones are still two rows, because that is a real, if unlikely, pair of facts.

Pinned by tests over the exact minute in the report.

Fixes [light-reports#10] and [light-reports#11] — the home screen closed itself on scroll.
