package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.Entry
import com.thelightphone.sample.data.EntryType
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PersonScreenViewModel(
    private val repository: PrayerRepository,
    private val personId: String,
) : LightViewModel<Unit>() {

    private val _selectedType = MutableStateFlow(EntryType.REQUEST)
    val selectedType: StateFlow<EntryType> = _selectedType.asStateFlow()

    /** Open requests, or every entry for the Praises / Updates tabs. Newest first. */
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Answered requests only; always empty on the Praises / Updates tabs. */
    private val _answeredEntries = MutableStateFlow<List<Entry>>(emptyList())
    val answeredEntries: StateFlow<List<Entry>> = _answeredEntries.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    /** Switch tabs and reload the list for the newly selected type. */
    fun selectType(type: EntryType) {
        if (_selectedType.value == type) return
        _selectedType.value = type
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.listEntries(personId, _selectedType.value)
            if (_selectedType.value == EntryType.REQUEST) {
                _entries.value = all.filter { it.answeredAt == null }
                _answeredEntries.value =
                    all.filter { it.answeredAt != null }.sortedByDescending { it.answeredAt }
            } else {
                _entries.value = all
                _answeredEntries.value = emptyList()
            }
        }
    }
}

/**
 * One person's entries, split into Requests / Praises / Updates tabs. The
 * selected tab is shown at full strength and underlined; the others are
 * dimmed - the screen is monochrome, so the difference never relies on color.
 * A back button sits in the top bar (see NOTES.md §1).
 */
class PersonScreen(
    sealedActivity: SealedLightActivity,
    private val personId: String,
    private val personName: String,
    private val repository: PrayerRepository,
) : LightScreen<Unit, PersonScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<PersonScreenViewModel>
        get() = PersonScreenViewModel::class.java

    override fun createViewModel() = PersonScreenViewModel(repository, personId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val selectedType by viewModel.selectedType.collectAsState()
        val entries by viewModel.entries.collectAsState()
        val answeredEntries by viewModel.answeredEntries.collectAsState()

        fun openEntry(entry: Entry) {
            navigateTo(screenFactory = {
                EntryActionsScreen(it, entry.id, personId, repository)
            })
        }

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
                    center = LightTopBarCenter.Text(personName),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                TabBar(
                    selectedType = selectedType,
                    onSelect = viewModel::selectType,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                )

                if (entries.isEmpty() && answeredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = emptyMessage(selectedType),
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        if (entries.isEmpty()) {
                            // Only reachable on the Requests tab: everything is answered.
                            LightText(
                                text = "No open requests.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        } else {
                            entries.forEach { entry ->
                                EntryRowView(
                                    entry = entry,
                                    onClick = { openEntry(entry) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                                )
                            }
                        }

                        if (answeredEntries.isNotEmpty()) {
                            LightText(
                                text = "Answered",
                                variant = LightTextVariant.Subheading,
                                modifier = Modifier.padding(
                                    top = 1.5f.gridUnitsAsDp(),
                                    bottom = 0.25f.gridUnitsAsDp(),
                                ),
                            )
                            answeredEntries.forEach { entry ->
                                EntryRowView(
                                    entry = entry,
                                    onClick = { openEntry(entry) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "Add",
                            onClick = {
                                navigateTo(screenFactory = {
                                    EntryEditScreen(it, personId, repository, null, selectedType)
                                })
                            },
                        ),
                    ),
                )
            }
        }
    }
}

/** A row of three tappable labels acting as tabs. */
@Composable
private fun TabBar(
    selectedType: EntryType,
    onSelect: (EntryType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        TabLabel("Requests", EntryType.REQUEST, selectedType, onSelect, Modifier.weight(1f))
        TabLabel("Praises", EntryType.PRAISE, selectedType, onSelect, Modifier.weight(1f))
        TabLabel("Updates", EntryType.UPDATE, selectedType, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun TabLabel(
    text: String,
    type: EntryType,
    selectedType: EntryType,
    onSelect: (EntryType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = type == selectedType
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        align = TextAlign.Center,
        underline = selected,
        lighten = !selected,
        modifier = modifier
            .lightClickable { onSelect(type) }
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun EntryRowView(
    entry: Entry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.lightClickable(onClick = onClick)) {
        LightText(text = entry.text, variant = LightTextVariant.Copy)
        LightText(
            text = entry.answeredAt?.let { "Answered ${formatDate(it)}" }
                ?: formatDate(entry.createdAt),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

private fun emptyMessage(type: EntryType): String = when (type) {
    EntryType.REQUEST -> "No prayer requests yet."
    EntryType.PRAISE -> "No praises yet."
    EntryType.UPDATE -> "No updates yet."
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
