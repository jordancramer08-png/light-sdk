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
import com.thelightphone.sample.data.PrayerDatabase
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One row on the home screen: a group (or the synthetic "Ungrouped" bucket,
 * where [groupId] is null) plus how many active people it holds.
 */
data class GroupRow(
    val groupId: String?,
    val name: String,
    val personCount: Int,
)

class HomeScreenViewModel(
    private val repository: PrayerRepository,
    private val seedFileImporter: SeedFileImporter,
) : LightViewModel<Unit>() {

    private val _rows = MutableStateFlow<List<GroupRow>>(emptyList())
    val rows: StateFlow<List<GroupRow>> = _rows.asStateFlow()

    /** The seed file is checked once per process, on the first load. */
    private val seedFileChecked = AtomicBoolean(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (seedFileChecked.compareAndSet(false, true)) {
                seedFileImporter.runOnce(System.currentTimeMillis())
            }

            val groupRows = repository.listGroups().map { group ->
                GroupRow(
                    groupId = group.id,
                    name = group.name,
                    personCount = repository.listPeopleInGroup(group.id).size,
                )
            }
            val ungrouped = repository.listUngroupedPeople()

            _rows.value = if (ungrouped.isEmpty()) {
                groupRows
            } else {
                groupRows + GroupRow(groupId = null, name = "Ungrouped", personCount = ungrouped.size)
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    private val repository = PrayerRepository.getInstance {
        lightContext.buildDatabase(PrayerDatabase::class.java, PrayerRepository.DATABASE_NAME)
    }

    private val seedFileImporter = SeedFileImporter(lightContext.fileShare, repository)

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel() = HomeScreenViewModel(repository, seedFileImporter)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Prayer List"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = {
                            navigateTo(screenFactory = { ManageScreen(it, repository) })
                        },
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "No groups yet.",
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
                        rows.forEach { row ->
                            GroupRowView(
                                row = row,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable {
                                        navigateTo(screenFactory = {
                                            GroupScreen(it, row.groupId, row.name, repository)
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
private fun GroupRowView(row: GroupRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        LightText(text = row.name, variant = LightTextVariant.Copy)
        LightText(
            text = personCountLabel(row.personCount),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

private fun personCountLabel(count: Int): String =
    if (count == 1) "1 person" else "$count people"
