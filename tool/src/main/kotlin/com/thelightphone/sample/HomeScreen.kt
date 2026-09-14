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
import com.thelightphone.sample.data.AnswersRepository
import com.thelightphone.sample.data.Lesson
import com.thelightphone.sample.data.StudyContentRepository
import com.thelightphone.sample.data.StudyContentResult
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sample.data.AnswersDatabase
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

/** One row on the home screen: a lesson plus how many of its questions are answered. */
data class LessonRow(
    val lesson: Int,
    val title: String,
    val passage: String,
    val answeredCount: Int,
    val questionCount: Int,
)

sealed interface HomeScreenState {
    data object Loading : HomeScreenState
    data class Loaded(val rows: List<LessonRow>) : HomeScreenState
    data object ContentNotFound : HomeScreenState
    data class ContentInvalid(val message: String) : HomeScreenState
}

class HomeScreenViewModel(
    private val studyContentRepository: StudyContentRepository,
    private val answersRepository: AnswersRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<HomeScreenState>(HomeScreenState.Loading)
    val state: StateFlow<HomeScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = studyContentRepository.load()) {
                is StudyContentResult.NotFound -> _state.value = HomeScreenState.ContentNotFound
                is StudyContentResult.Invalid -> _state.value = HomeScreenState.ContentInvalid(result.message)
                is StudyContentResult.Loaded -> {
                    val rows = result.content.lessons.map { lesson -> lesson.toRow() }
                    _state.value = HomeScreenState.Loaded(rows)
                }
            }
        }
    }

    private fun Lesson.toRow(): LessonRow {
        val questionIds = questions.map { it.id }
        return LessonRow(
            lesson = lesson,
            title = title,
            passage = passage,
            answeredCount = answersRepository.countAnswered(questionIds),
            questionCount = questionIds.size,
        )
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    private val studyContentRepository = StudyContentRepository(lightContext.fileShare)

    private val answersRepository = AnswersRepository.getInstance {
        lightContext.buildDatabase(AnswersDatabase::class.java, AnswersRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel() = HomeScreenViewModel(studyContentRepository, answersRepository)

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
                    center = LightTopBarCenter.Text("Small Group"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    is HomeScreenState.Loading -> Unit

                    is HomeScreenState.ContentNotFound -> EmptyMessage(
                        "if_this_is_the_end.json hasn't been added to this phone yet.",
                    )

                    is HomeScreenState.ContentInvalid -> EmptyMessage(
                        "The study file couldn't be read: ${current.message}",
                    )

                    is HomeScreenState.Loaded -> LessonList(
                        rows = current.rows,
                        onSelect = { row ->
                            navigateTo(screenFactory = {
                                LessonScreen(it, row.lesson, studyContentRepository, answersRepository)
                            })
                        },
                    )
                }
            }
        }
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

@Composable
private fun LessonList(rows: List<LessonRow>, onSelect: (LessonRow) -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        rows.forEach { row ->
            LessonRowView(
                row = row,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(row) }
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun LessonRowView(row: LessonRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        LightText(
            text = "${row.lesson}. ${row.title}",
            variant = LightTextVariant.Copy,
            maxLines = 1,
        )
        LightText(
            text = row.passage,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        LightText(
            text = "${row.answeredCount}/${row.questionCount}",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
