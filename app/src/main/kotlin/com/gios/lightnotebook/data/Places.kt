package com.gios.lightnotebook.data

import android.content.Context
import com.gios.lightnotebook.util.PlaceKeys
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Turning a coordinate into "Fasan Cafe".
 *
 * The one thing in this journal that genuinely cannot be done on the phone. There is no offline
 * gazetteer on a Light Phone, `Geocoder` needs a backend that a device without Play Services almost
 * certainly lacks, and a latitude means nothing to anyone reading a diary. So it is a lookup — and
 * like the weather, **never from a screen**: the nightly job fills this in and a day only ever reads
 * what is already on disk.
 *
 * Two sources, in order, because they answer different questions:
 *
 * 1. **Overpass** — what is *here*? A named thing within fifty metres. This is the one that produces
 *    "Fasan Cafe", and it is asked first because the name of a place beats its address every time.
 * 2. **Nominatim** — where is here? A street address, for a coordinate with no named thing near it.
 *    Better than nothing and better than a number.
 *
 * Cached by rounded coordinate ([PlaceKeys]), which is what makes it affordable: you go to the same
 * few places constantly, so after a week almost every stay is a cache hit and the network is barely
 * touched. A miss is cached too — a lay-by on a motorway has no name and asking again every night
 * would be the most expensive lookup in the app.
 */
class Places(private val context: Context) {

    /** A name already known for a spot, or null. No network, ever. */
    fun cached(latitude: Double, longitude: Double): String? {
        val file = fileFor(PlaceKeys.of(latitude, longitude))
        if (!file.isFile) return null
        val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
        // An empty file is a remembered miss: somewhere with no name. Distinct from no file at all,
        // which means nobody has looked yet.
        return text.takeIf { it.isNotEmpty() }
    }

    /** Whether this spot has been looked at, named or not. */
    fun known(latitude: Double, longitude: Double): Boolean =
        fileFor(PlaceKeys.of(latitude, longitude)).isFile

    /**
     * Look up anything not yet known. Called by the nightly job and nothing else.
     *
     * Sequential with a pause between, because both services are free and both ask for that. Stops
     * at [maxLookups] a run: a first night with months of stays should trickle rather than hammer,
     * and tomorrow's run picks up where this one left off.
     */
    fun fill(spots: List<Pair<Double, Double>>, maxLookups: Int = MAX_PER_RUN): Int {
        var found = 0
        var used = 0
        for ((latitude, longitude) in spots.distinctBy { PlaceKeys.of(it.first, it.second) }) {
            if (used >= maxLookups) break
            if (known(latitude, longitude)) continue
            used++
            val name = overpass(latitude, longitude) ?: nominatim(latitude, longitude)
            // Written either way. A remembered miss costs one byte and saves a request every night
            // for the rest of the phone's life.
            write(PlaceKeys.of(latitude, longitude), name.orEmpty())
            if (name != null) found++
            Thread.sleep(POLITE_PAUSE_MS)
        }
        return found
    }

    /**
     * The nearest named thing, from OpenStreetMap.
     *
     * `nwr` rather than `node`: a cafe is as often a building outline or a relation as it is a point,
     * and asking only for nodes misses most of the interesting answers. Sorted by nothing — Overpass
     * has no "nearest" — so the first named element within the radius is taken, which at fifty metres
     * is nearly always the right one.
     */
    private fun overpass(latitude: Double, longitude: Double): String? {
        val query = """[out:json][timeout:$TIMEOUT_S];
            nwr(around:$RADIUS_M,$latitude,$longitude)["name"];
            out tags 8;""".trimIndent()
        val body = post(OVERPASS, "data=" + URLEncoder.encode(query, "UTF-8")) ?: return null
        return runCatching {
            val elements = JSONObject(body).optJSONArray("elements") ?: return null
            for (i in 0 until elements.length()) {
                val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                val name = tags.optString("name").takeIf { it.isNotBlank() } ?: continue
                // A named road is not a place you went — it is the road you were standing on, and
                // "Kent Avenue" as the name of a stay is worse than the address.
                if (tags.has("highway") && !tags.has("amenity") && !tags.has("shop")) continue
                return name
            }
            null
        }.getOrNull()
    }

    /** A street address, when nothing nearby has a name. */
    private fun nominatim(latitude: Double, longitude: Double): String? {
        val url = "$NOMINATIM?format=jsonv2&zoom=18&lat=$latitude&lon=$longitude"
        val body = get(url) ?: return null
        return runCatching {
            val json = JSONObject(body)
            json.optJSONObject("address")?.let { address ->
                val road = address.optString("road").takeIf { it.isNotBlank() }
                val number = address.optString("house_number").takeIf { it.isNotBlank() }
                val area = address.optString("neighbourhood").takeIf { it.isNotBlank() }
                    ?: address.optString("suburb").takeIf { it.isNotBlank() }
                listOfNotNull(listOfNotNull(number, road).joinToString(" ").takeIf { it.isNotBlank() }, area)
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            } ?: json.optString("display_name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = TIMEOUT_S * 1000
            readTimeout = TIMEOUT_S * 1000
            // Named, because both services ask for it in their terms and an anonymous flood is how a
            // free service stops being free for everybody.
            setRequestProperty("User-Agent", USER_AGENT)
            inputStream.bufferedReader().use { it.readText() }
        }
    }.getOrNull()

    private fun post(url: String, body: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_S * 1000
            readTimeout = TIMEOUT_S * 1000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            outputStream.use { it.write(body.toByteArray()) }
            inputStream.bufferedReader().use { it.readText() }
        }
    }.getOrNull()

    private fun dir() = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(key: String) = File(dir(), key.replace(':', '_') + ".txt")

    private fun write(key: String, name: String) {
        runCatching { fileFor(key).writeText(name) }
    }

    private companion object {
        const val DIR = "places"
        const val OVERPASS = "https://overpass-api.de/api/interpreter"
        const val NOMINATIM = "https://nominatim.openstreetmap.org/reverse"
        const val USER_AGENT = "LightNotebook/1 (github.com/gi-os/LightNotebook)"
        const val TIMEOUT_S = 20

        /** Fifty metres. Wider and a stay in a shop is named after the bar two doors down. */
        const val RADIUS_M = 50

        /** Nominatim's terms are one request a second; this is comfortably inside both services'. */
        const val POLITE_PAUSE_MS = 1_200L

        /**
         * Lookups per nightly run.
         *
         * A first night on a phone with months of photographs could have hundreds of unnamed spots.
         * Trickling is both politer and safer: tomorrow's run continues, and nothing is lost.
         */
        const val MAX_PER_RUN = 40
    }
}
