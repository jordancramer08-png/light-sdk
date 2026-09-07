package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.EntryType
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
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
import kotlinx.coroutines.withContext

class EntryEditScreenViewModel(
    private val repository: PrayerRepository,
    private val personId: String,
    private val entryId: String?,
    initialType: EntryType,
) : LightViewModel<Unit>() {

    private val _type = MutableStateFlow(initialType)
    val type: StateFlow<EntryType> = _type.asStateFlow()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /** True while the full-screen keyboard editor is up instead of the form. */
    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    private var loaded = entryId == null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (loaded || entryId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.getEntry(entryId)?.let { existing ->
                _text.value = existing.text
                _type.value = existing.type
            }
            loaded = true
        }
    }

    fun setType(type: EntryType) {
        _type.value = type
    }

    fun startTyping() {
        _typing.value = true
    }

    fun stopTyping(newText: String) {
        _text.value = newText
        _typing.value = false
    }

    fun cancelTyping() {
        _typing.value = false
    }

    /** Saves the entry, then calls [onSaved] on the main thread. Blank text saves nothing. */
    fun save(onSaved: () -> Unit) {
        val body = _text.value.trim()
        if (body.isEmpty()) {
            onSaved()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (entryId == null) {
                    repository.addEntry(personId, _type.value, body, System.currentTimeMillis())
                } else {
                    repository.updateEntry(entryId, body, _type.value)
                }
            }
            onSaved()
        }
    }
}

/**
 * Create or edit one entry. The form shows a type selector, a tappable text
 * field, and Save / Cancel; tapping the field opens the full-screen keyboard.
 * The type defaults to whichever tab the person screen was on. A back button
 * sits in the top bar (see NOTES.md §1).
 */
class EntryEditScreen(
    sealedActivity: SealedLightActivity,
    private val personId: String,
    private val repository: PrayerRepository,
    private val entryId: String?,
    private val initialType: EntryType,
) : LightScreen<Unit, EntryEditScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<EntryEditScreenViewModel>
        get() = EntryEditScreenViewModel::class.java

    override fun createViewModel() =
        EntryEditScreenViewModel(repository, personId, entryId, initialType)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val type by viewModel.type.collectAsState()
        val text by viewModel.text.collectAsState()
        val typing by viewModel.typing.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            if (typing) {
                val textState = rememberTextFieldState(text)
                LightTextInputEditor(
                    title = "Note",
                    state = textState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.stopTyping(it.toString()) },
                    onBack = { viewModel.cancelTyping() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    initialCaps = true,
                )
            } else {
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
                        center = LightTopBarCenter.Text(
                            if (entryId == null) "New Entry" else "Edit Entry",
                        ),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        TypeSelector(
                            selected = type,
                            onSelect = viewModel::setType,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 0.5f.gridUnitsAsDp()),
                        )

                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                        LightTextField(
                            label = "Note",
                            value = text,
                            placeholder = "Tap to write",
                            onClick = viewModel::startTyping,
                        )
                    }

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(text = "Cancel", onClick = { goBack() }),
                            LightBarButton.Text(
                                text = "Save",
                                onClick = { viewModel.save { goBack() } },
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/** Request / Praise / Update, laid out like the person-screen tabs. */
@Composable
private fun TypeSelector(
    selected: EntryType,
    onSelect: (EntryType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        TypeOption("Request", EntryType.REQUEST, selected, onSelect, Modifier.weight(1f))
        TypeOption("Praise", EntryType.PRAISE, selected, onSelect, Modifier.weight(1f))
        TypeOption("Update", EntryType.UPDATE, selected, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun TypeOption(
    text: String,
    type: EntryType,
    selected: EntryType,
    onSelect: (EntryType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = type == selected
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        align = TextAlign.Center,
        underline = isSelected,
        lighten = !isSelected,
        modifier = modifier
            .lightClickable { onSelect(type) }
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}
