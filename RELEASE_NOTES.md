## LightNotebook v1.40 — The wheel moved out

### The wheel is a library now

`LightKeys.kt` and `Wheel.kt` are gone from this app. They are `com.gios:light-common:1.1.0`,
the same code every other Light app was keeping its own copy of — the shared core was already
identical everywhere, so this deletes duplication rather than changing behaviour.

The library version is a genuine superset: pressed turns, `WheelTurns`, `reverse` on
`WheelScroll` and `WheelGate` were all in one app or another and missing from the library until
now. Anything this app's copy could do, the shared one can.

Nothing about this is visible on the phone. It matters because a fix to the wheel used to mean
editing it in twelve places, so it got made once and the other eleven drifted.
