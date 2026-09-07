package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
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
import kotlinx.coroutines.withContext

class GroupEditScreenViewModel(
    private val repository: PrayerRepository,
    private val groupId: String?,
) : LightViewModel<Unit>() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    /** True while the full-screen keyboard editor is up instead of the form. */
    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    val isNew: Boolean get() = groupId == null

    private var loaded = groupId == null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (loaded || groupId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.getGroup(groupId)?.let { _name.value = it.name }
            loaded = true
        }
    }

    fun startTyping() {
        _typing.value = true
    }

    fun stopTyping(newText: String) {
        _name.value = newText
        _typing.value = false
    }

    fun cancelTyping() {
        _typing.value = false
    }

    /** Saves the group, then calls [onSaved] on the main thread. A blank name saves nothing. */
    fun save(onSaved: () -> Unit) {
        val trimmed = _name.value.trim()
        if (trimmed.isEmpty()) {
            onSaved()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (groupId == null) {
                    repository.addGroup(trimmed)
                } else {
                    repository.renameGroup(groupId, trimmed)
                }
            }
            onSaved()
        }
    }

    /** Deletes the group. Its people stay put and become ungrouped. */
    fun delete(onDone: () -> Unit) {
        if (groupId == null) {
            onDone()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteGroup(groupId) }
            onDone()
        }
    }
}

/**
 * Create a group, or rename / delete an existing one ([groupId] null means new).
 * Deleting a group never deletes its people - they just become ungrouped. A
 * back button sits in the top bar (see NOTES.md §1).
 */
class GroupEditScreen(
    sealedActivity: SealedLightActivity,
    private val repository: PrayerRepository,
    private val groupId: String?,
) : LightScreen<Unit, GroupEditScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<GroupEditScreenViewModel>
        get() = GroupEditScreenViewModel::class.java

    override fun createViewModel() = GroupEditScreenViewModel(repository, groupId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val name by viewModel.name.collectAsState()
        val typing by viewModel.typing.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            if (typing) {
                val textState = rememberTextFieldState(name)
                LightTextInputEditor(
                    title = "Group name",
                    state = textState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.stopTyping(it.toString()) },
                    onBack = { viewModel.cancelTyping() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    singleLine = true,
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
                            if (viewModel.isNew) "New Group" else "Edit Group",
                        ),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        LightTextField(
                            label = "Name",
                            value = name,
                            placeholder = "Tap to name",
                            onClick = viewModel::startTyping,
                        )
                    }

                    LightBottomBar(
                        items = buildList {
                            add(LightBarButton.Text(text = "Cancel", onClick = { goBack() }))
                            if (!viewModel.isNew) {
                                add(
                                    LightBarButton.Text(
                                        text = "Delete",
                                        onClick = { viewModel.delete { goBack() } },
                                    ),
                                )
                            }
                            add(
                                LightBarButton.Text(
                                    text = "Save",
                                    onClick = { viewModel.save { goBack() } },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}
