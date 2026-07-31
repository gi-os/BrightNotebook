package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineTest {

    private val today = 20_300L
    private val noon = 12 * 60

    private fun row(id: String, minutes: Int?, day: Long = today) =
        AgendaRow(id = id, epochDay = day, minutes = minutes, title = id)

    private fun photo(id: Long, minutes: Int) = DayTimeline.PhotoAt(id, minutes)

    /* ---- the now line ---- */

    @Test
    fun `only today has a line through it`() {
        assertEquals(noon, DayTimeline.nowLine(today, today, noon))
        assertNull(DayTimeline.nowLine(today - 1, today, noon))
        assertNull(DayTimeline.nowLine(today + 1, today, noon))
    }

    @Test
    fun `a past day is entirely behind and a future day entirely ahead`() {
        assertTrue(DayTimeline.behind(today - 1, 23 * 60, today, noon))
        assertTrue(DayTimeline.behind(today - 1, null, today, noon))
        assertFalse(DayTimeline.behind(today + 1, 0, today, noon))
        assertFalse(DayTimeline.behind(today + 1, null, today, noon))
    }

    @Test
    fun `today splits at the clock`() {
        assertTrue(DayTimeline.behind(today, noon - 1, today, noon))
        assertTrue(DayTimeline.behind(today, noon, today, noon))
        assertFalse(DayTimeline.behind(today, noon + 1, today, noon))
    }

    @Test
    fun `an all-day thing on today is still today's business`() {
        // It has no time to have passed, so it cannot be in the diary half.
        assertFalse(DayTimeline.behind(today, null, today, noon))
    }

    /* ---- clustering, which is what keeps a heavy day bounded ---- */

    @Test
    fun `photographs taken together become one moment`() {
        val burst = (0..10).map { photo(it.toLong(), 480 + it) }
        val clustered = DayTimeline.cluster(burst)
        assertEquals(1, clustered.size)
        assertEquals(11, clustered.first().photos.size)
        assertFalse(clustered.first().single)
    }

    @Test
    fun `morning and evening stay separate`() {
        val clustered = DayTimeline.cluster(listOf(photo(1, 8 * 60), photo(2, 20 * 60)))
        assertEquals(2, clustered.size)
        assertTrue(clustered.all { it.single })
    }

    @Test
    fun `the gap is measured from the previous photograph, not the run's start`() {
        // Steady shooting every fifteen minutes for two hours is one afternoon, not eight
        // arbitrary twenty-minute blocks.
        val steady = (0..7).map { photo(it.toLong(), 14 * 60 + it * 15) }
        assertEquals(1, DayTimeline.cluster(steady).size)
    }

    @Test
    fun `a burst carries the time it started and the time it ended`() {
        val clustered = DayTimeline.cluster(listOf(photo(1, 480), photo(2, 495))).single()
        assertEquals(480, clustered.minutes)
        assertEquals(495, clustered.untilMinutes)
    }

    @Test
    fun `an unsorted list still clusters correctly`() {
        // MediaStore's order is whatever the timestamp reconciliation produced.
        val shuffled = listOf(photo(3, 20 * 60), photo(1, 480), photo(2, 485))
        val clustered = DayTimeline.cluster(shuffled)
        assertEquals(2, clustered.size)
        assertEquals(2, clustered.first().photos.size)
    }

    @Test
    fun `no photographs is no moments`() {
        assertTrue(DayTimeline.cluster(emptyList()).isEmpty())
    }

    /* ---- the order of the day ---- */

    @Test
    fun `all-day things come first and timed things follow in order`() {
        val items = DayTimeline.build(
            rows = listOf(row("evening", 20 * 60), row("all day", null), row("morning", 9 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(
            listOf("all day", "morning", "evening"),
            items.map { (it as DayTimeline.Item.Entry).row.title },
        )
    }

    @Test
    fun `a photograph sorts in among the entries by the time it was taken`() {
        val items = DayTimeline.build(
            rows = listOf(row("breakfast", 8 * 60), row("dinner", 19 * 60)),
            photos = listOf(photo(1, 13 * 60)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(3, items.size)
        assertTrue(items[1] is DayTimeline.Item.Photos)
    }

    @Test
    fun `a photograph is behind even when its clock has drifted forward`() {
        // A camera an hour fast would otherwise put a picture you are looking at into the
        // half of today that has not happened yet.
        val items = DayTimeline.build(
            rows = emptyList(),
            photos = listOf(photo(1, noon + 60)),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertTrue(items.single().behind)
    }

    @Test
    fun `an entry and a photograph at the same minute put the entry first`() {
        val items = DayTimeline.build(
            rows = listOf(row("lunch", noon)),
            photos = listOf(photo(1, noon)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertTrue(items.first() is DayTimeline.Item.Entry)
        assertTrue(items.last() is DayTimeline.Item.Photos)
    }

    /* ---- where the rule is drawn ---- */

    @Test
    fun `the rule sits between the two halves of today`() {
        val items = DayTimeline.build(
            rows = listOf(row("done", 9 * 60), row("later", 18 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(1, DayTimeline.nowLineIndex(items, noon))
    }

    @Test
    fun `no rule on a day that is entirely one thing or the other`() {
        val past = DayTimeline.build(
            rows = listOf(row("was", 9 * 60, today - 1)),
            photos = emptyList(),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.nowLineIndex(past, DayTimeline.nowLine(today - 1, today, noon)))
    }

    @Test
    fun `no rule when everything on today is on one side of it`() {
        // A rule above everything or below everything has nothing on one side and reads as a
        // mistake rather than as the time.
        val allAhead = DayTimeline.build(
            rows = listOf(row("later", 18 * 60), row("also later", 20 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.nowLineIndex(allAhead, noon))

        val allBehind = DayTimeline.build(
            rows = listOf(row("done", 8 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.nowLineIndex(allBehind, noon))
    }

    @Test
    fun `an empty day has no rule`() {
        assertNull(DayTimeline.nowLineIndex(emptyList(), noon))
    }

    /* ---- notes, as part of the day's record of itself ---- */

    private val dayStart = 1_753_848_000_000L // a fixed local midnight, in ms
    private val dayEnd = dayStart + 86_400_000L

    private fun activity(created: Long, updated: Long) = DayTimeline.noteActivity(
        noteId = "n1",
        title = "Ideas",
        createdAtMs = created,
        updatedAtMs = updated,
        dayStartMs = dayStart,
        dayEndExclusiveMs = dayEnd,
    )

    @Test
    fun `a note written on the day says so, at the time it was written`() {
        val note = activity(dayStart + 9 * 3_600_000L, dayStart + 9 * 3_600_000L)!!
        assertTrue(note.wrote)
        assertEquals(9 * 60, note.minutes)
    }

    @Test
    fun `a note written earlier and returned to counts as an edit, at the edit's time`() {
        val note = activity(dayStart - 5 * 86_400_000L, dayStart + 14 * 3_600_000L)!!
        assertFalse(note.wrote)
        assertEquals(14 * 60, note.minutes)
    }

    @Test
    fun `written and edited on the same day is one row, and it is the writing`() {
        // A second row saying you also edited the note you had just written is noise.
        val note = activity(dayStart + 9 * 3_600_000L, dayStart + 20 * 3_600_000L)!!
        assertTrue(note.wrote)
        assertEquals(9 * 60, note.minutes)
    }

    @Test
    fun `a note untouched on this day is not on it`() {
        assertNull(activity(dayStart - 86_400_000L, dayStart - 86_400_000L))
        assertNull(activity(dayEnd + 1_000L, dayEnd + 1_000L))
    }

    @Test
    fun `the window is half-open at both ends`() {
        assertTrue(activity(dayStart, dayStart)!!.wrote)
        // Midnight belongs to one day, not two.
        assertNull(activity(dayEnd, dayEnd))
    }

    @Test
    fun `an untitled note still has something to show`() {
        val note = DayTimeline.noteActivity(
            noteId = "n2",
            title = "   ",
            createdAtMs = dayStart + 60_000L,
            updatedAtMs = dayStart + 60_000L,
            dayStartMs = dayStart,
            dayEndExclusiveMs = dayEnd,
        )!!
        assertEquals("Untitled", note.title)
    }

    @Test
    fun `at the same minute the order is entry, then note, then photograph`() {
        val note = activity(dayStart + noon * 60_000L, dayStart + noon * 60_000L)!!
        val items = DayTimeline.build(
            rows = listOf(row("lunch", noon)),
            photos = listOf(photo(1, noon)),
            notes = listOf(note),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertTrue(items[0] is DayTimeline.Item.Entry)
        assertTrue(items[1] is DayTimeline.Item.Note)
        assertTrue(items[2] is DayTimeline.Item.Photos)
    }

    /* ---- bookends ---- */

    @Test
    fun `bookends are the first and last thing that happened`() {
        val items = DayTimeline.build(
            rows = listOf(row("up", 6 * 60 + 40), row("bed", 23 * 60 + 10), row("lunch", noon)),
            photos = emptyList(),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        val ends = DayTimeline.bookends(items)!!
        assertEquals(6 * 60 + 40, ends.firstMinutes)
        assertEquals(23 * 60 + 10, ends.lastMinutes)
    }

    @Test
    fun `a plan for this evening is not when today ended`() {
        val items = DayTimeline.build(
            rows = listOf(row("up", 7 * 60), row("dinner later", 20 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        // Only one thing has happened, so there is nothing to bookend yet.
        assertNull(DayTimeline.bookends(items))
    }

    @Test
    fun `an all-day entry is the day's heading, not its end`() {
        val items = DayTimeline.build(
            rows = listOf(row("all day", null), row("up", 7 * 60), row("bed", 22 * 60)),
            photos = emptyList(),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        val ends = DayTimeline.bookends(items)!!
        assertEquals(7 * 60, ends.firstMinutes)
        assertEquals(22 * 60, ends.lastMinutes)
    }

    @Test
    fun `one moment is not a day`() {
        val items = DayTimeline.build(
            rows = listOf(row("only thing", 9 * 60)),
            photos = emptyList(),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.bookends(items))
    }

    @Test
    fun `a photograph can be the first or last thing`() {
        val items = DayTimeline.build(
            rows = listOf(row("lunch", noon)),
            photos = listOf(photo(1, 6 * 60), photo(2, 22 * 60)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        val ends = DayTimeline.bookends(items)!!
        assertEquals(6 * 60, ends.firstMinutes)
        assertEquals(22 * 60, ends.lastMinutes)
    }

    @Test
    fun `an empty day has no bookends`() {
        assertNull(DayTimeline.bookends(emptyList()))
    }

    /* ---- listening, grouped into runs ---- */

    @Test
    fun `a run of one artist is one line, not twenty`() {
        val plays = (0..19).map { (14 * 60 + it * 4) to "Talk Talk" }
        val runs = DayTimeline.listening(plays)
        assertEquals(1, runs.size)
        assertEquals(listOf("Talk Talk"), runs.single().artists)
        assertEquals(20, runs.single().tracks)
    }

    @Test
    fun `a shuffled afternoon is one run, not one per artist`() {
        // Grouped by time, not by artist: what makes a stretch one stretch is that the music did not
        // stop. Splitting on every change turned a shuffled afternoon into thirty true, useless rows.
        val plays = listOf(
            (14 * 60) to "Talk Talk",
            (14 * 60 + 5) to "Talk Talk",
            (14 * 60 + 10) to "Slowdive",
        )
        val run = DayTimeline.listening(plays).single()
        assertEquals(3, run.tracks)
        assertEquals(2, run.distinctArtists)
        // Most played first.
        assertEquals("Talk Talk", run.artists.first())
    }

    @Test
    fun `only a few artists are named and the rest are counted`() {
        val plays = listOf("A", "A", "A", "B", "B", "C", "D", "E")
            .mapIndexed { i, who -> (14 * 60 + i * 3) to who }
        val run = DayTimeline.listening(plays).single()
        assertEquals(DayTimeline.NAMED_ARTISTS, run.artists.size)
        assertEquals(listOf("A", "B", "C"), run.artists)
        assertEquals(5, run.distinctArtists)
        assertEquals(2, run.moreArtists)
    }

    @Test
    fun `nothing is left over when there are few enough to name`() {
        val plays = listOf((14 * 60) to "A", (14 * 60 + 3) to "B")
        assertEquals(0, DayTimeline.listening(plays).single().moreArtists)
    }

    @Test
    fun `the same artist after a long silence is two runs`() {
        // Morning and evening are two things you did, not one long one.
        val plays = listOf((8 * 60) to "Talk Talk", (20 * 60) to "Talk Talk")
        assertEquals(2, DayTimeline.listening(plays).size)
    }

    @Test
    fun `a run carries when it started and when it stopped`() {
        val plays = listOf((14 * 60) to "A", (14 * 60 + 20) to "A")
        val run = DayTimeline.listening(plays).single()
        assertEquals(14 * 60, run.minutes)
        assertEquals(14 * 60 + 20, run.untilMinutes)
    }

    @Test
    fun `plays out of order still group`() {
        val plays = listOf((14 * 60 + 10) to "A", (14 * 60) to "A", (14 * 60 + 5) to "A")
        assertEquals(1, DayTimeline.listening(plays).size)
    }

    @Test
    fun `no listening is no rows`() {
        assertTrue(DayTimeline.listening(emptyList()).isEmpty())
    }

    @Test
    fun `a place and a song at the same minute have a fixed order`() {
        val items = DayTimeline.build(
            rows = emptyList(),
            photos = emptyList(),
            places = listOf(DayTimeline.Item.Place(noon, noon + 60, 0.0, 0.0, null)),
            listening = listOf(DayTimeline.Item.Listening(noon, noon + 30, listOf("A"), 1, 3)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertTrue(items.first() is DayTimeline.Item.Place)
        assertTrue(items.last() is DayTimeline.Item.Listening)
    }

    /* ---- picking the phone up ---- */

    @Test
    fun `a flurry of pickups is one row, not thirty`() {
        val times = (0..29).map { 14 * 60 + it * 3 }
        val runs = DayTimeline.pickups(times)
        assertEquals(1, runs.size)
        assertEquals(30, runs.single().times)
    }

    @Test
    fun `checking it at lunch and again in the evening is two`() {
        assertEquals(2, DayTimeline.pickups(listOf(12 * 60, 12 * 60 + 5, 20 * 60)).size)
    }

    @Test
    fun `a run carries when it began and ended`() {
        val run = DayTimeline.pickups(listOf(9 * 60, 9 * 60 + 10)).single()
        assertEquals(9 * 60, run.minutes)
        assertEquals(9 * 60 + 10, run.untilMinutes)
    }

    @Test
    fun `the first time you looked at the phone can be the start of the day`() {
        // Often earlier than anything written down, and it is genuinely when the day started.
        val items = DayTimeline.build(
            rows = listOf(row("breakfast", 9 * 60)),
            photos = emptyList(),
            pickups = DayTimeline.pickups(listOf(6 * 60 + 40)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(6 * 60 + 40, DayTimeline.bookends(items)!!.firstMinutes)
    }

    @Test
    fun `no pickups is no rows`() {
        assertTrue(DayTimeline.pickups(emptyList()).isEmpty())
    }

    /* ---- going home, going to work ---- */

    @Test
    fun `the two named zones read as sentences, not as labels`() {
        assertEquals("Went home", DayTimeline.Item.Arrived(0, "home").phrase)
        assertEquals("Went to work", DayTimeline.Item.Arrived(0, "work").phrase)
    }

    @Test
    fun `case does not matter and anything else still reads`() {
        assertEquals("Went home", DayTimeline.Item.Arrived(0, "Home").phrase)
        assertEquals("Went to the studio", DayTimeline.Item.Arrived(0, "the studio").phrase)
    }

    @Test
    fun `an arrival is something that happened, so it sits behind the line`() {
        assertTrue(DayTimeline.Item.Arrived(20 * 60, "home").behind)
    }

    @Test
    fun `going home can be the last thing on a day`() {
        val items = DayTimeline.build(
            rows = listOf(row("breakfast", 8 * 60)),
            photos = emptyList(),
            arrivals = listOf(DayTimeline.Item.Arrived(19 * 60 + 40, "home")),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(19 * 60 + 40, DayTimeline.bookends(items)!!.lastMinutes)
    }
}
