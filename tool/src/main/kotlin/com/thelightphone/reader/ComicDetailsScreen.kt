package com.thelightphone.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.comics.ComicDetails
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPosition
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.ComicStore
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The comic's details, saved place and status, once read. */
data class ComicDetailsState(val details: ComicDetails, val position: ComicPosition?, val status: ReadingStatus)

class ComicDetailsViewModel(
    private val meta: ComicMeta,
    private val store: ComicStore,
    private val positionRepository: ComicPositionRepository,
    private val statusRepository: ReadingStatusRepository,
) : LightViewModel<Boolean>() {

    private val slug = comicSlug(meta.path)

    /** Null until read (a moment at most). */
    private val _state = MutableStateFlow<ComicDetailsState?>(null)
    val state: StateFlow<ComicDetailsState?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val details = withContext(Dispatchers.IO) { store.details(meta) }
            val (position, status) = DatabaseQueue.read { positionRepository.get(slug) to statusRepository.get(slug) }
            _state.value = ComicDetailsState(details, position, status)
        }
    }

    /** Shows the new status at once and saves it (the save finishes even if the screen closes). */
    fun changeStatus(newStatus: ReadingStatus) {
        _state.value = _state.value?.copy(status = newStatus)
        DatabaseQueue.write { statusRepository.set(slug, newStatus) }
    }
}

/**
 * Facts about one comic, reached from its screen's DETAILS button (CLAUDE.md 12): series and
 * issue as the title, then the story title, date, creators, publisher, summary and the notes
 * from the file name (each left out when unknown), its reading status (the one thing that
 * can be changed here), then pages, progress and file details. REMOVE FROM PHONE at the
 * bottom asks first; once the comic is removed this hands back true, so the viewer closes too.
 */
class ComicDetailsScreen(
    sealedActivity: SealedLightActivity,
    private val meta: ComicMeta,
    private val title: String,
) : LightScreen<Boolean, ComicDetailsViewModel>(sealedActivity) {

    private val store = ComicStore(lightContext.filesDir)

    override val viewModelClass: Class<ComicDetailsViewModel>
        get() = ComicDetailsViewModel::class.java

    override fun createViewModel() = ComicDetailsViewModel(
        meta,
        store,
        ComicPositionRepository.getInstance { lightContext.readerDatabase() },
        ReadingStatusRepository.getInstance { lightContext.readerDatabase() },
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Details"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            val loaded = state
            if (loaded == null) Spacer(modifier = Modifier.weight(1f)) else DetailsList(loaded, Modifier.weight(1f))
            LightBottomBar(items = listOf(LightBarButton.Text(text = "REMOVE FROM PHONE", onClick = ::openRemove)))
        }
    }

    @Composable
    private fun DetailsList(loaded: ComicDetailsState, modifier: Modifier) {
        val rows = comicDetailRows(meta, loaded.position, loaded.status)
        LightScrollView(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            AboutTheComic(loaded.details, title)
            HairlineDivider()
            StatusChoices(current = loaded.status, onSelect = viewModel::changeStatus)
            HairlineDivider()
            rows.forEachIndexed { i, row ->
                DetailRowView(row)
                if (i != rows.lastIndex) HairlineDivider()
            }
        }
    }

    private fun openRemove() {
        val heading = viewModel.state.value?.details?.let { comicHeading(it, title) } ?: title
        navigateTo(
            screenFactory = {
                RemoveScreen(
                    it,
                    name = heading,
                    measure = { itemRemovalQuestion(heading, store.removal(meta.path).bytes) },
                    remove = { store.remove(meta.path) },
                )
            },
            resultCallback = { removed -> if (removed) goBack(true) },
        )
    }
}

/**
 * The top of Comic Details: "Action Comics #1" (Subheading), the story title, "June 1938"
 * (lighter), then Writer / Artists / Publisher rows, the summary, the notes, and where all
 * this came from (lighter, small). Anything not known is left out.
 */
@Composable
private fun AboutTheComic(details: ComicDetails, fallbackTitle: String) {
    Column(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp())) {
        LightText(text = comicHeading(details, fallbackTitle), variant = LightTextVariant.Subheading)
        comicStoryTitle(details)?.let { LightText(text = it, variant = LightTextVariant.Copy) }
        comicDateText(details)?.let { LightText(text = it, variant = LightTextVariant.Copy, lighten = true) }
    }
    val credits = comicCreditRows(details)
    credits.forEach { row ->
        HairlineDivider()
        DetailRowView(row)
    }
    details.summary?.let { summary ->
        HairlineDivider()
        LightText(
            text = summary,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
        )
    }
    if (details.notes.isNotEmpty()) {
        HairlineDivider()
        Column(modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp())) {
            LightText(text = "Notes", variant = LightTextVariant.Copy, lighten = true)
            details.notes.forEach { LightText(text = it, variant = LightTextVariant.Copy) }
        }
    }
    LightText(
        text = comicDetailsSourceText(details),
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), bottom = 0.75f.gridUnitsAsDp()),
    )
}
