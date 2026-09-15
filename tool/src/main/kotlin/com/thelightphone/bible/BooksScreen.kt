package com.thelightphone.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.bible.data.BibleManifest
import com.thelightphone.bible.data.BibleManifestBook
import com.thelightphone.bible.data.BibleRepository
import com.thelightphone.bible.data.BookNameResolver
import com.thelightphone.bible.data.ReadingPlanRepository
import com.thelightphone.bible.data.TranslationRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val NEW_TESTAMENT_FIRST_BOOK = "Matthew"

sealed interface BooksScreenState {
    data object Loading : BooksScreenState
    data object Unavailable : BooksScreenState
    data class Loaded(
        val translation: String,
        val oldTestament: List<BibleManifestBook>,
        val newTestament: List<BibleManifestBook>,
    ) : BooksScreenState
}

/**
 * All 66 books under Old/New Testament headings (CLAUDE.md 9), in the reading plan's
 * canonical order rather than the manifest map's iteration order. Re-reads the persisted
 * translation on every visit, so returning here after TranslationScreen shows that
 * translation's books without either screen needing to notify the other.
 */
class BooksScreenViewModel(
    private val bibleRepository: BibleRepository,
    private val readingPlanRepository: ReadingPlanRepository,
    private val translationRepository: TranslationRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<BooksScreenState>(BooksScreenState.Loading)
    val state: StateFlow<BooksScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val translation = translationRepository.selectedTranslation()
            val plan = readingPlanRepository.loadPlan()
            val manifest = bibleRepository.loadManifest(translation)
            if (plan == null || manifest == null) {
                _state.value = BooksScreenState.Unavailable
                return@launch
            }

            val resolver = BookNameResolver(plan)
            val newTestamentStart = plan.canonicalBookOrder.indexOf(NEW_TESTAMENT_FIRST_BOOK)
                .let { if (it >= 0) it else plan.canonicalBookOrder.size }

            _state.value = BooksScreenState.Loaded(
                translation = translation,
                oldTestament = resolveBooks(plan.canonicalBookOrder.take(newTestamentStart), manifest, resolver),
                newTestament = resolveBooks(plan.canonicalBookOrder.drop(newTestamentStart), manifest, resolver),
            )
        }
    }

    private fun resolveBooks(
        names: List<String>,
        manifest: BibleManifest,
        resolver: BookNameResolver,
    ): List<BibleManifestBook> = names.mapNotNull { name -> bibleRepository.findBook(manifest, resolver, name) }
}

class BooksScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, BooksScreenViewModel>(sealedActivity) {

    private val bibleRepository = BibleRepository(lightContext.fileShare)
    private val readingPlanRepository = ReadingPlanRepository(lightContext.fileShare)
    private val translationRepository =
        TranslationRepository(lightContext.fileShare, bibleRepository, lightContext.dataStore)

    override val viewModelClass: Class<BooksScreenViewModel>
        get() = BooksScreenViewModel::class.java

    override fun createViewModel() =
        BooksScreenViewModel(bibleRepository, readingPlanRepository, translationRepository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Books"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    BooksScreenState.Loading -> Unit
                    BooksScreenState.Unavailable -> EmptyMessage("No translation installed yet.")
                    is BooksScreenState.Loaded -> BookSections(
                        oldTestament = current.oldTestament,
                        newTestament = current.newTestament,
                        onSelect = { book ->
                            navigateTo(screenFactory = { ChaptersScreen(it, book, current.translation) })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSections(
    oldTestament: List<BibleManifestBook>,
    newTestament: List<BibleManifestBook>,
    onSelect: (BibleManifestBook) -> Unit,
) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        BookSection(title = "OLD TESTAMENT", books = oldTestament, onSelect = onSelect)
        BookSection(title = "NEW TESTAMENT", books = newTestament, onSelect = onSelect)
    }
}

@Composable
private fun BookSection(
    title: String,
    books: List<BibleManifestBook>,
    onSelect: (BibleManifestBook) -> Unit,
) {
    if (books.isEmpty()) return

    LightText(
        text = title,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
    books.forEach { book ->
        LightText(
            text = book.name,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onSelect(book) }
                .padding(vertical = 0.6f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(
        modifier = Modifier
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
