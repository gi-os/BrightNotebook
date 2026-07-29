package com.gios.lightnotebook.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.data.FolderEntity
import com.gios.lightnotebook.data.NoteEntity
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.NoteMarkdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    vm: NotebookViewModel,
    onOpenNote: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val counts by vm.noteCounts.collectAsStateWithLifecycle()
    val filter by vm.folderFilter.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<NoteEntity?>(null) }
    var movingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var folderActions by remember { mutableStateOf<FolderEntity?>(null) }
    var renaming by remember { mutableStateOf<FolderEntity?>(null) }

    val visible = remember(notes, query) {
        val q = query.trim()
        if (q.isEmpty()) notes else notes.filter {
            it.title.contains(q, true) || it.body.contains(q, true)
        }
    }
    val pinned = visible.filter { it.pinned }
    val rest = visible.filterNot { it.pinned }
    val folderName = folders.firstOrNull { it.id == filter }?.name

    Column(modifier.fillMaxSize()) {
        LightTopBar(
            title = folderName?.uppercase() ?: "NOTES",
            left = LightBarItem.Icon(
                icon = if (searching) LightIcons.Close else LightIcons.Search,
                sizeUnits = 1.6f,
                onClick = {
                    searching = !searching
                    if (!searching) query = ""
                },
            ),
            right = LightBarItem.Icon(
                icon = LightIcons.Settings,
                sizeUnits = 1.6f,
                onClick = onSettings,
            ),
        )

        if (searching) {
            LightInlineField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search notes",
                autoFocus = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = lightInset(), vertical = 0.4f.verticalGridUnitsAsDp()),
            )
        }

        // Folders live in one scrolling row rather than a screen of their own: switching
        // folder is something you do while reading, not a place you go.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = lightInset(), vertical = 0.5f.verticalGridUnitsAsDp()),
            horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
        ) {
            LightChip("ALL ${counts.values.sum()}", filter == null) { vm.selectFolder(null) }
            folders.forEach { folder ->
                LightChip(
                    label = "${folder.name.uppercase()} ${counts[folder.id] ?: 0}",
                    selected = filter == folder.id,
                ) {
                    // Tapping the folder you are already in is how you get at its actions.
                    if (filter == folder.id) folderActions = folder else vm.selectFolder(folder.id)
                }
            }
            LightChip("+ FOLDER", false) { newFolder = true }
        }
        LightRule()

        if (visible.isEmpty()) {
            LightEmptyState(
                when {
                    query.isNotBlank() -> "Nothing matches that."
                    filter != null -> "This folder is empty.\nADD puts a note in it."
                    else -> "No notes yet.\nTap ADD to write one,\nor to photograph a page."
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (pinned.isNotEmpty()) {
                    item { LightSectionLabel("PINNED") }
                    items(pinned, key = { it.id }) { note ->
                        NoteRow(note, onOpenNote) { actionsFor = note }
                        LightRule()
                    }
                    if (rest.isNotEmpty()) item { LightSectionLabel("NOTES") }
                }
                items(rest, key = { it.id }) { note ->
                    NoteRow(note, onOpenNote) { actionsFor = note }
                    LightRule()
                }
            }
        }
    }

    /* ---- sheets ---- */

    actionsFor?.let { note ->
        LightActionSheet(
            heading = NoteMarkdown.firstLine(note.title.ifBlank { note.body }, 34)
                .ifBlank { "UNTITLED" }
                .uppercase(),
            onDismiss = { actionsFor = null },
        ) {
            LightSheetAction(if (note.pinned) "Unpin" else "Pin to top") {
                vm.togglePinned(note)
                actionsFor = null
            }
            LightSheetAction("Move to folder") {
                movingNote = note
                actionsFor = null
            }
            LightSheetAction("Delete note") {
                vm.deleteNote(note.id)
                actionsFor = null
            }
        }
    }

    movingNote?.let { note ->
        LightActionSheet(heading = "MOVE TO", onDismiss = { movingNote = null }) {
            LightSheetAction("All Notes", sub = "No folder") {
                vm.moveNote(note.id, null)
                movingNote = null
            }
            folders.forEach { folder ->
                LightSheetAction(folder.name) {
                    vm.moveNote(note.id, folder.id)
                    movingNote = null
                }
            }
            if (folders.isEmpty()) {
                LightText(
                    "No folders yet — make one with + FOLDER.",
                    LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(
                        horizontal = lightInset(),
                        vertical = 1f.verticalGridUnitsAsDp(),
                    ),
                )
            }
        }
    }

    folderActions?.let { folder ->
        LightActionSheet(heading = folder.name.uppercase(), onDismiss = { folderActions = null }) {
            LightSheetAction("Rename folder") {
                renaming = folder
                folderActions = null
            }
            LightSheetAction("Delete folder", sub = "The notes inside are kept") {
                vm.deleteFolder(folder.id)
                folderActions = null
            }
        }
    }

    if (newFolder) {
        LightNameSheet(
            title = "NEW FOLDER",
            initial = "",
            onConfirm = { vm.createFolder(it); newFolder = false },
            onDismiss = { newFolder = false },
        )
    }

    renaming?.let { folder ->
        LightNameSheet(
            title = "RENAME FOLDER",
            initial = folder.name,
            onConfirm = { vm.renameFolder(folder, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun NoteRow(note: NoteEntity, onOpen: (String) -> Unit, onLongPress: () -> Unit) {
    val title = note.title.ifBlank { NoteMarkdown.firstLine(note.body) }
    LightListRow(
        title = title.ifBlank { "Untitled" },
        sub = NoteMarkdown.preview(note.body).ifBlank { "Empty" },
        detail = NoteDates.shortDate(note.updatedAt / 86_400_000L),
        leading = if (note.pinned) LightIcons.Star else null,
        onClick = { onOpen(note.id) },
        onLongClick = onLongPress,
    )
}

/** A sheet of row actions, headed by the thing being acted on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightActionSheet(
    heading: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LightThemeTokens.colors.background,
        dragHandle = null,
    ) {
        Column(Modifier.padding(bottom = 1.5f.verticalGridUnitsAsDp())) {
            LightText(
                text = heading,
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(
                    start = lightInset(),
                    top = 1.2f.verticalGridUnitsAsDp(),
                    bottom = 0.4f.verticalGridUnitsAsDp(),
                ),
            )
            LightRule()
            content()
        }
    }
}
