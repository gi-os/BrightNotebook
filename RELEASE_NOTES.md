## BrightNotebook v1.58 — degrees you can read, and arrows you can see

**The weather line speaks Fahrenheit now, and the day view grew the arrows the LightOS calendar
always had.**

### 74°, not 23°

The calendar's weather has always been said in Celsius, because that is what Open-Meteo delivers
and the number went to the screen the way it arrived. On a phone that lives in New York, "23° to
28°" reads as a cold snap in what was actually a warm week.

There is a setting now — **Settings → Weather → Degrees** — and it ships saying Fahrenheit. The
conversion happens at the moment of saying, not of storing: the cache stays Celsius forever,
exactly as delivered, so flipping the switch re-says every cached day instantly and re-fetches
nothing. A number is data; which units you say it in is presentation, and presentation is the
only thing the switch touches.

Fixes [light-reports#161] — no option for Fahrenheit for temperature metrics on calendar.

### Stepping a day, visibly

The day view has always stepped between days — slide the surface sideways and yesterday arrives.
But a gesture announces itself to nobody: the report asked for "an arrow at the top to toggle
forward or backward," which is a person telling you the only affordance they could find was
missing.

The date in the day view's header now sits between two arrows, the way the LightOS calendar draws
its own. One press steps one day, either direction, with the same haptic click as every other
control. The slide still works and still feels better for covering distance; the arrows are for
knowing the capability exists at all. The bar's corners keep their jobs — back on the left,
TODAY on the right when you have wandered.

Fixes [light-reports#172] — when in daily view, adjust arrow at the top to toggle forward or
backward a day.
