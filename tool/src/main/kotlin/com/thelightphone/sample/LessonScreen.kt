package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.AnswersRepository
import com.thelightphone.sample.data.Item
import com.thelightphone.sample.data.ItemType
import com.thelightphone.sample.data.Lesson
import com.thelightphone.sample.data.StudyContentRepository
import com.thelightphone.sample.data.StudyContentResult
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
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row in [LessonScreen]'s item list: an item plus whether it has a saved answer. */
data class ItemRow(
    val item: Item,
    val answered: Boolean,
)

sealed interface LessonScreenState {
    data object Loading : LessonScreenState
    data class Loaded(
        val title: String,
        val passageReference: String,
        val scripture: String,
        val commentary: String,
        val items: List<ItemRow>,
        val footnotes: List<String>,
    ) : LessonScreenState
    data object NotFound : LessonScreenState
}

class LessonScreenViewModel(
    private val lessonNumber: Int,
    private val studyContentRepository: StudyContentRepository,
    private val answersRepository: AnswersRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<LessonScreenState>(LessonScreenState.Loading)
    val state: StateFlow<LessonScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = studyContentRepository.load()
            val lesson = (result as? StudyContentResult.Loaded)?.content?.lesson(lessonNumber)

            _state.value = if (lesson == null) {
                LessonScreenState.NotFound
            } else {
                lesson.toState()
            }
        }
    }

    private fun Lesson.toState(): LessonScreenState.Loaded {
        val itemRows = items.map { item ->
            ItemRow(item = item, answered = answersRepository.getText(item.id)?.isNotBlank() == true)
        }
        return LessonScreenState.Loaded(
            title = title,
            passageReference = scriptureRef.ifBlank { passage },
            scripture = scripture,
            commentary = commentary,
            items = itemRows,
            footnotes = footnotes,
        )
    }
}

class LessonScreen(
    sealedActivity: SealedLightActivity,
    private val lessonNumber: Int,
    private val studyContentRepository: StudyContentRepository,
    private val answersRepository: AnswersRepository,
) : LightScreen<Unit, LessonScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<LessonScreenViewModel>
        get() = LessonScreenViewModel::class.java

    override fun createViewModel() =
        LessonScreenViewModel(lessonNumber, studyContentRepository, answersRepository)

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
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Lesson $lessonNumber"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    is LessonScreenState.Loading -> Unit

                    is LessonScreenState.NotFound -> Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "This lesson couldn't be found in the study file.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            align = TextAlign.Center,
                        )
                    }

                    is LessonScreenState.Loaded -> LessonBody(current)
                }
            }
        }
    }
}

@Composable
private fun LessonBody(state: LessonScreenState.Loaded) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = state.title, variant = LightTextVariant.Heading)

        if (state.passageReference.isNotBlank()) {
            LightText(
                text = state.passageReference,
                variant = LightTextVariant.Subheading,
                lighten = true,
                modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
            )
        }

        if (state.scripture.isNotBlank()) {
            LightText(
                text = state.scripture,
                variant = LightTextVariant.ParagraphWide,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }

        if (state.commentary.isNotBlank()) {
            LightText(
                text = state.commentary,
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }

        if (state.items.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 1f.gridUnitsAsDp())) {
                state.items.forEach { row ->
                    ItemRowView(
                        row = row,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }

        if (state.footnotes.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 1f.gridUnitsAsDp())) {
                LightText(text = "Footnotes", variant = LightTextVariant.Detail, lighten = true)
                state.footnotes.forEach { footnote ->
                    LightText(
                        text = footnote,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRowView(row: ItemRow, modifier: Modifier = Modifier) {
    val label = when (row.item.type) {
        ItemType.QUESTION -> "${row.item.number}."
        ItemType.CHILDRENS -> "Children's question"
    }

    Column(modifier = modifier) {
        LightText(text = label, variant = LightTextVariant.Detail, lighten = true)
        Row(
            verticalAlignment = Alignment.Top,
        ) {
            LightText(
                text = row.item.text,
                variant = LightTextVariant.Copy,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LightIcon(
                icon = if (row.answered) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
                size = 1.5f,
                contentDescription = if (row.answered) "Answered" else "Not answered",
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
