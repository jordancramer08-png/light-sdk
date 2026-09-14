package com.thelightphone.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** The book's chapters. Reachable from the reading screen once it exists (CLAUDE.md 8). */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(bookMeta.title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                ChapterList(bookMeta = bookMeta)
            }
        }
    }
}

@Composable
private fun ChapterList(bookMeta: BookMeta) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        bookMeta.chapters.forEachIndexed { i, chapter ->
            LightText(
                text = chapter.title,
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
            if (i != bookMeta.chapters.lastIndex) {
                HairlineDivider()
            }
        }
    }
}
