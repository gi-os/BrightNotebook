package com.gios.lightnotebook.util

/**
 * A phone call, reduced to what a day needs to say about it.
 *
 * The highest-signal thing this phone can contribute to a journal, and the cheapest: the call log
 * is a system provider with weeks of history in it, so a day from last month answers without this
 * app having been running for it. Nothing is recorded here — it is all a query, like usage stats
 * and unlike the step counter.
 *
 * Android-free so the phrasing and the ordering are tested off-device.
 */
object Calls {

    enum class Kind { Outgoing, Incoming, Missed }

    data class Call(
        val atMs: Long,
        /** The cached display name from the call log, if the system had one. */
        val name: String?,
        val number: String?,
        val kind: Kind,
        val seconds: Int,
    ) {
        /** Who it was, as a day should say it: a name if there is one, else a tidied number. */
        val who: String
            get() = name?.trim()?.takeIf { it.isNotBlank() }
                ?: number?.let { pretty(it) }
                ?: "Unknown number"

        /**
         * "Called Alex", "Alex called", "Missed call from Alex".
         *
         * Three phrasings rather than a label and an arrow, because these are three different
         * things that happened and only one of them is something you did.
         */
        val phrase: String
            get() = when (kind) {
                Kind.Outgoing -> "Called $who"
                Kind.Incoming -> "$who called"
                Kind.Missed -> "Missed call from $who"
            }

        /**
         * "12 min", "40 sec", or nothing at all.
         *
         * A missed call has no duration to state, and a zero-second answered call is one that
         * failed to connect — saying "0 min" about either would be describing a length that did
         * not happen.
         */
        val length: String?
            get() = when {
                kind == Kind.Missed -> null
                seconds <= 0 -> null
                seconds < 60 -> "$seconds sec"
                else -> "${seconds / 60} min"
            }
    }

    /**
     * A number as a person reads it, for the case where the system had no name.
     *
     * Deliberately not clever: full international formatting needs a library and a region, and
     * getting it subtly wrong looks worse than leaving the digits alone. US-shaped numbers are
     * grouped because those are almost all of them here; anything else is returned untouched.
     */
    fun pretty(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 ->
                "(${digits.take(3)}) ${digits.drop(3).take(3)}-${digits.drop(6)}"

            digits.length == 11 && digits.startsWith("1") ->
                "(${digits.drop(1).take(3)}) ${digits.drop(4).take(3)}-${digits.drop(7)}"

            else -> number.trim()
        }
    }

    /**
     * Whether a call is worth a row.
     *
     * An outgoing call of zero seconds is a misdial or a hang-up before it rang; it is not a
     * conversation and it is not a thing that happened. Missed calls are kept at any length,
     * because the whole content of a missed call is that it arrived.
     */
    fun worthShowing(call: Call): Boolean =
        call.kind == Kind.Missed || call.seconds > 0 || call.kind == Kind.Incoming
}
