package com.thelightphone.adventure

import android.os.Environment
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import java.io.File

/** The shelf: every game, with Continue at the top. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ShelfViewModel>(sealedActivity) {

    override val viewModelClass = ShelfViewModel::class.java

    override fun createViewModel() = ShelfViewModel(
        readAsset = lightContext::readAsset,
        gamesFolder = File(Environment.getExternalStorageDirectory(), "Adventure/Games"),
        dataFolder = lightContext.filesDir,
        hasStorageAccess = { Environment.isExternalStorageManager() },
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        GameKeys.isIgnored(keyCode)

    private fun open(entry: ShelfEntry) {
        viewModel.markPlayed(entry)
        val bytes = viewModel.storyBytes(entry)
        navigateTo({ GameScreen(it, entry.title, bytes) })
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LightTheme(colors = AdventureTheme.lightColors) {
            val renaming = state.renaming
            if (renaming != null) RenameEditor(renaming) else ShelfList(state)
        }
    }

    @Composable
    private fun RenameEditor(entry: ShelfEntry) {
        LightTextInputEditor(
            title = "Rename",
            state = rememberTextFieldState(entry.title),
            onSubmit = { viewModel.finishRename(it.toString()) },
            onBack = viewModel::cancelRename,
            keyboardOptionsFlow = rememberKeyboardOptions(),
            submitLabel = "SAVE",
            singleLine = true,
        )
    }

    @Composable
    private fun ShelfList(state: ShelfState) {
        Column(Modifier.fillMaxSize().background(AdventureTheme.background)) {
            LightTopBar(
                center = LightTopBarCenter.Text("Adventure"),
                rightButton = LightBarButton.Text(
                    text = "Licenses",
                    onClick = { navigateTo({ LicensesScreen(it) }) },
                ),
            )
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                state.continueGame?.let { game ->
                    item { ContinueButton(game) { open(game) } }
                }
                items(state.entries, key = { it.key }) { entry -> GameRow(entry, state.removing) }
                item { ShelfNotes(state) }
            }
        }
    }

    @Composable
    private fun GameRow(entry: ShelfEntry, removing: ShelfEntry?) {
        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            Column(Modifier.fillMaxWidth().clickable { open(entry) }) {
                Line(entry.title, AdventureTheme.storyText, 20)
                Line(shelfDetail(entry), AdventureTheme.hintText, 14)
            }
            if (removing == entry) {
                Line("Remove this game and its saves?", AdventureTheme.statusText, 15)
                Actions("Yes, remove" to viewModel::confirmRemove, "Cancel" to viewModel::cancelRemove)
            } else {
                val rename = "Rename" to { viewModel.startRename(entry) }
                val remove = "Remove" to { viewModel.askToRemove(entry) }
                if (entry.removable) Actions(rename, remove) else Actions(rename)
            }
        }
    }
}

@Composable
private fun ContinueButton(game: ShelfEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AdventureTheme.statusBackground)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Line("Continue", AdventureTheme.statusText, 20)
        Line(game.title, AdventureTheme.storyText, 15)
    }
}

@Composable
private fun Line(text: String, color: Color, size: Int) {
    BasicText(text, style = TextStyle(color = color, fontSize = size.sp))
}

@Composable
private fun Actions(vararg actions: Pair<String, () -> Unit>) {
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        actions.forEach { (label, onClick) ->
            BasicText(
                text = label,
                style = TextStyle(color = AdventureTheme.quickText, fontSize = 15.sp),
                modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ShelfNotes(state: ShelfState) {
    Column(Modifier.padding(vertical = 10.dp)) {
        state.message?.let { Line(it, AdventureTheme.statusText, 15) }
        if (state.needsStorageAccess) {
            Line("Added games need storage access. Use option 4 in Adventure Games.cmd.", AdventureTheme.hintText, 14)
        }
        if (state.unreadable.isNotEmpty()) {
            Line("Could not read: " + state.unreadable.joinToString(), AdventureTheme.hintText, 14)
        }
    }
}
