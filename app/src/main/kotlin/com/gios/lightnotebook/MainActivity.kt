package com.gios.lightnotebook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightnotebook.ai.ReadMode
import com.gios.lightnotebook.notify.Notifier
import com.gios.lightnotebook.ui.AgendaScreen
import com.gios.lightnotebook.ui.CalendarScreen
import com.gios.lightnotebook.ui.CalendarsScreen
import com.gios.lightnotebook.ui.CameraScreen
import com.gios.lightnotebook.ui.CaptureScreen
import com.gios.lightnotebook.ui.DayScreen
import com.gios.lightnotebook.ui.KeyScanScreen
import com.gios.lightnotebook.ui.LightActionSheet
import com.gios.lightnotebook.ui.LightSheetAction
import com.gios.lightnotebook.ui.NoteEditorScreen
import com.gios.lightnotebook.ui.NotebookViewModel
import com.gios.lightnotebook.ui.NotesScreen
import com.gios.lightnotebook.ui.SettingsScreen
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightBottomBar
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightNotebookTheme
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightHorizontalSwipe
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * A day asked for from outside the app — a tapped reminder. Held in a flow rather than
     * read straight off the intent so a second tap while the app is open still lands.
     */
    private val pendingDay = MutableStateFlow<Long?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDay.value = dayIn(intent)
    }

    private fun dayIn(intent: Intent?): Long? =
        intent?.getLongExtra(Notifier.EXTRA_EPOCH_DAY, 0L)?.takeIf { it != 0L }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        pendingDay.value = dayIn(intent)
        setContent {
            LightNotebookTheme {
                val nav = rememberNavController()
                val vm: NotebookViewModel = viewModel()

                // A force-stop cancels every alarm an app owns and says nothing about it,
                // so reminders are re-armed on the way in as well as after a reboot.
                LaunchedEffect(Unit) {
                    vm.rearmReminders()
                    vm.scheduleSync()
                }

                val requestedDay by pendingDay.collectAsStateWithLifecycle()
                LaunchedEffect(requestedDay) {
                    val day = requestedDay ?: return@LaunchedEffect
                    nav.navigate("day/$day")
                    pendingDay.value = null
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background)
                        .systemBarsPadding(),
                ) {
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeShell(
                                vm = vm,
                                onOpenNote = { id -> nav.navigate("note/$id") },
                                onOpenDay = { day -> nav.navigate("day/$day") },
                                onOpenAgenda = { nav.navigate("agenda") },
                                onSettings = { nav.navigate("settings") },
                                onCamera = { mode ->
                                    vm.setReadMode(mode)
                                    nav.navigate("camera")
                                },
                            )
                        }
                        composable(
                            "note/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.StringType }),
                        ) { entry ->
                            NoteEditorScreen(
                                vm = vm,
                                noteId = entry.arguments!!.getString("id")!!,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(
                            "day/{epochDay}",
                            arguments = listOf(navArgument("epochDay") { type = NavType.LongType }),
                            // The day grows out of the cell you were pinching and shrinks back
                            // into it, so the planner's zoom carries on through the screen
                            // change instead of cutting.
                            enterTransition = { scaleIn(initialScale = 0.86f) + fadeIn() },
                            exitTransition = { scaleOut(targetScale = 0.86f) + fadeOut() },
                            popEnterTransition = { scaleIn(initialScale = 0.94f) + fadeIn() },
                            popExitTransition = { scaleOut(targetScale = 0.86f) + fadeOut() },
                        ) { entry ->
                            val day = entry.arguments!!.getLong("epochDay")
                            // The route only seeds the selection; the day screen reads it
                            // from the view model afterwards, so swiping can move it without
                            // pushing a screen per day.
                            LaunchedEffect(day) { vm.selectDay(day) }
                            DayScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("camera") {
                            CameraScreen(
                                hint = "A page of writing, or a calendar.",
                                newFile = { vm.newCaptureFile() },
                                onCaptured = { file ->
                                    vm.readCapture(file)
                                    nav.navigate("capture") {
                                        popUpTo("home")
                                    }
                                },
                                onCancel = { nav.popBackStack() },
                            )
                        }
                        composable("capture") {
                            CaptureScreen(
                                vm = vm,
                                onOpenNote = { id ->
                                    nav.navigate("note/$id") { popUpTo("home") }
                                },
                                onOpenDay = { day ->
                                    nav.navigate("day/$day") { popUpTo("home") }
                                },
                                onRetry = {
                                    vm.clearCapture()
                                    nav.navigate("camera") { popUpTo("home") }
                                },
                                onCancel = {
                                    vm.clearCapture()
                                    nav.popBackStack("home", false)
                                },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                vm = vm,
                                onScanQr = { nav.navigate("scan") },
                                onCalendars = { nav.navigate("calendars") },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("agenda") {
                            AgendaScreen(
                                vm = vm,
                                onOpenDay = { day -> nav.navigate("day/$day") },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("calendars") {
                            CalendarsScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("scan") {
                            KeyScanScreen(
                                onKey = { key ->
                                    vm.setApiKey(key)
                                    nav.popBackStack()
                                },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three buttons at the bottom are the whole app: notes, add, calendar. ADD is in the
 * middle because it is the only verb of the three, and because that is where a thumb
 * already rests.
 */
@Composable
private fun HomeShell(
    vm: NotebookViewModel,
    onOpenNote: (String) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenAgenda: () -> Unit,
    onSettings: () -> Unit,
    onCamera: (ReadMode) -> Unit,
) {
    // Saveable, not just remembered: leaving for a day screen disposes this, and a plain
    // remember meant coming back from a day landed you on the notes page.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> NotesScreen(
                    vm = vm,
                    onOpenNote = onOpenNote,
                    onSettings = onSettings,
                    // Swipe across to the calendar, the same way the planner pages back here.
                    modifier = Modifier.lightHorizontalSwipe(
                        onLeft = { tab = 1 },
                        onRight = {},
                    ),
                )

                else -> CalendarScreen(
                    vm = vm,
                    onOpenDay = onOpenDay,
                    onOpenAgenda = onOpenAgenda,
                    onSwipePage = { direction -> if (direction < 0) tab = 0 },
                )
            }
        }
        LightRule()
        // Icons, not labels: three words at the Button variant's 15% tracking filled the
        // bar edge to edge, and LightOS's own action bar is icons wherever a glyph exists.
        LightBottomBar(
            items = listOf(
                LightBarItem.Icon(
                    icon = LightIcons.List,
                    sizeUnits = 1.9f,
                    active = tab == 0,
                    lighten = true,
                ) { tab = 0 },
                LightBarItem.Icon(icon = LightIcons.Add, sizeUnits = 1.9f) { addSheet = true },
                LightBarItem.Icon(
                    icon = LightIcons.Calendar,
                    sizeUnits = 1.9f,
                    active = tab == 1,
                    lighten = true,
                ) { tab = 1 },
            ),
        )
    }

    if (addSheet) {
        LightActionSheet(heading = "ADD", onDismiss = { addSheet = false }) {
            LightSheetAction(
                label = "New note",
                sub = "Type it",
            ) {
                addSheet = false
                vm.createNote { id -> onOpenNote(id) }
            }
            LightSheetAction(
                label = "Camera",
                sub = if (tab == 1) {
                    "Photograph a calendar — dates go straight in"
                } else {
                    "Photograph a page — Claude reads it"
                },
            ) {
                addSheet = false
                // The calendar tab biases the read towards dates; the notes tab leaves it
                // to the model, which is right far more often than it is wrong.
                onCamera(if (tab == 1) ReadMode.CALENDAR else ReadMode.AUTO)
            }
        }
    }
}
