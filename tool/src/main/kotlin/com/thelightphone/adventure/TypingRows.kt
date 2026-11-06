package com.thelightphone.adventure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tappable label in a sideways-scrolling row. */
@Composable
private fun Chip(label: String, color: Color, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(color = color, fontSize = 16.sp),
        modifier = Modifier
            .background(AdventureTheme.buttonBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, color: Color, onClick: (T) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(AdventureTheme.inputBackground).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(items) { item -> Chip(label(item), color) { onClick(item) } }
    }
}

/** Directions and common commands. A tap sends the command at once. */
@Composable
fun QuickRow(enabled: Boolean, onSend: (String) -> Unit) {
    val color = if (enabled) AdventureTheme.quickText else AdventureTheme.hintText
    ChipRow(QuickButtons.commands, { it.label }, color) { onSend(it.command) }
}

/** Verbs, or word suggestions in their place while a word is being typed. */
@Composable
fun VerbOrSuggestionRow(suggestions: List<String>, onVerb: (String) -> Unit, onSuggestion: (String) -> Unit) {
    if (suggestions.isEmpty()) {
        ChipRow(QuickButtons.verbs, { it }, AdventureTheme.verbText, onVerb)
    } else {
        ChipRow(suggestions, { it }, AdventureTheme.suggestionText, onSuggestion)
    }
}

/** The last commands, newest first. A tap fills the input line. */
@Composable
fun HistoryPanel(history: List<String>, textSize: Int, onPick: (String) -> Unit, modifier: Modifier) {
    val style = TextStyle(color = AdventureTheme.commandText, fontSize = textSize.sp)
    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        if (history.isEmpty()) {
            item { BasicText("No commands yet", style = style.copy(color = AdventureTheme.hintText)) }
        }
        items(history) { command ->
            BasicText(
                text = command,
                style = style,
                modifier = Modifier.fillMaxWidth().clickable { onPick(command) }.padding(vertical = 9.dp),
            )
        }
    }
}

/** The game menu. Restart needs a second tap, because it throws the current game away. */
@Composable
fun MenuPanel(
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier,
) {
    var restartArmed by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        MenuRow("Save", onSave)
        MenuRow("Restore", onRestore)
        MenuRow("Undo", onUndo)
        MenuRow(if (restartArmed) "Tap again to restart" else "Restart") {
            if (restartArmed) onRestart() else restartArmed = true
        }
        MenuRow("Settings", onSettings)
        MenuRow("Back to shelf", onLeave)
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(color = AdventureTheme.storyText, fontSize = 18.sp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

/** The five slots, each with its label and time. */
@Composable
fun SlotPanel(picker: SlotPicker, onPick: (Int) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val title = if (picker.mode == SlotMode.SAVE) "Save to which slot?" else "Restore which slot?"
    LazyColumn(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        item { BasicText(title, style = TextStyle(color = AdventureTheme.statusText, fontSize = 18.sp)) }
        items(picker.slots) { slot -> SlotRow(slot, picker.confirming == slot.number) { onPick(slot.number) } }
        item { MenuRow("Cancel", onCancel) }
    }
}

@Composable
private fun SlotRow(slot: SlotInfo, confirming: Boolean, onClick: () -> Unit) {
    val time = slot.savedAt?.let(::slotTime) ?: ""
    val detail = if (confirming) "Tap again to overwrite" else time
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp)) {
        BasicText(
            text = "${slot.number}. ${slot.label}",
            style = TextStyle(color = AdventureTheme.storyText, fontSize = 17.sp),
        )
        if (detail.isNotEmpty()) {
            val color = if (confirming) AdventureTheme.statusText else AdventureTheme.hintText
            BasicText(detail, style = TextStyle(color = color, fontSize = 14.sp))
        }
    }
}

@Composable
fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(label, style = TextStyle(color = AdventureTheme.storyText, fontSize = 18.sp))
        BasicText(value, style = TextStyle(color = AdventureTheme.commandText, fontSize = 18.sp))
    }
}
