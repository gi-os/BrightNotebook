package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightBottomBar
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.lightTextStyle
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.Edit
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.NoteMarkdown

/**
 * The editor. There is no read mode and no edit mode — opening a note puts the cursor in
 * it, because a notebook you have to unlock before writing in is a worse notebook.
 */
@Composable
fun NoteEditorScreen(
    vm: NotebookViewModel,
    noteId: String,
    onBack: () -> Unit,
) {
    val note by vm.observeNote(noteId).collectAsStateWithLifecycle(null)
    val folders by vm.folders.collectAsStateWithLifecycle()
    val colors = LightThemeTokens.colors

    var title by remember(noteId) { mutableStateOf(TextFieldValue()) }
    var body by remember(noteId) { mutableStateOf(TextFieldValue()) }
    var loaded by remember(noteId) { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var makingEvent by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var eventDay by remember { mutableStateOf<Long?>(null) }
    var showPhoto by remember { mutableStateOf(false) }

    // Load once. Re-syncing from the database on every emission would fight the cursor.
    val current = note
    if (!loaded && current != null) {
        title = TextFieldValue(current.title, TextRange(current.title.length))
        body = TextFieldValue(current.body, TextRange(current.body.length))
        loaded = true
    }

    fun persist(flush: Boolean) {
        val base = note ?: return
        val updated = base.copy(title = title.text, body = body.text)
        if (updated.title == base.title && updated.body == base.body) return
        if (flush) vm.flushNote(updated) else vm.saveNote(updated)
    }

    /**
     * Whatever is on screen when the editor goes away is what gets written — and a note
     * that was opened and never written in is thrown away rather than left in the list
     * as an "Untitled" to tidy up later.
     */
    val onLeave by rememberUpdatedState(
        newValue = {
            if (loaded && title.text.isBlank() && body.text.isBlank()) {
                vm.deleteNote(noteId)
            } else {
                persist(flush = true)
            }
        },
    )
    DisposableEffect(noteId) {
        onDispose { onLeave() }
    }

    /** Runs one of the markdown operations over the current selection. */
    fun format(op: (String, Int, Int) -> Edit) {
        val start = minOf(body.selection.start, body.selection.end)
        val end = maxOf(body.selection.start, body.selection.end)
        val edit = op(body.text, start, end)
        body = TextFieldValue(edit.text, TextRange(edit.selStart, edit.selEnd))
        persist(flush = false)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LightTopBar(
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f) {
                persist(flush = true)
                onBack()
            },
            title = note?.let { n ->
                folders.firstOrNull { it.id == n.folderId }?.name?.uppercase()
            } ?: "NOTE",
            right = LightBarItem.Text("MORE", onClick = { showActions = true }),
        )
        LightRule()

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = lightInset()),
        ) {
            LightInlineField(
                value = title.text,
                onValueChange = { title = TextFieldValue(it, TextRange(it.length)) },
                placeholder = "Title",
                variant = LightTextVariant.Subheading,
                underline = false,
                modifier = Modifier.padding(top = 0.8f.verticalGridUnitsAsDp()),
            )

            BasicTextField(
                value = body,
                onValueChange = { next ->
                    // A newline just typed carries the list marker down to the new line.
                    val grewByOne = next.text.length == body.text.length + 1
                    val cursor = next.selection.start
                    val typedNewline = next.selection.collapsed && cursor > 0 &&
                        next.text.getOrNull(cursor - 1) == '\n'
                    body = if (grewByOne && typedNewline) {
                        NoteMarkdown.continueList(next.text, cursor)
                            ?.let { TextFieldValue(it.text, TextRange(it.selStart, it.selEnd)) }
                            ?: next
                    } else {
                        next
                    }
                    persist(flush = false)
                },
                textStyle = lightTextStyle(LightTextVariant.Paragraph).copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                visualTransformation = remember(colors) {
                    NoteTransformation(colors.contentFaint, colors.content)
                },
                decorationBox = { inner ->
                    if (body.text.isEmpty()) {
                        LightText("Write…", LightTextVariant.Paragraph, lighten = true)
                    }
                    inner()
                },
                // No outer verticalScroll: a multi-line BasicTextField scrolls itself and
                // keeps the cursor on screen, and wrapping it in a scrollable parent is
                // what stops that happening — press return near the bottom and the new
                // line is written somewhere you cannot see.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 0.8f.verticalGridUnitsAsDp()),
            )
        }

        // Formatting appears only when there is something selected to format. It used to
        // sit there permanently, taking four grid units off every note for three verbs
        // that only apply to a selection.
        if (!body.selection.collapsed) {
            LightRule()
            LightBottomBar(
                items = listOf(
                    LightBarItem.Text("B", onClick = { format(NoteMarkdown::toggleBold) }),
                    LightBarItem.Text("•", onClick = { format(NoteMarkdown::toggleBullet) }),
                    LightBarItem.Text("1.", onClick = { format(NoteMarkdown::toggleNumbered) }),
                ),
            )
        }
    }

    if (showActions) {
        val n = note
        LightActionSheet(heading = "NOTE", onDismiss = { showActions = false }) {
            if (n != null) {
                LightSheetAction(if (n.pinned) "Unpin" else "Pin to top") {
                    vm.togglePinned(n)
                    showActions = false
                }
                LightSheetAction("Move to folder") {
                    moving = true
                    showActions = false
                }
                LightSheetAction(
                    label = "Put it on the calendar",
                    sub = "Keeps the note as well",
                ) {
                    makingEvent = true
                    showActions = false
                }
                if (n.imagePath != null) {
                    LightSheetAction("See the photo", sub = "The page this was read off") {
                        showPhoto = true
                        showActions = false
                    }
                }
                LightSheetAction("Delete note") {
                    vm.deleteNote(n.id)
                    showActions = false
                    onBack()
                }
            }
        }
    }

    // Turning a note into an event is two questions — which day, then what time — because a
    // note does not carry either, and guessing at them would be worse than asking.
    if (makingEvent) {
        LightActionSheet(heading = "WHICH DAY", onDismiss = { makingEvent = false }) {
            LightSheetAction("Today") {
                eventDay = NoteDates.today()
                makingEvent = false
            }
            LightSheetAction("Tomorrow") {
                eventDay = NoteDates.today() + 1
                makingEvent = false
            }
            LightSheetAction("Another day", sub = "Type the date") {
                pickingDate = true
                makingEvent = false
            }
        }
    }

    if (pickingDate) {
        LightNameSheet(
            title = "WHICH DAY · YYYY-MM-DD",
            initial = NoteDates.isoDate(NoteDates.today()),
            confirmLabel = "NEXT",
            onConfirm = { typed ->
                eventDay = NoteDates.parseIsoDate(typed)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }

    eventDay?.let { day ->
        val n = note
        LightNameSheet(
            title = "WHAT TIME · BLANK FOR ALL DAY",
            initial = "",
            confirmLabel = "ADD",
            allowBlank = true,
            onConfirm = { typed ->
                if (n != null) {
                    vm.noteToEvent(n, day, NoteDates.parseClock(typed)) { }
                }
                eventDay = null
            },
            onDismiss = { eventDay = null },
        )
    }

    if (showPhoto) {
        PhotoSheet(path = note?.imagePath, onDismiss = { showPhoto = false })
    }

    if (moving) {
        LightActionSheet(heading = "MOVE TO", onDismiss = { moving = false }) {
            LightSheetAction("All Notes", sub = "No folder") {
                vm.moveNote(noteId, null)
                moving = false
            }
            folders.forEach { folder ->
                LightSheetAction(folder.name) {
                    vm.moveNote(noteId, folder.id)
                    moving = false
                }
            }
        }
    }
}
