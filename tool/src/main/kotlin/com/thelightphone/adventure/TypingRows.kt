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

@Composable
fun SettingsPanel(
    textSize: Int,
    quickRowShown: Boolean,
    onNextTextSize: () -> Unit,
    onToggleQuickRow: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        SettingRow("Text size", textSize.toString(), onNextTextSize)
        SettingRow("Quick buttons", if (quickRowShown) "On" else "Off", onToggleQuickRow)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(label, style = TextStyle(color = AdventureTheme.storyText, fontSize = 18.sp))
        BasicText(value, style = TextStyle(color = AdventureTheme.commandText, fontSize = 18.sp))
    }
}
