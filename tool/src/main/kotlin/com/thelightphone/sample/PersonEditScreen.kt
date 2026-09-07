package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.Group
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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

class PersonEditScreenViewModel(
    private val repository: PrayerRepository,
    private val personId: String?,
) : LightViewModel<Unit>() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    /** True while the full-screen keyboard editor is up instead of the form. */
    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    /** Which group the person should be in; null is the "Ungrouped" choice. */
    private val _groupId = MutableStateFlow<String?>(null)
    val groupId: StateFlow<String?> = _groupId.asStateFlow()

    private val _archived = MutableStateFlow(false)
    val archived: StateFlow<Boolean> = _archived.asStateFlow()

    val isNew: Boolean get() = personId == null

    private var loaded = personId == null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _groups.value = repository.listGroups()
            if (!loaded && personId != null) {
                repository.getPerson(personId)?.let { person ->
                    _name.value = person.name
                    _groupId.value = person.groupId
                    _archived.value = person.archived
                }
                loaded = true
            }
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

    fun selectGroup(groupId: String?) {
        _groupId.value = groupId
    }

    /**
     * Adds the new person, or moves the existing one into the chosen group,
     * then calls [onSaved] on the main thread. Adding with a blank name is a
     * no-op.
     */
    fun save(onSaved: () -> Unit) {
        val trimmed = _name.value.trim()
        if (personId == null && trimmed.isEmpty()) {
            onSaved()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (personId == null) {
                    repository.addPerson(trimmed, _groupId.value)
                } else {
                    repository.movePerson(personId, _groupId.value)
                }
            }
            onSaved()
        }
    }

    /**
     * Archives the person (hiding them from the lists) or restores them.
     * Archiving never touches their entries.
     */
    fun toggleArchived(onDone: () -> Unit) {
        if (personId == null) {
            onDone()
            return
        }
        val nowArchived = !_archived.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (nowArchived) {
                    repository.archivePerson(personId)
                } else {
                    repository.unarchivePerson(personId)
                }
            }
            onDone()
        }
    }
}

/**
 * Add a person and place them in a group, or move / archive an existing one
 * ([personId] null means new). Archiving keeps every entry - it only hides the
 * person from the lists. A back button sits in the top bar (see NOTES.md §1).
 */
class PersonEditScreen(
    sealedActivity: SealedLightActivity,
    private val repository: PrayerRepository,
    private val personId: String?,
) : LightScreen<Unit, PersonEditScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<PersonEditScreenViewModel>
        get() = PersonEditScreenViewModel::class.java

    override fun createViewModel() = PersonEditScreenViewModel(repository, personId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val name by viewModel.name.collectAsState()
        val typing by viewModel.typing.collectAsState()
        val groups by viewModel.groups.collectAsState()
        val selectedGroupId by viewModel.groupId.collectAsState()
        val archived by viewModel.archived.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            if (typing) {
                val textState = rememberTextFieldState(name)
                LightTextInputEditor(
                    title = "Name",
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
                            if (viewModel.isNew) "Add Person" else name.ifBlank { "Person" },
                        ),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        if (viewModel.isNew) {
                            LightTextField(
                                label = "Name",
                                value = name,
                                placeholder = "Tap to name",
                                onClick = viewModel::startTyping,
                            )
                            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                        }

                        LightText(
                            text = "Group",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                        )

                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            GroupChoice(
                                label = "Ungrouped",
                                selected = selectedGroupId == null,
                                onClick = { viewModel.selectGroup(null) },
                            )
                            groups.forEach { group ->
                                GroupChoice(
                                    label = group.name,
                                    selected = selectedGroupId == group.id,
                                    onClick = { viewModel.selectGroup(group.id) },
                                )
                            }
                        }
                    }

                    LightBottomBar(
                        items = buildList {
                            add(LightBarButton.Text(text = "Cancel", onClick = { goBack() }))
                            if (!viewModel.isNew) {
                                add(
                                    LightBarButton.Text(
                                        text = if (archived) "Unarchive" else "Archive",
                                        onClick = { viewModel.toggleArchived { goBack() } },
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

/** One row in the group picker. The selected row is underlined, not colored. */
@Composable
private fun GroupChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        underline = selected,
        lighten = !selected,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}
