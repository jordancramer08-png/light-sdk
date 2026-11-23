package com.thelightphone.listen.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/**
 * One letter of the A–Z bar: [target] is the list row it jumps to. A letter with no rows
 * ([present] false) is drawn dimmed and jumps to the next letter down the bar that has some.
 */
data class LetterSlot(val letter: Char, val target: Int, val present: Boolean)

/**
 * The A–Z bar for a list whose rows file under [letters] (one per row, in list order; null
 * for a row that files under no letter, such as "No Genre"). A–Z lists have "#" at the top,
 * then A to Z; Z–A lists ([descending]) run Z to A with "#" at the bottom, as the rows do.
 * Empty when no row has a letter.
 */
fun letterSlots(letters: List<Char?>, descending: Boolean): List<LetterSlot> {
    val first = HashMap<Char, Int>()
    letters.forEachIndexed { i, letter -> if (letter != null && letter !in first) first[letter] = i }
    if (first.isEmpty()) return emptyList()
    val az = ('A'..'Z').toList()
    val order = if (descending) az.reversed() + OTHER_LETTER else listOf(OTHER_LETTER) + az
    // Each missing letter borrows the next present letter's row (or, at the end, the last one).
    val targets = IntArray(order.size)
    var next = -1
    for (i in order.indices.reversed()) {
        first[order[i]]?.let { next = it }
        targets[i] = next
    }
    var previous = -1
    for (i in order.indices) {
        if (targets[i] < 0) targets[i] = previous else if (first[order[i]] != null) previous = targets[i]
    }
    return order.mapIndexed { i, letter -> LetterSlot(letter, targets[i], first[letter] != null) }
}

/** Width of the A–Z bar at the right of a list. */
private const val BAR_GRID_UNITS = 1.6f

/**
 * [MusicLazyList] with the A–Z bar down its right edge (when [letters] is given and the list
 * is longer than the screen). Tapping or sliding along the bar jumps the list; while a
 * finger is on it, the letter shows large in the middle.
 */
@Composable
fun IndexedMusicList(
    tag: Any,
    rowGridUnits: Float,
    letters: List<Char?>?,
    descending: Boolean,
    content: LazyListScope.() -> Unit,
) {
    key(tag) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val slots = remember(letters, descending) { letters?.let { letterSlots(it, descending) }.orEmpty() }
        val scrolls by remember { derivedStateOf { listState.canScrollForward || listState.canScrollBackward } }
        var touched by remember { mutableStateOf<Char?>(null) }
        val showBar = slots.isNotEmpty() && scrolls
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 1f.gridUnitsAsDp(), end = if (showBar) 0.25f.gridUnitsAsDp() else 1f.gridUnitsAsDp()),
                    listState = listState,
                    uniformItemHeightGridUnits = rowGridUnits,
                    content = content,
                )
                if (showBar) {
                    LetterBar(
                        slots = slots,
                        onTouch = { slot ->
                            touched = slot?.letter
                            if (slot != null && slot.target >= 0) scope.launch { listState.scrollToItem(slot.target) }
                        },
                    )
                }
            }
            touched?.let { BigLetter(it, Modifier.align(Alignment.Center)) }
        }
    }
}

/** The thin column of letters; [onTouch] gets the letter under the finger, then null when it lifts. */
@Composable
private fun LetterBar(slots: List<LetterSlot>, onTouch: (LetterSlot?) -> Unit) {
    val touch by rememberUpdatedState(onTouch)
    Column(
        modifier = Modifier
            .width(BAR_GRID_UNITS.gridUnitsAsDp())
            .fillMaxHeight()
            .pointerInput(slots) {
                awaitEachGesture {
                    var last: LetterSlot? = null
                    fun at(y: Float) {
                        val i = (y / size.height * slots.size).toInt().coerceIn(0, slots.lastIndex)
                        val slot = slots[i]
                        if (slot != last) {
                            last = slot
                            touch(slot)
                        }
                    }
                    val down = awaitFirstDown()
                    down.consume()
                    at(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        at(change.position.y)
                    }
                    touch(null)
                }
            },
    ) {
        for (slot in slots) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (slot.present) 1f else DIMMED_ALPHA),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = slot.letter.toString(), variant = LightTextVariant.Superfine, align = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** The touched letter, large, in a framed square in the middle of the list. */
@Composable
private fun BigLetter(letter: Char, modifier: Modifier) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .size(8f.gridUnitsAsDp())
            .background(colors.background)
            .border(1.dp, colors.contentSecondary),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = letter.toString(), variant = LightTextVariant.Title)
    }
}

private const val DIMMED_ALPHA = 0.3f
