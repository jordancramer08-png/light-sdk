package com.thelightphone.adventure

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.adventure.engine.ZStatusLine
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard

/** Plays one game: status band on top, story in the middle, input line at the bottom. */
class GameScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val loadStory: () -> ByteArray,
) :
    LightScreen<Unit, GameViewModel>(sealedActivity) {

    override val viewModelClass = GameViewModel::class.java

    override fun createViewModel() =
        GameViewModel(
            loadStory = loadStory,
            dataFolder = lightContext.filesDir,
            autosaveEnabled = { AppSettings.latest.autosave },
        )

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        GameKeys.isIgnored(keyCode)

    @Composable
    override fun Content() {
        val settings = rememberAppSettings(lightContext.dataStore)
        LightTheme(colors = AdventureTheme.lightColors) {
            Column(Modifier.fillMaxSize().background(AdventureTheme.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                    rightButton = LightBarButton.Text(
                        text = "Menu",
                        onClick = { viewModel.typing.togglePanel(GamePanel.MENU) },
                    ),
                )
                GameBody(
                    game = viewModel,
                    settings = settings,
                    onSettings = { navigateTo({ SettingsScreen(it) }) },
                    onLeave = { goBack() },
                )
            }
        }
    }
}

@Composable
private fun GameBody(
    game: GameViewModel,
    settings: AppSettings,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
) {
    val state by game.state.collectAsState()
    val picker by game.picker.collectAsState()
    val typing by game.typing.state.collectAsState()
    var keyboardOpen by remember { mutableStateOf(false) }
    // Sending closes the keyboard, because while it is open the story has no room to show the reply.
    val submitAndClose = { game.submit(); keyboardOpen = false }
    val sendAndClose = { command: String -> game.send(command); keyboardOpen = false }

    val problem = state.problem
    if (problem != null) {
        ProblemPanel(problem, onLeave)
        return
    }
    Column(Modifier.fillMaxSize()) {
        state.status?.let { StatusBar(it) }
        if (state.upperRows.isNotEmpty()) UpperBand(state.upperRows)
        val openPicker = picker
        if (openPicker != null) {
            SlotPanel(openPicker, game::pickSlot, game::cancelPicker, Modifier.weight(1f))
        } else {
            MiddleArea(game, state, typing, settings.textSize, keyboardOpen, onSettings, onLeave) {
                keyboardOpen = false
            }
        }
        if (settings.quickRow) QuickRow(state.waitingForInput, sendAndClose)
        VerbOrSuggestionRow(typing.suggestions, game.typing::addWord, game.typing::complete)
        InputLine(
            typed = typing.text,
            state = state,
            textSize = settings.textSize,
            onTap = { keyboardOpen = true },
            onHistory = { game.typing.togglePanel(GamePanel.HISTORY) },
            onSend = submitAndClose,
        )
        if (keyboardOpen) GameKeyboard(game, submitAndClose)
    }
}

/** A plain message in place of the game, with the way back to the shelf. */
@Composable
private fun ProblemPanel(message: String, onLeave: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        BasicText(message, style = TextStyle(color = AdventureTheme.storyText, fontSize = 18.sp))
        BasicText(
            text = "Back to shelf",
            style = TextStyle(color = AdventureTheme.quickText, fontSize = 18.sp),
            modifier = Modifier.clickable(onClick = onLeave).padding(vertical = 18.dp),
        )
    }
}

/** The story, or the history or menu in its place. */
@Composable
private fun ColumnScope.MiddleArea(
    game: GameViewModel,
    state: GameUiState,
    typing: TypingState,
    textSize: Int,
    keyboardOpen: Boolean,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
    onCloseKeyboard: () -> Unit,
) {
    val fill = Modifier.weight(1f)
    when (typing.panel) {
        GamePanel.HISTORY -> HistoryPanel(typing.history, textSize, game.typing::pickFromHistory, fill)
        GamePanel.MENU -> MenuPanel(
            onSave = { game.openPicker(SlotMode.SAVE) },
            onRestore = { game.openPicker(SlotMode.RESTORE) },
            onUndo = game::undo,
            onRestart = game::restart,
            onSettings = onSettings,
            onLeave = onLeave,
            modifier = fill,
        )
        GamePanel.NONE -> StoryText(
            lines = state.lines,
            textSize = textSize,
            keyboardOpen = keyboardOpen,
            onWord = game.typing::addWord,
            onTap = onCloseKeyboard,
            modifier = fill,
        )
    }
}

