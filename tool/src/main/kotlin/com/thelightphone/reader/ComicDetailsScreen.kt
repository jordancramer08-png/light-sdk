package com.thelightphone.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.comics.comicSlug
import com.thelightphone.reader.data.ComicMeta
import com.thelightphone.reader.data.ComicPosition
import com.thelightphone.reader.data.ComicPositionRepository
import com.thelightphone.reader.data.DatabaseQueue
import com.thelightphone.reader.data.ReadingStatusRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The comic's saved place and status, once read. */
data class ComicDetailsState(val position: ComicPosition?, val status: ReadingStatus)

class ComicDetailsViewModel(
    meta: ComicMeta,
    private val positionRepository: ComicPositionRepository,
    private val statusRepository: ReadingStatusRepository,
) : LightViewModel<Unit>() {

    private val slug = comicSlug(meta.path)

    /** Null until read (a moment at most). */
    private val _state = MutableStateFlow<ComicDetailsState?>(null)
    val state: StateFlow<ComicDetailsState?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = DatabaseQueue.read { ComicDetailsState(positionRepository.get(slug), statusRepository.get(slug)) }
        }
    }

    /** Shows the new status at once and saves it (the save finishes even if the screen closes). */
    fun changeStatus(newStatus: ReadingStatus) {
        _state.value = _state.value?.copy(status = newStatus)
        DatabaseQueue.write { statusRepository.set(slug, newStatus) }
    }
}

/**
 * Facts about one comic, reached from its screen's DETAILS button: title and folder at the
 * top, its reading status (the one thing that can be changed here), then pages, progress
 * and file details — like Book Details.
 */
class ComicDetailsScreen(
    sealedActivity: SealedLightActivity,
    private val meta: ComicMeta,
    private val title: String,
) : LightScreen<Unit, ComicDetailsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComicDetailsViewModel>
        get() = ComicDetailsViewModel::class.java

    override fun createViewModel() = ComicDetailsViewModel(
        meta,
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
            val loaded = state ?: return@ThemedScreen
            val rows = comicDetailRows(meta, loaded.position, loaded.status)
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                Column(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp())) {
                    LightText(text = title, variant = LightTextVariant.Subheading)
                }
                HairlineDivider()
                StatusChoices(current = loaded.status, onSelect = viewModel::changeStatus)
                HairlineDivider()
                rows.forEachIndexed { i, row ->
                    DetailRowView(row)
                    if (i != rows.lastIndex) HairlineDivider()
                }
            }
        }
    }
}
