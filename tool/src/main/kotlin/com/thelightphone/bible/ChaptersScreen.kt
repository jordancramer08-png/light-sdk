package com.thelightphone.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.bible.data.BibleManifestBook
import com.thelightphone.bible.data.DEFAULT_TRANSLATION
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
import com.thelightphone.sdk.ui.lightClickable

/** The chapter numbers for [book] (CLAUDE.md 9). Tapping one opens [ChapterScreen]. */
class ChaptersScreen(
    sealedActivity: SealedLightActivity,
    private val book: BibleManifestBook,
    private val translation: String = DEFAULT_TRANSLATION,
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
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(book.name),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                ChapterList(
                    chapterCount = book.chapterCount,
                    onSelect = { chapter ->
                        navigateTo(screenFactory = { ChapterScreen(it, translation, book, chapter) })
                    },
                )
            }
        }
    }
}

@Composable
private fun ChapterList(chapterCount: Int, onSelect: (Int) -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        for (chapter in 1..chapterCount) {
            LightText(
                text = "Chapter $chapter",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(chapter) }
                    .padding(vertical = 0.6f.gridUnitsAsDp()),
            )
        }
    }
}
