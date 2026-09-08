package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.GroupDeletionSummary
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
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

/** What to do with a group's people when the group itself is deleted. */
enum class GroupDeleteChoice { KEEP_PEOPLE, DELETE_PEOPLE }

class ConfirmDeleteGroupScreenViewModel(
    private val repository: PrayerRepository,
    private val groupId: String,
) : LightViewModel<GroupDeleteChoice>() {

    private val _summary = MutableStateFlow<GroupDeletionSummary?>(null)
    val summary: StateFlow<GroupDeletionSummary?> = _summary.asStateFlow()

    /** false = keep the people (move them to Ungrouped); true = delete them too. Starts safe. */
    private val _deletePeople = MutableStateFlow(false)
    val deletePeople: StateFlow<Boolean> = _deletePeople.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<GroupDeleteChoice>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _summary.value = repository.groupDeletionSummary(groupId)
        }
    }

    fun setDeletePeople(value: Boolean) {
        _deletePeople.value = value
    }

    fun choice(): GroupDeleteChoice =
        if (_deletePeople.value) GroupDeleteChoice.DELETE_PEOPLE else GroupDeleteChoice.KEEP_PEOPLE
}

/**
 * Asks what should happen to a group's people before the group is deleted -
 * move them to Ungrouped (keeping their entries) or delete them and their
 * entries too. Cancel and the back button return nothing and change nothing;
 * Delete returns the chosen [GroupDeleteChoice] and the caller does the work.
 * A back button sits in the top bar (see NOTES.md).
 */
class ConfirmDeleteGroupScreen(
    sealedActivity: SealedLightActivity,
    private val repository: PrayerRepository,
    private val groupId: String,
    private val groupName: String,
) : LightScreen<GroupDeleteChoice, ConfirmDeleteGroupScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ConfirmDeleteGroupScreenViewModel>
        get() = ConfirmDeleteGroupScreenViewModel::class.java

    override fun createViewModel() = ConfirmDeleteGroupScreenViewModel(repository, groupId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val summary by viewModel.summary.collectAsState()
        val deletePeople by viewModel.deletePeople.collectAsState()

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
                    center = LightTopBarCenter.Text("Delete Group"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                val peopleCount = summary?.peopleCount ?: 0
                val entryCount = summary?.entryCount ?: 0

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Delete “$groupName”?",
                        variant = LightTextVariant.Subheading,
                    )

                    LightText(
                        text = if (peopleCount == 0) {
                            "No one is in this group. Only the group is removed."
                        } else {
                            "Choose what happens to the ${countPeople(peopleCount)} in it."
                        },
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(
                            top = 0.5f.gridUnitsAsDp(),
                            bottom = 1f.gridUnitsAsDp(),
                        ),
                    )

                    if (peopleCount > 0) {
                        PeopleChoiceRow(
                            label = "Keep them, move to Ungrouped",
                            detail = "Their entries are kept",
                            selected = !deletePeople,
                            onClick = { viewModel.setDeletePeople(false) },
                        )
                        PeopleChoiceRow(
                            label = "Delete them too",
                            detail = "Also removes ${countPeople(peopleCount)} and " +
                                "${countEntries(entryCount)}, for good",
                            selected = deletePeople,
                            onClick = { viewModel.setDeletePeople(true) },
                        )
                    }

                    LightText(
                        text = "This can't be undone.",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "Cancel", onClick = { goBack() }),
                        LightBarButton.Text(
                            text = "Delete",
                            onClick = { goBack(viewModel.choice()) },
                        ),
                    ),
                )
            }
        }
    }
}

/** One selectable option. The chosen row is underlined, not colored. */
@Composable
private fun PeopleChoiceRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            underline = selected,
            lighten = !selected,
        )
        LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
    }
}
