package com.gios.lightnotebook.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Taking a calendar entry's location and getting somebody walking.
 *
 * "Tap the address to start navigating" is a thing every other calendar app on every other phone
 * does, and on this one there was nothing to tap — so the location was a line of text you read and
 * then typed into something else. This is the tap.
 *
 * ### What it hands over, and to whom
 *
 * A location is **words**, not coordinates: `EVENT_LOCATION` and an ICS `LOCATION` are free text —
 * a room name, a postal address, a Teams link, "moms". So every candidate here takes a search
 * string, and nothing tries to geocode anything first. This app has no map, no key and no business
 * guessing.
 *
 * Three candidates, in the order they are offered:
 *
 *  - **BrightWay** (`brightway://go?q=`), because it is the one on this phone.
 *  - **Waze** (`waze://?q=&navigate=yes`), asked for by name. Its own scheme rather than `geo:`,
 *    because `navigate=yes` is what starts the route instead of dropping you on a map — and
 *    traffic is the reason somebody installs Waze in the first place.
 *  - **Anything else that handles `geo:0,0?q=`**, which is the standard "here are words, show me a
 *    map" request that Google Maps and HERE both answer. `0,0` is the convention for "no
 *    coordinates".
 *
 * ### Why a list rather than one intent
 *
 * Because the phone answers `geo:` with a chooser when more than one app can, and a chooser is the
 * wrong shape here: the whole gesture is meant to be one press, and the *same* app every time —
 * which app is a preference, not a question. So this reports what is installed and the caller
 * shows a sheet, once, in this app's own language. See `Prefs.navigateWith` for the remembering.
 */
object Directions {

    /** One place worth sending somebody. */
    data class Target(val id: String, val label: String, val intent: Intent)

    /**
     * Every navigation app on the phone that can take these words, most preferred first.
     *
     * Resolved rather than assumed: a candidate whose app is not installed is left out entirely,
     * so an empty list means the phone genuinely cannot do this and the caller can say so instead
     * of firing an intent into nothing.
     */
    fun targetsFor(context: Context, location: String): List<Target> {
        val words = location.trim()
        if (words.isBlank()) return emptyList()
        val encoded = Uri.encode(words)
        val candidates = listOf(
            Target(
                id = BRIGHTWAY,
                label = "BrightWay",
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("brightway://go?q=$encoded"))
                    .setPackage(BRIGHTWAY),
            ),
            Target(
                id = WAZE,
                label = "Waze",
                // `navigate=yes` is the difference between a map of where you are going and being
                // told to turn left, which is the entire reason somebody chose Waze.
                intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("waze://?q=$encoded&navigate=yes"),
                ).setPackage(WAZE),
            ),
        ) + geoTargets(context, encoded)
        return candidates.filter { resolves(context, it.intent) }.distinctBy { it.id }
    }

    /**
     * Whatever else on the phone handles `geo:`, named by its own app label.
     *
     * Queried rather than listed, because the point of the standard scheme is that it works for
     * apps nobody here has heard of. Each one is pinned to its package before it is offered — an
     * unpinned `geo:` intent is what raises the system chooser, and the sheet in front of the user
     * is already that choice.
     */
    private fun geoTargets(context: Context, encoded: String): List<Target> = runCatching {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        context.packageManager
            .queryIntentActivities(geo, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != BRIGHTWAY && it != WAZE }
            .map { pkg ->
                Target(
                    id = pkg,
                    label = label(context, pkg),
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
                        .setPackage(pkg),
                )
            }
    }.getOrDefault(emptyList())

    /**
     * Start one. False when the app it names has gone since the list was built, which is the only
     * failure worth reporting — everything else is the other app's business once it is open.
     */
    fun go(context: Context, target: Target): Boolean = runCatching {
        context.startActivity(target.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun resolves(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    private fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    const val BRIGHTWAY = "com.gios.brightway"
    const val WAZE = "com.waze"
}
