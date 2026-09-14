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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.AnswersRepository
import com.thelightphone.sample.data.Item
import com.thelightphone.sample.data.ItemType
import com.thelightphone.sample.data.StudyContentRepository
import com.thelightphone.sample.data.StudyContentResult
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

private fun Item.label(): String = when (type) {
    ItemType.QUESTION -> "Question $number"
    ItemType.CHILDRENS -> "Children's question"
}

sealed interface ItemScreenState {
    data object Loading : ItemScreenState
    data class Loaded(
        val itemId: String,
        val itemLabel: String,
        val questionText: String,
        val answerText: String,
        val hasAnswer: Boolean,
        val previousItemId: String?,
        val nextItemId: String?,
    ) : ItemScreenState
    data object NotFound : ItemScreenState
}

class ItemScreenViewModel(
    private val lessonNumber: Int,
    private val itemId: String,
    private val studyContentRepository: StudyContentRepository,
    private val answersRepository: AnswersRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<ItemScreenState>(ItemScreenState.Loading)
    val state: StateFlow<ItemScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val lesson = (studyContentRepository.load() as? StudyContentResult.Loaded)
                ?.content?.lesson(lessonNumber)
            val items = lesson?.items.orEmpty()
            val index = items.indexOfFirst { it.id == itemId }
            val item = items.getOrNull(index)

            _state.value = if (item == null) {
                ItemScreenState.NotFound
            } else {
                val answer = answersRepository.getText(item.id).orEmpty()
                ItemScreenState.Loaded(
                    itemId = item.id,
                    itemLabel = item.label(),
                    questionText = item.text,
                    answerText = answer,
                    hasAnswer = answer.isNotBlank(),
                    previousItemId = items.getOrNull(index - 1)?.id,
                    nextItemId = items.getOrNull(index + 1)?.id,
                )
            }
        }
    }
}

class ItemScreen(
    sealedActivity: SealedLightActivity,
    private val lessonNumber: Int,
    private val itemId: String,
    private val studyContentRepository: StudyContentRepository,
    private val answersRepository: AnswersRepository,
) : LightScreen<Unit, ItemScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ItemScreenViewModel>
        get() = ItemScreenViewModel::class.java

    override fun createViewModel() =
        ItemScreenViewModel(lessonNumber, itemId, studyContentRepository, answersRepository)

    private fun openItem(id: String) {
        navigateTo(screenFactory = {
            ItemScreen(it, lessonNumber, id, studyContentRepository, answersRepository)
        })
    }

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
                    is ItemScreenState.Loading -> Unit

                    is ItemScreenState.NotFound -> Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "This question couldn't be found in the study file.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            align = TextAlign.Center,
                        )
                    }

                    is ItemScreenState.Loaded -> ItemBody(
                        state = current,
                        onEditAnswer = {
                            navigateTo(screenFactory = {
                                AnswerEditorScreen(it, current.itemId, current.itemLabel, current.answerText, answersRepository)
                            })
                        },
                        onPrevious = current.previousItemId?.let { id -> { openItem(id) } },
                        onNext = current.nextItemId?.let { id -> { openItem(id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemBody(
    state: ItemScreenState.Loaded,
    onEditAnswer: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(text = state.itemLabel, variant = LightTextVariant.Detail, lighten = true)

            LightText(
                text = state.questionText,
                variant = LightTextVariant.ParagraphWide,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onEditAnswer)
                    .padding(top = 1.5f.gridUnitsAsDp()),
            ) {
                LightText(text = "Your answer", variant = LightTextVariant.Detail, lighten = true)
                LightText(
                    text = state.answerText.ifBlank { "Tap to write your answer" },
                    variant = LightTextVariant.Copy,
                    lighten = !state.hasAnswer,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }

        LightBottomBar(
            items = listOf(
                onPrevious?.let {
                    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it, contentDescription = "Previous")
                },
                onNext?.let {
                    LightBarButton.LightIcon(icon = LightIcons.ARROW_RIGHT, onClick = it, contentDescription = "Next")
                },
            ),
        )
    }
}
