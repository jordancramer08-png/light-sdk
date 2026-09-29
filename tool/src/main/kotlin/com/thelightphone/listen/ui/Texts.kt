package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** One line of text; a long name ends in "…" instead of wrapping. */
@Composable
fun OneLine(text: String, variant: LightTextVariant, lighten: Boolean = false, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = variant,
        lighten = lighten,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A lighter, centered message filling the space it's given (an empty list, no access). */
@Composable
fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/** The small "Updating library…" line under a list while new music is being read. */
@Composable
fun UpdatingLine(visible: Boolean) {
    if (!visible) return
    LightText(
        text = "Updating library…",
        variant = LightTextVariant.Detail,
        lighten = true,
        align = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}
