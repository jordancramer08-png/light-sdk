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
import com.thelightphone.sample.data.Group
import com.thelightphone.sample.data.Person
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

/** One active person plus the name of the group they sit in, for the people list. */
data class ManagePersonRow(
    val person: Person,
    val groupName: String,
)

class ManageScreenViewModel(
    private val repository: PrayerRepository,
) : LightViewModel<Unit>() {

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val _people = MutableStateFlow<List<ManagePersonRow>>(emptyList())
    val people: StateFlow<List<ManagePersonRow>> = _people.asStateFlow()

    private val _archived = MutableStateFlow<List<Person>>(emptyList())
    val archived: StateFlow<List<Person>> = _archived.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val groups = repository.listGroups()
            val groupNames = groups.associate { it.id to it.name }

            _groups.value = groups
            _people.value = repository.listActivePeople().map { person ->
                ManagePersonRow(
                    person = person,
                    groupName = person.groupId?.let(groupNames::get) ?: "Ungrouped",
                )
            }
            _archived.value = repository.listArchivedPeople()
        }
    }
}

/**
 * Housekeeping for the whole list: create / rename / delete groups, add people,
 * move a person between groups, and archive or restore a person. Reached from the
 * settings icon on the home screen. A back button sits in the top bar (see NOTES.md §1).
 */
class ManageScreen(
    sealedActivity: SealedLightActivity,
    private val repository: PrayerRepository,
) : LightScreen<Unit, ManageScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ManageScreenViewModel>
        get() = ManageScreenViewModel::class.java

    override fun createViewModel() = ManageScreenViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val groups by viewModel.groups.collectAsState()
        val people by viewModel.people.collectAsState()
        val archived by viewModel.archived.collectAsState()

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
                    center = LightTopBarCenter.Text("Manage"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    SectionHeader("Groups")
                    if (groups.isEmpty()) {
                        Hint("No groups yet.")
                    } else {
                        groups.forEach { group ->
                            RowText(
                                title = group.name,
                                onClick = {
                                    navigateTo(screenFactory = {
                                        GroupEditScreen(it, repository, group.id)
                                    })
                                },
                            )
                        }
                    }

                    SectionHeader("People")
                    if (people.isEmpty()) {
                        Hint("No people yet.")
                    } else {
                        people.forEach { row ->
                            RowText(
                                title = row.person.name,
                                subtitle = row.groupName,
                                onClick = {
                                    navigateTo(screenFactory = {
                                        PersonEditScreen(it, repository, row.person.id)
                                    })
                                },
                            )
                        }
                    }

                    if (archived.isNotEmpty()) {
                        SectionHeader("Archived")
                        archived.forEach { person ->
                            RowText(
                                title = person.name,
                                subtitle = "Archived",
                                onClick = {
                                    navigateTo(screenFactory = {
                                        PersonEditScreen(it, repository, person.id)
                                    })
                                },
                            )
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "New Group",
                            onClick = {
                                navigateTo(screenFactory = {
                                    GroupEditScreen(it, repository, null)
                                })
                            },
                        ),
                        LightBarButton.Text(
                            text = "Add Person",
                            onClick = {
                                navigateTo(screenFactory = {
                                    PersonEditScreen(it, repository, null)
                                })
                            },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Subheading,
        modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

@Composable
private fun Hint(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}

@Composable
private fun RowText(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy)
        subtitle?.let {
            LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}
