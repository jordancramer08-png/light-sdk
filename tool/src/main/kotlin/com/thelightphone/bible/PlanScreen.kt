package com.thelightphone.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.bible.data.ReadingPlan
import com.thelightphone.bible.data.ReadingPlanDay
import com.thelightphone.bible.data.ReadingPlanRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
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
import java.time.LocalDate

private const val PLAN_ROW_HEIGHT_GRID = 4f

sealed interface PlanScreenState {
    data object Loading : PlanScreenState
    data object Unavailable : PlanScreenState
    data class Loaded(val days: List<ReadingPlanDay>, val initialIndex: Int) : PlanScreenState
}

/**
 * The reading plan by day (CLAUDE.md 9). Opens scrolled to today's row if today falls
 * within the plan's year, otherwise Day 1 - so opening this screen daily doesn't mean
 * scrolling past everything already read.
 */
class PlanScreenViewModel(
    private val readingPlanRepository: ReadingPlanRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<PlanScreenState>(PlanScreenState.Loading)
    val state: StateFlow<PlanScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val plan = readingPlanRepository.loadPlan()
            if (plan == null || plan.days.isEmpty()) {
                _state.value = PlanScreenState.Unavailable
                return@launch
            }
            _state.value = PlanScreenState.Loaded(plan.days, todayIndex(plan))
        }
    }

    private fun todayIndex(plan: ReadingPlan): Int {
        val today = LocalDate.now()
        if (today.year != plan.year) return 0
        val todayDate = today.toString()
        val index = plan.days.indexOfFirst { it.date == todayDate }
        return if (index >= 0) index else 0
    }
}

class PlanScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, PlanScreenViewModel>(sealedActivity) {

    private val readingPlanRepository = ReadingPlanRepository(lightContext.fileShare)

    override val viewModelClass: Class<PlanScreenViewModel>
        get() = PlanScreenViewModel::class.java

    override fun createViewModel() = PlanScreenViewModel(readingPlanRepository)

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
                    center = LightTopBarCenter.Text("Plan"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    PlanScreenState.Loading -> Unit
                    PlanScreenState.Unavailable -> EmptyMessage("Reading plan not installed yet.")
                    is PlanScreenState.Loaded -> PlanDayList(
                        days = current.days,
                        initialIndex = current.initialIndex,
                        onSelect = { day -> navigateTo(screenFactory = { PlanDayScreen(it, day) }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanDayList(
    days: List<ReadingPlanDay>,
    initialIndex: Int,
    onSelect: (ReadingPlanDay) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LightLazyScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        listState = listState,
        uniformItemHeightGridUnits = PLAN_ROW_HEIGHT_GRID,
    ) {
        items(days, key = { it.day }) { day ->
            PlanDayRow(day = day, onSelect = onSelect)
        }
    }
}

@Composable
private fun PlanDayRow(day: ReadingPlanDay, onSelect: (ReadingPlanDay) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(PLAN_ROW_HEIGHT_GRID.gridUnitsAsDp())
            .lightClickable { onSelect(day) },
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = day.displayDate,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        LightText(
            text = day.label,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
