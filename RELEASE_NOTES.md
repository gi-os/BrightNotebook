## Notebook v1.43 — The first page scan asks for the camera instead of crashing

**Scanning a page for the first time on a fresh install closed the app.** Photographing a page is
Roll's job, and handing it a capture request needs no camera permission of Roll — but Android has
a stricter rule about the app doing the asking: an app that *declares* the camera permission in
its manifest, as this one does for its built-in fallback camera, is not allowed to fire a capture
intent at anyone until that permission has actually been granted. The very first scan is exactly
the moment nothing has asked for it yet, so the launch came back as a security error and took the
app down.

The scan path now checks its own permission before preferring Roll. Without it, the first scan
goes to the in-app camera instead — the screen that asks for the camera permission properly — and
every scan after that reaches Roll the way it always did. Nothing changes on a phone where the
permission was already granted.

Fixes [light-reports#15] — "It closed itself", launching the page scan feature for the first time.
