package com.gios.lightnotebook

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gios.lightnotebook.camera.RollCapture
import com.gios.lightnotebook.hw.LightKey
import com.gios.lightnotebook.hw.LightKeys
import com.gios.lightnotebook.hw.LocalWheelBus
import com.gios.lightnotebook.hw.WheelBus
import com.gios.lightnotebook.notify.Notifier
import com.gios.lightnotebook.notify.WeatherArchiveWorker
import com.gios.lightnotebook.report.CrashLog
import com.gios.lightnotebook.report.ReportContext
import com.gios.lightnotebook.report.Reports
import com.gios.lightnotebook.report.Screenshot
import com.gios.lightnotebook.report.ShakeDetector
import com.gios.lightnotebook.report.Symptom
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
import com.gios.lightnotebook.ui.ReportReason
import com.gios.lightnotebook.ui.ReportSheet
import com.gios.lightnotebook.ui.SettingsScreen
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightBottomBar
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightNotebookTheme
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightHorizontalSwipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import com.gios.lightnotebook.ui.ColorMode
import com.gios.lightnotebook.ui.ColourEffect

/** A `lightnotebook://note/<key>` link, split into the parts [MainActivity] acts on. */
private data class NoteLink(val key: String, val title: String)

/**
 * A report waiting to be confirmed.
 *
 * The screenshot is taken at the moment of the shake and carried here, rather than taken when
 * the sheet asks for it: by then the sheet is the thing on screen, and a picture of the sheet
 * is a picture of nothing.
 */
private data class ReportRequest(val reason: ReportReason, val shot: Bitmap?)

private const val NOTE_SCHEME = "lightnotebook"
private const val NOTE_HOST = "note"

/** Generous for a set of handles, small enough that nobody can stuff the database. */
private const val MAX_KEY = 256
private const val MAX_TITLE = 128

class MainActivity : ComponentActivity() {

    /**
     * A day asked for from outside the app — a tapped reminder. Held in a flow rather than
     * read straight off the intent so a second tap while the app is open still lands.
     */
    private val pendingDay = MutableStateFlow<Long?>(null)

    /**
     * A note asked for from outside the app — LightChat's contact page, which keeps one
     * note per conversation here and opens it by `lightnotebook://note/<key>`. Held in a
     * flow for the same reason [pendingDay] is: a second tap while the app is already open
     * arrives through [onNewIntent], not through [onCreate].
     */
    private val pendingNote = MutableStateFlow<NoteLink?>(null)

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /** Set by a shake, or by finding a crash log left over from the last run. */
    private val reportRequest = MutableStateFlow<ReportRequest?>(null)

    /** Null until [onCreate]; also null for good on a phone with no accelerometer. */
    private var shake: ShakeDetector? = null

    /**
     * Every hardware key arrives here first — `DecorView` hands the event to the window
     * callback before it walks the view hierarchy — so a turn still scrolls the day while
     * the add-a-line field holds focus and the keyboard is up.
     *
     * Both halves of the pair are swallowed: one notch is a complete DOWN+UP, and letting
     * the UP through would let a text field take it as a keypress.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Greyscale comes back the moment this app is not the one you are looking at.
     *
     * Not left to the composable's disposal: leaving for another app does not dispose the
     * composition, so without this the whole phone would stay in colour behind us.
     */
    override fun onStop() {
        super.onStop()
        ColorMode.onAppHidden(this)
    }

    /**
     * The accelerometer runs only while this app is the one you are looking at.
     *
     * Resumed rather than started: a 50Hz stream is a real battery cost, and a shake while
     * something else is in front is not a complaint about Notebook.
     */
    override fun onResume() {
        super.onResume()
        if (reportRequest.value == null) shake?.start()
    }

    override fun onPause() {
        super.onPause()
        shake?.stop()
    }

