package com.thelightphone.listen.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Listen is on screen. Set by every Listen screen when it shows and when Listen goes
 * to the background (which includes the screen turning off), so animations can stop.
 */
object AppVisibility {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun set(visible: Boolean) {
        _foreground.value = visible
    }
}

/** How fast a long line moves, and how long it rests at each end. */
private const val SCROLL_DP_PER_SECOND = 30f
private const val REST_MS = 2_000L

/**
 * One line of text for Now Playing and the now-playing bar. Text that fits stays still
 * ([centered] or at the start). Text that doesn't fit rests, slowly scrolls to its end,
 * rests about 2 seconds, scrolls back, and repeats — only while Listen is on screen.
 */
@Composable
fun ScrollingLine(
    text: String,
    variant: LightTextVariant,
    lighten: Boolean = false,
    centered: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val visible by AppVisibility.foreground.collectAsState()
    var boxWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember(text) { mutableIntStateOf(0) }
    val offset = remember(text) { Animatable(0f) }
    val overflow = if (boxWidth > 0) (textWidth - boxWidth).coerceAtLeast(0) else 0
    val pxPerSecond = with(LocalDensity.current) { SCROLL_DP_PER_SECOND.dp.toPx() }

    LaunchedEffect(text, overflow, visible) {
        offset.snapTo(0f)
        if (overflow <= 0 || !visible) return@LaunchedEffect
        val travelMs = ((overflow / pxPerSecond) * 1000).toInt().coerceAtLeast(400)
        while (true) {
            delay(REST_MS)
            offset.animateTo(overflow.toFloat(), tween(travelMs, easing = LinearEasing))
            delay(REST_MS)
            offset.animateTo(0f, tween(travelMs, easing = LinearEasing))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { boxWidth = it.width },
        contentAlignment = if (centered && overflow == 0) Alignment.Center else Alignment.CenterStart,
    ) {
        LightText(
            text = text,
            variant = variant,
            lighten = lighten,
            maxLines = 1,
            align = if (centered && overflow == 0) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                // Measured at its full length, however wide the line is.
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { textWidth = it.width }
                .graphicsLayer { translationX = -offset.value },
        )
    }
}
