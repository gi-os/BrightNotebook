package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.light.common.hw.WheelInDialog
import com.gios.light.common.hw.WheelScroll
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.DayPosition
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.RepeatFreq
import com.gios.lightnotebook.util.Recurrence
import com.gios.lightnotebook.util.RecurrenceRule
import java.time.DayOfWeek

/** Where a monthly rule takes its day from: the number, or the weekday's place in the month. */
private enum class MonthlyBy { DAY_OF_MONTH, WEEKDAY }

/** How a series stops. */
private enum class Ending { NEVER, AFTER, ON }

/**
 * The repeat picker.
 *
 * Everything a rule needs on one sheet, in the order the sentence reads: how often, how many of
 * those apart, which days, and when it stops. It produces an `RRULE` string and nothing else —
 * the same string an imported feed would have supplied — so both halves of recurrence are the
 * same engine reading the same text.
 *
 * [WheelInDialog] is why the wheel works in here. A `ModalBottomSheet` is its own window with
 * its own `ViewRootImpl`, so the activity's key handling never sees the wheel while the sheet is
 * up; without that call the picker would be a scrollable list the wheel could not scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatSheet(
    startEpochDay: Long,
    current: String?,
    onDismiss: () -> Unit,
    onSet: (String?) -> Unit,
) {
    val colors = LightThemeTokens.colors
    val start = NoteDates.of(startEpochDay)
    val existing = (Recurrence.parse(current) as? Recurrence.Parsed.Rule)?.rule

    var freq by remember { mutableStateOf(existing?.freq) }
    var interval by remember { mutableIntStateOf(existing?.interval ?: 1) }
    var weekdays by remember {
        mutableStateOf(
            existing?.takeIf { it.freq == RepeatFreq.WEEKLY }
                ?.byDay?.map { it.day }?.toSet()
                ?: setOf(start.dayOfWeek),
        )
    }
    var monthlyBy by remember {
        mutableStateOf(
            if (existing?.freq == RepeatFreq.MONTHLY && existing.byDay.isNotEmpty()) {
                MonthlyBy.WEEKDAY
            } else {
                MonthlyBy.DAY_OF_MONTH
            },
        )
    }
    var ending by remember {
        mutableStateOf(
            when {
                existing?.count != null -> Ending.AFTER
                existing?.untilDay != null -> Ending.ON
                else -> Ending.NEVER
            },
        )
    }
    var count by remember { mutableIntStateOf(existing?.count ?: 10) }
    var untilText by remember {
        mutableStateOf(existing?.untilDay?.let { NoteDates.isoDate(it) } ?: "")
    }

    // Which occurrence of its own weekday the start date is — "the second Tuesday" — and the
    // same thing counted from the end, since "the last Friday" is how people say that one.
    val nth = (start.dayOfMonth - 1) / 7 + 1
    val isLastSuchWeekday = start.dayOfMonth + 7 > start.lengthOfMonth()

    fun built(): String? {
        val f = freq ?: return null
        val rule = RecurrenceRule(
            freq = f,
            interval = interval,
            byDay = when {
                f == RepeatFreq.WEEKLY -> weekdays.sortedBy { it.value }.map { DayPosition(null, it) }
                f == RepeatFreq.MONTHLY && monthlyBy == MonthlyBy.WEEKDAY ->
                    listOf(DayPosition(if (isLastSuchWeekday) -1 else nth, start.dayOfWeek))
                else -> emptyList()
            },
            byMonthDay = if (f == RepeatFreq.MONTHLY && monthlyBy == MonthlyBy.DAY_OF_MONTH) {
                listOf(start.dayOfMonth)
            } else {
                emptyList()
            },
            count = if (ending == Ending.AFTER) count else null,
            untilDay = if (ending == Ending.ON) NoteDates.parseIsoDate(untilText) else null,
        )
        return Recurrence.format(rule)
    }

    // A date that has been typed but is not a date yet: the button says so by staying off,
    // rather than silently saving a series with no end.
    val endReady = ending != Ending.ON || NoteDates.parseIsoDate(untilText) != null
    val daysReady = freq != RepeatFreq.WEEKLY || weekdays.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        dragHandle = null,
    ) {
        val scroll = rememberScrollState()
        // The sheet is its own window; both of these are needed for the wheel to reach the list.
        WheelInDialog()
        WheelScroll(scroll)

        Column(
            Modifier
                .heightIn(max = 26f.verticalGridUnitsAsDp())
                .verticalScroll(scroll)
                .padding(bottom = 1.2f.verticalGridUnitsAsDp()),
        ) {
            LightSectionLabel("REPEATS")
            ChipRow {
                LightChip("NEVER", freq == null, Modifier.weight(1f)) { freq = null }
                LightChip("DAY", freq == RepeatFreq.DAILY, Modifier.weight(1f)) {
                    freq = RepeatFreq.DAILY
                }
                LightChip("WEEK", freq == RepeatFreq.WEEKLY, Modifier.weight(1f)) {
                    freq = RepeatFreq.WEEKLY
                }
            }
            ChipRow {
                LightChip("MONTH", freq == RepeatFreq.MONTHLY, Modifier.weight(1f)) {
                    freq = RepeatFreq.MONTHLY
                }
                LightChip("YEAR", freq == RepeatFreq.YEARLY, Modifier.weight(1f)) {
                    freq = RepeatFreq.YEARLY
                }
            }

            if (freq != null) {
                LightSectionLabel("EVERY")
                ChipRow {
                    // Chips rather than a number field: the answer is nearly always in here, and
                    // a keyboard on this phone costs half the screen.
                    listOf(1, 2, 3, 4, 6).forEach { n ->
                        LightChip(n.toString(), interval == n, Modifier.weight(1f)) { interval = n }
                    }
                }

                if (freq == RepeatFreq.WEEKLY) {
                    LightSectionLabel("ON")
                    ChipRow {
                        Recurrence.WEEKDAYS.forEach { day ->
                            LightChip(
                                label = Recurrence.letter(day),
                                selected = day in weekdays,
                                modifier = Modifier.weight(1f),
                            ) {
                                weekdays = if (day in weekdays) weekdays - day else weekdays + day
                            }
                        }
                    }
                }

                if (freq == RepeatFreq.MONTHLY) {
                    LightSectionLabel("ON")
                    ChipRow {
                        LightChip(
                            label = "DAY ${start.dayOfMonth}",
                            selected = monthlyBy == MonthlyBy.DAY_OF_MONTH,
                            modifier = Modifier.weight(1f),
                        ) { monthlyBy = MonthlyBy.DAY_OF_MONTH }
                        LightChip(
                            label = weekdayPlace(isLastSuchWeekday, nth, start.dayOfWeek),
                            selected = monthlyBy == MonthlyBy.WEEKDAY,
                            modifier = Modifier.weight(1f),
                        ) { monthlyBy = MonthlyBy.WEEKDAY }
                    }
                    if (monthlyBy == MonthlyBy.DAY_OF_MONTH && start.dayOfMonth > 28) {
                        // Said out loud rather than fixed behind the scenes: RFC 5545 skips a
                        // month that has no such day, and clamping to the 30th instead would be
                        // this app inventing a rule no other calendar would agree with.
                        Hint("A month with no ${start.dayOfMonth}th is skipped. Use the weekday instead to land every month.")
                    }
                }

                LightSectionLabel("ENDS")
                ChipRow {
                    LightChip("NEVER", ending == Ending.NEVER, Modifier.weight(1f)) {
                        ending = Ending.NEVER
                    }
                    LightChip("AFTER", ending == Ending.AFTER, Modifier.weight(1f)) {
                        ending = Ending.AFTER
                    }
                    LightChip("ON", ending == Ending.ON, Modifier.weight(1f)) { ending = Ending.ON }
                }

                if (ending == Ending.AFTER) {
                    ChipRow {
                        listOf(3, 5, 10, 20, 52).forEach { n ->
                            LightChip(n.toString(), count == n, Modifier.weight(1f)) { count = n }
                        }
                    }
                }

                if (ending == Ending.ON) {
                    Column(Modifier.padding(horizontal = lightInset())) {
                        LightInlineField(
                            value = untilText,
                            onValueChange = { untilText = it },
                            placeholder = "YYYY-MM-DD",
                        )
                    }
                }

                Hint(Recurrence.describe(built(), startEpochDay) ?: "Never")
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = lightInset(), vertical = 1.2f.verticalGridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(0.8f.gridUnitsAsDp()),
            ) {
                LightWideButton("CANCEL", Modifier.weight(1f), filled = false, onClick = onDismiss)
                LightWideButton(
                    label = "SET",
                    modifier = Modifier.weight(1f),
                    enabled = endReady && daysReady,
                    onClick = { onSet(built()) },
                )
            }
        }
    }
}

/** "THE 2ND TUE", or "THE LAST FRI" when the start date is the last of its kind in the month. */
private fun weekdayPlace(isLast: Boolean, nth: Int, day: DayOfWeek): String {
    val which = if (isLast) "LAST" else when (nth) {
        1 -> "1ST"
        2 -> "2ND"
        3 -> "3RD"
        else -> "${nth}TH"
    }
    return "$which ${Recurrence.shortName(day).uppercase()}"
}

@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.3f.verticalGridUnitsAsDp()),
        // Chips take a share of the row rather than a fraction of the screen: a `fillMaxWidth`
        // fraction inside a Row compounds against what is left, and three "third-width" chips
        // come out at a third, two ninths and four twenty-sevenths.
        horizontalArrangement = Arrangement.spacedBy(0.4f.gridUnitsAsDp()),
        content = content,
    )
}

@Composable
private fun Hint(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(
            horizontal = lightInset(),
            vertical = 0.6f.verticalGridUnitsAsDp(),
        ),
    )
}
