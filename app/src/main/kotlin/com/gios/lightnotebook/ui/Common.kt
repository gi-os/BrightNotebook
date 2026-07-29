package com.gios.lightnotebook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightnotebook.ui.theme.LightIcon
import com.gios.lightnotebook.ui.theme.LightIconSpec
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.designVerticalPxToDp
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightCombinedClickable
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.lightTextStyle
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp

/**
 * A row in a list. Everything is a full-width row on this phone: it is the only shape
 * that stays tappable at a glance, and it means the eye only ever scans one column.
 */
@Composable
fun LightListRow(
    title: String,
    sub: String? = null,
    detail: String? = null,
    leading: LightIconSpec? = null,
    trailing: LightIconSpec? = null,
    lighten: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                when {
                    onClick != null && onLongClick != null ->
                        it.lightCombinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> it.lightClickable(onClick = onClick)
                    else -> it
                }
            }
            .heightIn(min = 3.2f.verticalGridUnitsAsDp())
            .padding(horizontal = lightInset(), vertical = 0.6f.verticalGridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            LightIcon(
                leading,
                size = 1.4f,
                modifier = Modifier.padding(end = 0.6f.gridUnitsAsDp()),
            )
        }
        Column(Modifier.weight(1f)) {
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
                lighten = lighten,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sub.isNullOrBlank()) {
                LightText(
                    text = sub,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (detail != null) {
            LightText(
                text = detail,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp()),
            )
        }
        if (trailing != null) {
            LightIcon(
                trailing,
                size = 1.2f,
                modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
fun LightSectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = lightInset(),
                top = 1f.verticalGridUnitsAsDp(),
                bottom = 0.3f.verticalGridUnitsAsDp(),
            ),
    )
}

@Composable
fun LightEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()), Alignment.Center) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/** Selection inverts rather than tints — the only state change that survives greyscale. */
@Composable
fun LightChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        modifier
            .height(2.2f.verticalGridUnitsAsDp())
            .background(if (selected) colors.content else colors.background)
            .border(1.dp, if (selected) colors.content else colors.rule)
            .lightClickable(onClick = onClick)
            .padding(horizontal = 0.8f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            color = if (selected) colors.background else colors.content,
            maxLines = 1,
        )
    }
}

/** A full-width action. Inverted, because it is the one thing to do on the screen. */
@Composable
fun LightWideButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(3.4f.verticalGridUnitsAsDp())
            .background(if (filled && enabled) colors.content else colors.background)
            .border(1.dp, if (enabled) colors.content else colors.rule)
            .lightClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Button,
            color = when {
                !enabled -> colors.rule
                filled -> colors.background
                else -> colors.content
            },
            maxLines = 1,
        )
    }
}

/**
 * A single line of editable text, underlined the way the SDK underlines its fields.
 * Material's own text fields bring a filled container and a floating label with them,
 * neither of which exists anywhere in LightOS.
 */
@Composable
fun LightInlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    variant: LightTextVariant = LightTextVariant.Copy,
    autoFocus: Boolean = false,
    underline: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    Column(modifier) {
        Box {
            if (value.isEmpty()) {
                LightText(placeholder, variant, lighten = true, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = lightTextStyle(variant).copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (underline) {
            Box(
                Modifier
                    .padding(top = 0.4f.verticalGridUnitsAsDp())
                    .fillMaxWidth()
                    .height(3f.designVerticalPxToDp())
                    .background(colors.content),
            )
        }
    }
}

/** Naming a folder is a one-field question, so it gets a sheet rather than a screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightNameSheet(
    title: String,
    initial: String,
    confirmLabel: String = "SAVE",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val colors = LightThemeTokens.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        dragHandle = null,
    ) {
        Column(
            Modifier.padding(
                horizontal = lightInset(),
                vertical = 1.2f.verticalGridUnitsAsDp(),
            ),
        ) {
            LightText(title, LightTextVariant.Superfine, lighten = true)
            LightInlineField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Name",
                autoFocus = true,
                modifier = Modifier.padding(top = 0.5f.verticalGridUnitsAsDp()),
                onDone = { if (text.isNotBlank()) onConfirm(text.trim()) },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 1.2f.verticalGridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(0.8f.gridUnitsAsDp()),
            ) {
                LightWideButton(
                    label = "CANCEL",
                    filled = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                LightWideButton(
                    label = confirmLabel,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = { onConfirm(text.trim()) },
                )
            }
        }
    }
}

/** One line in a bottom sheet of actions. */
@Composable
fun LightSheetAction(label: String, sub: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = lightInset(), vertical = 1f.verticalGridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(label, LightTextVariant.Copy)
        if (sub != null) LightText(sub, LightTextVariant.Detail, lighten = true)
    }
}
