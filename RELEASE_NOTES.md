## Notebook v1.28 — reports actually leave the phone

**The reporting key is in the build, so a report now becomes a GitHub issue instead of a queued
file.**

Every report filed before this — the ones that got "this build has no reporting key" — is still on
the phone and goes out on the first launch after this installs, carrying its original timestamp.
Nothing that was reported has been lost.

The key is a fine-grained token that can do exactly one thing: file issues on the private
`gi-os/light-reports` tracker. It ships inside a sideloaded APK from a public repository, so its
scope was tested rather than trusted — filing an issue on `light-reports` works, opening one on
this repo is refused, and writing repository contents anywhere at all is refused. Someone who
unzips the APK gets the ability to write junk into one private tracker.

That constraint is also why the screenshot travels as base64 inside the issue body rather than as
a committed file: attaching one would have needed contents access, which is the permission worth
not having.
