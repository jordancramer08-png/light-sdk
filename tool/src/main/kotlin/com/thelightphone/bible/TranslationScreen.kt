package com.thelightphone.bible

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.thelightphone.bible.data.BibleRepository
import com.thelightphone.bible.data.TranslationOption
import com.thelightphone.bible.data.TranslationRepository
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TranslationScreenState {
    data object Loading : TranslationScreenState
    data object Unavailable : TranslationScreenState
    data class Loaded(val options: List<TranslationOption>, val selectedId: String) : TranslationScreenState
}

/**
 * Picks the active translation (CLAUDE.md 9). [TranslationRepository] persists the choice,
 * and Read and Plan each read it fresh the next time they load - this screen doesn't push
 * the change to them directly.
 */
class TranslationScreenViewModel(
    private val translationRepository: TranslationRepository,
) : LightViewModel<Unit>() {

    private val _state = MutableStateFlow<TranslationScreenState>(TranslationScreenState.Loading)
    val state: StateFlow<TranslationScreenState> = _state.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val options = translationRepository.availableTranslations()
            if (options.isEmpty()) {
                _state.value = TranslationScreenState.Unavailable
                return@launch
            }
            _state.value = TranslationScreenState.Loaded(options, translationRepository.selectedTranslation())
        }
    }

    fun select(id: String) {
        val current = _state.value as? TranslationScreenState.Loaded ?: return
        if (current.selectedId == id) return
        _state.value = current.copy(selectedId = id)
        viewModelScope.launch(Dispatchers.IO) {
            translationRepository.selectTranslation(id)
        }
    }
}

class TranslationScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, TranslationScreenViewModel>(sealedActivity) {

    private val bibleRepository = BibleRepository(lightContext.fileShare)
    private val translationRepository =
        TranslationRepository(lightContext.fileShare, bibleRepository, lightContext.dataStore)

    override val viewModelClass: Class<TranslationScreenViewModel>
        get() = TranslationScreenViewModel::class.java

    override fun createViewModel() = TranslationScreenViewModel(translationRepository)

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
                    center = LightTopBarCenter.Text("Translation"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    TranslationScreenState.Loading -> Unit
                    TranslationScreenState.Unavailable -> EmptyMessage("No translations installed yet.")
                    is TranslationScreenState.Loaded -> TranslationList(
                        options = current.options,
                        selectedId = current.selectedId,
                        onSelect = viewModel::select,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationList(
    options: List<TranslationOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        options.forEach { option ->
            TranslationRow(option = option, selected = option.id == selectedId, onSelect = onSelect)
        }
    }
}

@Composable
private fun TranslationRow(
    option: TranslationOption,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onSelect(option.id) }
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightIcon(
            icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            modifier = Modifier.padding(end = 0.75f.gridUnitsAsDp()),
        )
        LightText(text = option.displayName, variant = LightTextVariant.Copy)
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
