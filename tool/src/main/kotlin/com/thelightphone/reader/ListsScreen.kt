package com.thelightphone.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.reader.data.ReadingList
import com.thelightphone.reader.data.ReadingListQueue
import com.thelightphone.reader.data.ReadingListRepository
import com.thelightphone.reader.data.readerDatabase
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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

class ListsScreenViewModel(
    private val repository: ReadingListRepository,
) : LightViewModel<LibraryView>() {

    /** Null until the first load finishes. */
    private val _lists = MutableStateFlow<List<ReadingList>?>(null)
    val lists: StateFlow<List<ReadingList>?> = _lists.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<LibraryView>) {
        super.onScreenShow(screen)
        reload()
    }

    fun create(name: String) = changeThenReload { repository.create(name) }

    fun rename(listId: Long, name: String) = changeThenReload { repository.rename(listId, name) }

    fun delete(listId: Long) = changeThenReload { repository.delete(listId) }

    private fun changeThenReload(change: () -> Unit) {
        ReadingListQueue.write(change)
        reload()
    }

    private fun reload() {
        viewModelScope.launch { _lists.value = ReadingListQueue.read { repository.lists() } }
    }
}

/**
 * "All books" plus Jordan's lists, reached from the Library's lists button. Tapping one
 * hands it back to the Library, which then shows only that list's books. + makes a new
 * list; each list has a pencil (rename) and a bin (delete, after asking).
 */
class ListsScreen(
    sealedActivity: SealedLightActivity,
    private val current: LibraryView,
) : LightScreen<LibraryView, ListsScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ListsScreenViewModel>
        get() = ListsScreenViewModel::class.java

    override fun createViewModel() =
        ListsScreenViewModel(ReadingListRepository.getInstance { lightContext.readerDatabase() })

    @Composable
    override fun Content() {
        val lists by viewModel.lists.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Lists"),
                rightButton = LightBarButton.LightIcon(icon = LightIcons.ADD, onClick = ::openNewList),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            LightScrollView(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                ListRow(
                    name = "All books",
                    isCurrent = current == LibraryView.AllBooks,
                    onSelect = { goBack(LibraryView.AllBooks) },
                )
                lists.orEmpty().forEach { list ->
                    HairlineDivider()
                    ListRow(
                        name = list.name,
                        isCurrent = current == LibraryView.OneList(list.id),
                        onSelect = { goBack(LibraryView.OneList(list.id)) },
                        onRename = { openRename(list) },
                        onDelete = { openDelete(list) },
                    )
                }
            }
        }
    }

    private fun openNewList() {
        navigateTo(
            screenFactory = { ListNameScreen(it, title = "New list") },
            resultCallback = { name -> viewModel.create(name) },
        )
    }

    private fun openRename(list: ReadingList) {
        navigateTo(
            screenFactory = { ListNameScreen(it, title = "Rename list", initialName = list.name) },
            resultCallback = { name -> viewModel.rename(list.id, name) },
        )
    }

    private fun openDelete(list: ReadingList) {
        navigateTo(
            screenFactory = { DeleteListScreen(it, list.name) },
            resultCallback = { confirmed -> if (confirmed) viewModel.delete(list.id) },
        )
    }
}

/**
 * One choice: a filled circle when it's what the Library shows now, the name, then
 * rename and delete buttons (lists only — "All books" can't be renamed or deleted).
 */
@Composable
private fun ListRow(
    name: String,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onSelect)
                .padding(vertical = 0.75f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
            LightText(
                text = name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 0.75f.gridUnitsAsDp()),
            )
        }
        if (onRename != null) RowIconButton(icon = LightIcons.PENCIL, onClick = onRename)
        if (onDelete != null) RowIconButton(icon = LightIcons.TRASH, onClick = onDelete)
    }
}
