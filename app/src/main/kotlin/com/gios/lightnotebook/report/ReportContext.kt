package com.gios.lightnotebook.report

/**
 * Where the app was when it went wrong.
 *
 * A single field rather than anything passed down: the crash handler runs on a dying thread that
 * has no view of the composition, and a report is worth far more with "day" on it than without.
 * Written from the navigation listener, read from anywhere, so it is deliberately volatile.
 */
object ReportContext {
    @Volatile
    var screen: String = "home"
}
