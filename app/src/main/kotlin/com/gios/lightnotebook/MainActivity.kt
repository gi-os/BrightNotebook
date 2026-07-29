package com.gios.lightnotebook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightnotebook.ai.ReadMode
import com.gios.lightnotebook.ui.CalendarScreen
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
import com.gios.lightnotebook.ui.theme.LightNotebookTheme
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightThemeTokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightNotebookTheme {
                val nav = rememberNavController()
                val vm: NotebookViewModel = viewModel()

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
                        ) { entry ->
                            val day = entry.arguments!!.getLong("epochDay")
                            // Selecting here keeps the month grid and the day screen agreed
                            // on which day is open — and the day screen reads its entries
                            // from that selection, so it has to happen before first draw.
                            LaunchedEffect(day) { vm.selectDay(day) }
                            DayScreen(vm = vm, epochDay = day, onBack = { nav.popBackStack() })
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
                                onBack = { nav.popBackStack() },
                            )
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
    onSettings: () -> Unit,
    onCamera: (ReadMode) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var addSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> NotesScreen(vm = vm, onOpenNote = onOpenNote, onSettings = onSettings)
                else -> CalendarScreen(vm = vm, onOpenDay = onOpenDay)
            }
        }
        LightRule()
        LightBottomBar(
            items = listOf(
                LightBarItem.Text("NOTES", active = tab == 0, lighten = true) { tab = 0 },
                LightBarItem.Text("ADD", lighten = true) { addSheet = true },
                LightBarItem.Text("CALENDAR", active = tab == 1, lighten = true) { tab = 1 },
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
