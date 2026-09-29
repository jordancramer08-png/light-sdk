package com.thelightphone.listen.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.listen.music.ListSort
import com.thelightphone.listen.music.sortLabel
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

/** A field a list can be sorted by: [name] is what's saved, [label] what's shown. */
data class SortField(val name: String, val label: String)

/**
 * Sort (like the Reader's Sort & Filter screen): every field A–Z and Z–A, e.g. "Title A–Z",
 * "Title Z–A", "Artist A–Z"… The current choice has a filled circle, the others an empty
 * one, so it reads without color. Tapping a row hands that choice back; back changes nothing.
 */
class SortScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val fields: List<SortField>,
    private val current: ListSort,
) : SimpleLightScreen<ListSort>(sealedActivity) {

    @Composable
    override fun Content() {
        val choices = fields.flatMap { field -> listOf(false, true).map { ListSort(field.name, it) to field.label } }
        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                LightText(
                    text = "Sort by",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                choices.forEachIndexed { i, (choice, label) ->
                    ChoiceRow(
                        label = sortLabel(label, choice.descending),
                        isCurrent = choice == current,
                        onSelect = { goBack(choice) },
                    )
                    if (i != choices.lastIndex) HairlineDivider()
                }
            }
        }
    }
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
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightIcon(icon = if (isCurrent) LightIcons.SELECT_ON else LightIcons.SELECT_OFF)
    }
}
