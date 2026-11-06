package com.thelightphone.adventure

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A game the tool ships with. [asset] is its file in tool/src/main/assets. */
enum class BundledGame(val title: String, val asset: String) {
    ZORK1("Zork I", "zork1.dat"),
    ZORK2("Zork II", "zork2.dat"),
    ZORK3("Zork III", "zork3.dat"),
}

private val TEXT_SIZE_KEY = intPreferencesKey("text_size")

/** Plays one game: status band on top, story in the middle, input line at the bottom. */
class GameScreen(sealedActivity: SealedLightActivity, private val game: BundledGame) :
    LightScreen<Unit, GameViewModel>(sealedActivity) {

    override val viewModelClass = GameViewModel::class.java

    override fun createViewModel() =
        GameViewModel(lightContext.readAsset(game.asset), lightContext.filesDir, game.name.lowercase())

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        GameKeys.isIgnored(keyCode)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val store = lightContext.dataStore
        val savedSize by remember { store.data.map { it[TEXT_SIZE_KEY] ?: TextSizes.DEFAULT } }
            .collectAsState(TextSizes.DEFAULT)
        val scope = rememberCoroutineScope()

        LightTheme(colors = AdventureTheme.lightColors) {
            Column(Modifier.fillMaxSize().background(AdventureTheme.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(game.title),
                    rightButton = LightBarButton.Text(
                        text = "Aa",
                        contentDescription = "Change text size",
                        onClick = {
                            scope.launch { store.edit { it[TEXT_SIZE_KEY] = TextSizes.next(savedSize) } }
                        },
                    ),
                )
                GameBody(state, savedSize, viewModel::send)
            }
        }
    }
}

@Composable
private fun GameBody(state: GameUiState, textSize: Int, onSend: (String) -> Unit) {
    val input = rememberTextFieldState()
    var keyboardOpen by remember { mutableStateOf(false) }
    val send = {
        if (state.waitingForInput) {
            onSend(input.text.toString())
            input.clearText()
        }
    }

    Column(Modifier.fillMaxSize()) {
        state.status?.let { StatusBar(it) }
        if (state.upperRows.isNotEmpty()) UpperBand(state.upperRows)
        StoryText(
            lines = state.lines,
            textSize = textSize,
            keyboardOpen = keyboardOpen,
            onTap = { keyboardOpen = false },
            modifier = Modifier.weight(1f),
        )
        InputLine(input, state, textSize, onTap = { keyboardOpen = true }, onSend = send)
        if (keyboardOpen) GameKeyboard(input, send)
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
        itemsIndexed(lines) { _, line ->
            BasicText(
                text = styled(line) { AdventureTheme.storyColorFor(it.kind) to Color.Transparent },
                style = style,
            )
        }
    }
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
    input: TextFieldState,
    state: GameUiState,
    textSize: Int,
    onTap: () -> Unit,
    onSend: () -> Unit,
) {
    val hint = if (state.finished) "The game has ended" else "Tap to type"
    val typed = input.text.toString()
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
private fun GameKeyboard(input: TextFieldState, onReturn: () -> Unit) {
    val options = rememberKeyboardOptions()
    val latestOnReturn by rememberUpdatedState(onReturn) // The keyboard outlives each redraw.
    val keyboard = remember(input) {
        EnQwertyLp3KeyboardViewModel<Unit>(
            InputLineKeys(input) { latestOnReturn() },
            keyboardOptionsFlow = options,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        )
    }
    LightEmbeddedLp3Keyboard(viewModel = keyboard)
}

/** A monospace character is about this wide, as a fraction of its font size. */
private const val MONOSPACE_WIDTH = 0.6f
