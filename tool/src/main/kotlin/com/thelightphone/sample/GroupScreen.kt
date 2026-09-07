package com.thelightphone.sample

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
import com.thelightphone.sample.data.Person
import com.thelightphone.sample.data.PrayerRepository
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

class GroupScreenViewModel(
    private val repository: PrayerRepository,
    private val groupId: String?,
) : LightViewModel<Unit>() {

    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _people.value = if (groupId == null) {
                repository.listUngroupedPeople()
            } else {
                repository.listPeopleInGroup(groupId)
            }
        }
    }
}

/**
 * The people in one group, alphabetical. A null [groupId] shows the ungrouped
 * people instead. A back button sits in the top bar (see NOTES.md §1).
 */
class GroupScreen(
    sealedActivity: SealedLightActivity,
    private val groupId: String?,
    private val groupName: String,
    private val repository: PrayerRepository,
) : LightScreen<Unit, GroupScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<GroupScreenViewModel>
        get() = GroupScreenViewModel::class.java

    override fun createViewModel() = GroupScreenViewModel(repository, groupId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val people by viewModel.people.collectAsState()

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
                    center = LightTopBarCenter.Text(groupName),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (people.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "No one in this group yet.",
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
                        people.forEach { person ->
                            PersonRowView(
                                person = person,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable {
                                        navigateTo(screenFactory = {
                                            PersonScreen(it, person.id, person.name, repository)
                                        })
                                    }
                                    .padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRowView(person: Person, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        LightText(text = person.name, variant = LightTextVariant.Copy)
        person.note?.let { note ->
            LightText(text = note, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}
