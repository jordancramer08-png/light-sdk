package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.BookMeta
import com.thelightphone.reader.data.ReadingList
import com.thelightphone.reader.data.ReadingListQueue
import com.thelightphone.reader.data.ReadingListRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every list, and which of them already hold this book. */
data class AddToListState(val lists: List<ReadingList>, val listIdsWithBook: Set<Long>)

class AddToListViewModel(
    private val repository: ReadingListRepository,
    private val bookSlug: String,
) : LightViewModel<Unit>() {

    /** Null until loaded. */
    private val _state = MutableStateFlow<AddToListState?>(null)
    val state: StateFlow<AddToListState?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = ReadingListQueue.read {
                AddToListState(repository.lists(), repository.listIdsContaining(bookSlug))
            }
        }
    }

    /** Puts the book in the list, or takes it out if it's already there. Shown at once, saved behind. */
    fun toggle(listId: Long) {
        val current = _state.value ?: return
        val isIn = listId in current.listIdsWithBook
        _state.value = current.copy(
            listIdsWithBook = if (isIn) current.listIdsWithBook - listId else current.listIdsWithBook + listId,
        )
        ReadingListQueue.write {
            if (isIn) repository.removeBook(listId, bookSlug) else repository.addBook(listId, bookSlug)
        }
    }
}

/**
 * Reached from a book's Contents screen. Each list has an on/off switch; tapping a row
 * puts this book in that list (at the end) or takes it out. A book can be in any number
 * of lists.
 */
class AddToListScreen(
    sealedActivity: SealedLightActivity,
    private val bookMeta: BookMeta,
) : LightScreen<Unit, AddToListViewModel>(sealedActivity) {

    override val viewModelClass: Class<AddToListViewModel>
        get() = AddToListViewModel::class.java

    override fun createViewModel() = AddToListViewModel(
        ReadingListRepository.getInstance { lightContext.readerDatabase() },
        bookMeta.slug,
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Add to list"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            val loaded = state
            when {
                loaded == null -> Unit
                loaded.lists.isEmpty() -> NoListsMessage()
                else -> ToggleList(state = loaded, onToggle = viewModel::toggle)
            }
        }
    }
}

@Composable
private fun NoListsMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "No lists yet.\n\nMake one with the lists button at the top of the Library.",
            variant = LightTextVariant.Copy,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/** One row per list: its name, then a switch that is on when this book is in it. */
@Composable
private fun ToggleList(state: AddToListState, onToggle: (Long) -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        state.lists.forEachIndexed { i, list ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onToggle(list.id) }
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = list.name,
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val isIn = list.id in state.listIdsWithBook
                LightIcon(icon = if (isIn) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF)
            }
            if (i != state.lists.lastIndex) {
                HairlineDivider()
            }
        }
    }
}
