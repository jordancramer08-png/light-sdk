package com.thelightphone.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * The library's order, which books it shows, and whether each series is one row, chosen
 * together on the Sort & Filter screen.
 */
data class SortAndFilter(
    val sort: LibrarySort,
    val filter: LibraryFilter,
    val groupSeries: Boolean = true,
)

/**
 * Sort & Filter (CLAUDE.md 8): the four library orders, then a Show section (All, Want to
 * Read, Reading, Finished), then Group series (On, Off). The current choice in each section
 * has a filled circle, the others an empty one, so it reads without color. Tapping any row
 * hands the new choices back to LibraryScreen; back leaves them all unchanged.
 */
class SortFilterScreen(
    sealedActivity: SealedLightActivity,
    private val current: SortAndFilter,
) : SimpleLightScreen<SortAndFilter>(sealedActivity) {

    @Composable
    override fun Content() {
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Sort & Filter"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                SectionHeading("Sort by")
                LibrarySort.entries.forEachIndexed { i, sort ->
                    ChoiceRow(
                        label = sort.label,
                        isCurrent = sort == current.sort,
                        onSelect = { goBack(current.copy(sort = sort)) },
                    )
                    if (i != LibrarySort.entries.lastIndex) HairlineDivider()
                }

                SectionHeading("Show", modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()))
                LibraryFilter.entries.forEachIndexed { i, filter ->
                    ChoiceRow(
                        label = filter.label,
                        isCurrent = filter == current.filter,
                        onSelect = { goBack(current.copy(filter = filter)) },
                    )
                    if (i != LibraryFilter.entries.lastIndex) HairlineDivider()
                }

                SectionHeading("Group series", modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()))
                ChoiceRow(
                    label = "On",
                    isCurrent = current.groupSeries,
                    onSelect = { goBack(current.copy(groupSeries = true)) },
                )
                HairlineDivider()
                ChoiceRow(
                    label = "Off",
                    isCurrent = !current.groupSeries,
                    onSelect = { goBack(current.copy(groupSeries = false)) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
    )
}

@Composable
private fun ChoiceRow(label: String, isCurrent: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onSelect)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
        )
        LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
    }
}