    override fun onStart() {
        super.onStart()
        ColorMode.onAppVisible(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDay.value = dayIn(intent)
        noteIn(intent)?.let { pendingNote.value = it }
    }

    private fun dayIn(intent: Intent?): Long? =
        intent?.getLongExtra(Notifier.EXTRA_EPOCH_DAY, 0L)?.takeIf { it != 0L }

    /**
     * `lightnotebook://note/<key>?title=<label>` — the whole external surface of this app.
     *
     * The key is opaque and is whatever the calling app can produce again tomorrow;
     * LightChat sends a conversation's normalised handles. The title only ever seeds a note
     * that does not exist yet. A malformed link is null, and nothing happens.
     */
    private fun noteIn(intent: Intent?): NoteLink? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (!uri.scheme.equals(NOTE_SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(NOTE_HOST, ignoreCase = true)) return null
        // Decoded by Uri, and from a *path* segment, where a literal "+" survives —
        // unlike a query parameter, where Android decodes "+" to a space.
        val key = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // The activity is exported and browsable, so both of these are attacker-chosen
        // strings. Neither is read back by anything, but a note per distinct key with a
        // title of arbitrary length is still somebody else's junk in your notebook.
        if (key.length > MAX_KEY) return null
        return NoteLink(key = key, title = uri.getQueryParameter("title").orEmpty().take(MAX_TITLE))
    }

    /**
     * A shake, caught. Take the picture first and ask afterwards — the sheet is about to cover
     * whatever it was that looked wrong.
     */
    private fun onShaken() {
        if (reportRequest.value != null) return
        shake?.stop()
        Screenshot.capture(window) { bitmap ->
            reportRequest.value = ReportRequest(ReportReason.Shaken, bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, so that a crash anywhere below still leaves a trace to send.
        CrashLog.install(this)
        Notifier.ensureChannel(this)
        shake = ShakeDetector(this, ::onShaken).takeIf { it.available }
        // A crash log that is still on disk was never sent. Ask once, on the next launch,
        // rather than at the moment of the crash: a dying process has no business opening a
        // socket, and a dialog on top of a crash is not a thing anyone reads.
        if (savedInstanceState == null && CrashLog.read(this) != null) {
            reportRequest.value = ReportRequest(ReportReason.Crashed, null)
        }
        pendingDay.value = dayIn(intent)
        // Only on a genuinely new launch. `onNewIntent` calls `setIntent`, so the VIEW
        // intent becomes the activity's permanent intent — and a recreation (a theme or
        // font-scale change, a restore after process death, "don't keep activities") would
        // re-parse it and push the note again on top of the back stack that was just
        // restored with it. Unbounded: it would fire again on every recreation for the life
        // of the task.
        pendingNote.value = if (savedInstanceState == null) noteIn(intent) else null
        setContent {
            LightNotebookTheme {
                // **Colour, everywhere.** The panel is a full-colour AMOLED and LightOS's grey is
                // the accessibility daltonizer, so the app can simply hold it off while it is in
                // front. Released in onStop below, or the rest of the phone would stay in colour
                // after leaving — see ColorMode. Needs one adb grant and stays grey without it.
                ColourEffect(enabled = true)

                val nav = rememberNavController()
                val vm: NotebookViewModel = viewModel()

                /*
                 * Photographing a page is Roll's job now, so the result comes back through an
                 * activity launcher rather than from a screen in this app.
                 *
                 * The file is held in a `remember` beside the launcher and not in the view
                 * model, because it is a property of *this* launch: the process can die while
                 * Roll is in front, and a path that survived into a restored view model would
                 * point at a photograph this composition never asked for.
                 */
                var pendingCapture by remember { mutableStateOf<File?>(null) }
                val rollCapture = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val file = pendingCapture
                    pendingCapture = null
                    if (file == null) return@rememberLauncherForActivityResult
                    RollCapture.revoke(this@MainActivity, file)
                    // RESULT_OK is not sufficient — see RollCapture.wrote. A cancelled shot
                    // leaves an empty file behind, which has to be cleared or the next
                    // capture inherits it.
                    if (result.resultCode == Activity.RESULT_OK && RollCapture.wrote(file)) {
                        // Rotation 0: Roll writes an oriented JPEG and ImageUtils reads EXIF
                        // first, using the fallback only when the file carries none.
                        vm.readCapture(file, rotationDegrees = 0)
                        nav.navigate("capture") { popUpTo("home") }
                    } else {
                        file.delete()
                    }
                }

                // A force-stop cancels every alarm an app owns and says nothing about it,
                // so reminders are re-armed on the way in as well as after a reboot.
                LaunchedEffect(Unit) {
                    vm.rearmReminders()
                    vm.scheduleSync()
                    // Asked for once a day, on a charger. Nothing on a screen ever fetches weather;
                    // this is what makes that possible. KEEP, so opening the app often does not keep
                    // resetting the period.
                    WeatherArchiveWorker.schedule(this@MainActivity)
                    // Anything written while the phone was offline, or while the last build
                    // had no key in it, goes out now.
                    Reports.flush(this@MainActivity)
                }

                // Which screen a report was filed from. Read by the crash handler too, which
                // runs on a dying thread with no view of any of this.
                LaunchedEffect(nav) {
                    nav.currentBackStackEntryFlow.collect { entry ->
                        ReportContext.screen = entry.destination.route ?: "home"
                    }
                }

                val reports = rememberCoroutineScope()
                val report by reportRequest.collectAsStateWithLifecycle()
                report?.let { pending ->
                    ReportSheet(
                        reason = pending.reason,
                        hasScreenshot = pending.shot != null,
                        onDismiss = {
                            // Saying no to a crash log throws it away. Otherwise the same
                            // question waits at every launch until the app crashes again.
                            if (pending.reason == ReportReason.Crashed) CrashLog.clear(this@MainActivity)
                            reportRequest.value = null
                            shake?.start()
                        },
                        onSend = { symptom, note, includeScreenshot ->
                            reportRequest.value = null
                            shake?.start()
                            reports.launch {
                                withContext(Dispatchers.IO) {
                                    // The trace goes with a crash prompt, and with a shake
                                    // that says the app closed itself — the two cases where
                                    // it is the answer. Not with a report about a slow list,
                                    // where a week-old stack trace is only noise.
                                    val crash = if (
                                        pending.reason == ReportReason.Crashed ||
                                        symptom == Symptom.Crashed
                                    ) {
                                        CrashLog.read(this@MainActivity)
                                    } else {
                                        null
                                    }
                                    Reports.enqueue(
                                        this@MainActivity,
                                        Reports.compose(
                                            context = this@MainActivity,
                                            symptom = symptom,
                                            note = note,
                                            screen = ReportContext.screen,
                                            crash = crash,
                                            shot = pending.shot
                                                ?.takeIf { includeScreenshot }
                                                ?.let { Screenshot.encode(it) },
                                        ),
                                    )
                                    if (crash != null) CrashLog.clear(this@MainActivity)
                                }
                                Reports.flush(this@MainActivity)
                            }
                        },
                    )
                }

                val requestedDay by pendingDay.collectAsStateWithLifecycle()
                LaunchedEffect(requestedDay) {
                    val day = requestedDay ?: return@LaunchedEffect
                    nav.navigate("day/$day")
                    pendingDay.value = null
                }

                val requestedNote by pendingNote.collectAsStateWithLifecycle()
                LaunchedEffect(requestedNote) {
                    val link = requestedNote ?: return@LaunchedEffect
                    // Cleared before the lookup, not after: finding or creating the note is
                    // a database round trip, and leaving the request standing across it
                    // would re-fire the effect on the next recomposition.
                    pendingNote.value = null
                    // Awaited here rather than handed a callback: this effect is scoped to
                    // the composition, so an activity recreated mid-lookup cancels it
                    // instead of navigating a NavController that no longer exists.
                    val id = vm.noteFor(link.key, link.title) ?: return@LaunchedEffect
                    // Same shape as arriving from the capture screen: one copy of the note
                    // on the stack however many times the link is tapped.
                    nav.navigate("note/$id") {
                        popUpTo("home")
                        launchSingleTop = true
                    }
                }

                // Every screen below can reach the wheel; which scroller answers a notch
                // is decided down there, by whatever is actually on the panel.
                CompositionLocalProvider(LocalWheelBus provides wheel) {
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
                                        // Roll first, the in-app camera when it isn't there.
                                        // Decided here rather than inside the sheet so the
                                        // fallback is a navigation like any other.
                                        val file = vm.newCaptureFile()
                                        val intent = RollCapture.intentFor(this@MainActivity, file)
                                        if (intent != null) {
                                            pendingCapture = file
                                            rollCapture.launch(intent)
                                        } else {
                                            nav.navigate("camera")
                                        }
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
                                DayScreen(
                                    vm = vm,
                                    onBack = { nav.popBackStack() },
                                    onOpenNote = { id -> nav.navigate("note/$id") },
                                )
                            }
                            composable("camera") {
                                CameraScreen(
                                    hint = "A page of writing, or a calendar.",
                                    newFile = { vm.newCaptureFile() },
                                    onCaptured = { file, rotation ->
                                        vm.readCapture(file, rotation)
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
                                    // The same sheet the shake opens. Shaking a phone you are
                                    // already annoyed with is not always something you want to
                                    // do in front of other people.
                                    onReport = { onShaken() },
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

                // No onOpenDay: a day is opened by the cell itself growing into one, in place.
                else -> CalendarScreen(
                    vm = vm,
                    onOpenAgenda = onOpenAgenda,
                    onOpenNote = onOpenNote,
                    onSwipePage = { direction -> if (direction < 0) tab = 0 },
                )
            }
        }
        // Out of the way while a day is being read, back the moment you scroll up. The day owns
        // the decision; this bar only follows, because it belongs to the shell underneath.
        val chromeHidden by vm.chromeHidden.collectAsStateWithLifecycle()
        AnimatedVisibility(visible = !chromeHidden) {
        Column {
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
        }
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