@Composable
private fun StatusBar(status: ZStatusLine) {
    val style = TextStyle(color = AdventureTheme.statusText, fontSize = 15.sp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdventureTheme.statusBackground)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = status.location,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BasicText(text = statusSummary(status), style = style, maxLines = 1)
    }
}

/** The upper window, sized so all its columns fit across the screen. */
@Composable
private fun UpperBand(rows: List<TextLine>) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(AdventureTheme.statusBackground)) {
        val columnWidth = maxWidth / GameViewModel.UPPER_COLUMNS
        val fontSize = with(LocalDensity.current) { (columnWidth / MONOSPACE_WIDTH).toSp() }
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize)
        Column {
            rows.forEach { row ->
                BasicText(
                    text = styled(row) { AdventureTheme.statusText to AdventureTheme.statusBackground },
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun StoryText(
    lines: List<TextLine>,
    textSize: Int,
    keyboardOpen: Boolean,
    onWord: (String) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val family = LightThemeTokens.typography.paragraph.fontFamily
    val style = TextStyle(fontFamily = family, fontSize = textSize.sp, lineHeight = (textSize * 1.3f).sp)

    // Keeps the newest text in view: on new output, a new size, or the keyboard opening.
    LaunchedEffect(lines, textSize, keyboardOpen) {
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
    ) {
        itemsIndexed(lines) { _, line -> StoryLine(line, style, onWord, onTap) }
    }
}

/** One paragraph. A tap on a word adds it to the input line; a tap elsewhere is [onTap]. */
@Composable
private fun StoryLine(line: TextLine, style: TextStyle, onWord: (String) -> Unit, onTap: () -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = styled(line) { AdventureTheme.storyColorFor(it.kind) to Color.Transparent }
    BasicText(
        text = text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(text) {
            detectTapGestures { position ->
                val word = layout?.let { tappedWord(it, text.text, position) }
                if (word != null) onWord(word) else onTap()
            }
        },
    )
}

/** The word under a tap. Past the end of a line there is no word, whatever is nearest. */
private fun tappedWord(layout: TextLayoutResult, text: String, position: Offset): String? {
    val row = layout.getLineForVerticalPosition(position.y)
    if (position.x > layout.getLineRight(row)) return null
    return wordAt(text, layout.getOffsetForPosition(position))
}

/** Turns spans into drawable text. [themeColors] gives a span's text and background colors. */
private fun styled(line: TextLine, themeColors: (StorySpan) -> Pair<Color, Color>): AnnotatedString =
    buildAnnotatedString {
        line.forEach { span ->
            val (text, back) = themeColors(span)
            withStyle(AdventureTheme.spanStyle(span, text, back)) { append(span.text) }
        }
    }

@Composable
private fun InputLine(
    typed: String,
    state: GameUiState,
    textSize: Int,
    onTap: () -> Unit,
    onHistory: () -> Unit,
    onSend: () -> Unit,
) {
    val hint = when {
        state.finished -> "The game has ended"
        state.waitingForInput -> "Tap to type"
        else -> "The game is working..."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdventureTheme.inputBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = typed.ifEmpty { hint },
            style = TextStyle(
                color = if (typed.isEmpty()) AdventureTheme.hintText else AdventureTheme.commandText,
                fontSize = textSize.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onTap),
        )
        BasicText(
            text = "HISTORY",
            style = TextStyle(color = AdventureTheme.quickText, fontSize = 16.sp),
            modifier = Modifier.clickable(onClick = onHistory).padding(start = 14.dp),
        )
        BasicText(
            text = "SEND",
            style = TextStyle(
                color = if (state.waitingForInput) AdventureTheme.commandText else AdventureTheme.hintText,
                fontSize = 16.sp,
            ),
            modifier = Modifier.clickable(onClick = onSend).padding(start = 14.dp),
        )
    }
}

@Composable
private fun GameKeyboard(game: GameViewModel, onReturn: () -> Unit) {
    val options = rememberKeyboardOptions()
    val latestOnReturn by rememberUpdatedState(onReturn)
    val keyboard = remember(game) {
        EnQwertyLp3KeyboardViewModel<Unit>(
            InputLineKeys(game.typing) { latestOnReturn() },
            keyboardOptionsFlow = options,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        )
    }
    LightEmbeddedLp3Keyboard(viewModel = keyboard)
}

/** A monospace character is about this wide, as a fraction of its font size. */
private const val MONOSPACE_WIDTH = 0.6f
