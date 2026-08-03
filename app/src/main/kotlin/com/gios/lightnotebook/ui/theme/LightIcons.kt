package com.gios.lightnotebook.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.gios.lightnotebook.R
import com.gios.lightnotebook.util.Holidays

/**
 * LightOS's own icon set. The vector drawables in `res/drawable/ic_*` are copied from
 * `lightphone/light-sdk` (MIT licence, © 2026 The Light Phone — see LICENSE-light-sdk);
 * an app that draws its own back chevron never quite looks like it belongs on the phone.
 */
class LightIconSpec(val name: String, @DrawableRes val res: Int)

object LightIcons {
    val Back = LightIconSpec("back", R.drawable.ic_back_white)
    val Calendar = LightIconSpec("calendar", R.drawable.ic_calendar_white)
    val Forward = LightIconSpec("forward", R.drawable.ic_arrow_right_white)
    val Add = LightIconSpec("add", R.drawable.ic_add_white)
    val Camera = LightIconSpec("camera", R.drawable.ic_camera)
    val FlashOff = LightIconSpec("flash off", R.drawable.ic_camera_flash_off)
    val FlashOn = LightIconSpec("flash on", R.drawable.ic_camera_flash_on)
    val FlashAuto = LightIconSpec("flash auto", R.drawable.ic_camera_flash_auto)
    val Settings = LightIconSpec("settings", R.drawable.ic_settings_white)
    val Search = LightIconSpec("search", R.drawable.ic_search_white)
    val Close = LightIconSpec("close", R.drawable.ic_close_white)
    val Accept = LightIconSpec("confirm", R.drawable.ic_accept_white)
    val Trash = LightIconSpec("delete", R.drawable.ic_trash)
    val Delete = LightIconSpec("backspace", R.drawable.ic_delete_white)
    val Pencil = LightIconSpec("edit", R.drawable.ic_pencil_white)
    val Compose = LightIconSpec("new note", R.drawable.ic_compose_white)
    val Star = LightIconSpec("pinned", R.drawable.ic_star_white)
    val Ticket = LightIconSpec("film ticket", R.drawable.ic_ticket_white)
    val Pin = LightIconSpec("a place", R.drawable.ic_pin_white)
    val Person = LightIconSpec("someone", R.drawable.ic_person_white)
    val Group = LightIconSpec("a group", R.drawable.ic_group_white)
    val StarOutline = LightIconSpec("pin", R.drawable.ic_star_outline_white)
    val List = LightIconSpec("list", R.drawable.ic_list_white)
    val Alarm = LightIconSpec("time", R.drawable.ic_alarm_white)
    val SelectOn = LightIconSpec("selected", R.drawable.ic_select_on_white)
    val SelectOff = LightIconSpec("not selected", R.drawable.ic_select_off_white)
    val Refresh = LightIconSpec("try again", R.drawable.ic_refresh_white)
    val Up = LightIconSpec("up", R.drawable.ic_up_white)
    val Down = LightIconSpec("down", R.drawable.ic_down_white)
    val Spacer = LightIconSpec("", R.drawable.ic_spacer)

    /**
     * The glyph for a US federal holiday, by [com.gios.lightnotebook.util.Holidays] id.
     *
     * Drawn for this app rather than taken from the SDK set, which has no holidays in it, but to
     * the same rules: a 30-unit viewport, one filled path, no stroke. They are chosen to stay
     * apart from each other at about twelve pixels, which is what a month cell gives them — the
     * eight-pointed firework and the five-pointed Juneteenth star differ by their point count
     * for exactly that reason.
     */
    fun holiday(id: String): LightIconSpec? = when (id) {
        Holidays.NEW_YEAR -> LightIconSpec("new year", R.drawable.ic_holiday_new_year)
        Holidays.MLK -> LightIconSpec("martin luther king jr day", R.drawable.ic_holiday_mlk)
        Holidays.PRESIDENTS -> LightIconSpec("presidents day", R.drawable.ic_holiday_presidents)
        Holidays.MEMORIAL -> LightIconSpec("memorial day", R.drawable.ic_holiday_memorial)
        Holidays.JUNETEENTH -> LightIconSpec("juneteenth", R.drawable.ic_holiday_juneteenth)
        Holidays.INDEPENDENCE -> LightIconSpec("independence day", R.drawable.ic_holiday_independence)
        Holidays.LABOR -> LightIconSpec("labor day", R.drawable.ic_holiday_labor)
        Holidays.COLUMBUS -> LightIconSpec("columbus day", R.drawable.ic_holiday_columbus)
        Holidays.VETERANS -> LightIconSpec("veterans day", R.drawable.ic_holiday_veterans)
        Holidays.THANKSGIVING -> LightIconSpec("thanksgiving", R.drawable.ic_holiday_thanksgiving)
        Holidays.CHRISTMAS -> LightIconSpec("christmas", R.drawable.ic_holiday_christmas)
        else -> null
    }
}

private const val DEFAULT_SIZE_UNITS = 2f

/**
 * Icons are sized in grid units and take the content colour, as in LightOS. [tint] exists
 * only for the one case the SDK doesn't have: a tab bar, where the destinations you are
 * not on have to recede.
 */
@Composable
fun LightIcon(
    icon: LightIconSpec,
    modifier: Modifier = Modifier,
    size: Float = DEFAULT_SIZE_UNITS,
    tint: Color? = null,
    contentDescription: String? = icon.name,
) {
    Icon(
        painter = painterResource(icon.res),
        contentDescription = contentDescription?.takeIf { it.isNotBlank() },
        tint = tint ?: LightThemeTokens.colors.content,
        modifier = modifier.size(size.gridUnitsAsDp()),
    )
}
