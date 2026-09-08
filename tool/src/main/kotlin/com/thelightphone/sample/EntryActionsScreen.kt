package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sample.data.Entry
import com.thelightphone.sample.data.EntryType
import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EntryActionsScreenViewModel(
    private val repository: PrayerRepository,
    private val entryId: String,
) : LightViewModel<Unit>() {

    private val _entry = MutableStateFlow<Entry?>(null)
    val entry: StateFlow<Entry?> = _entry.asStateFlow()

    /** False until the first load finishes, so we don't flash "not found". */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _entry.value = repository.getEntry(entryId)
            _loaded.value = true
        }
    }

    /** Marks an unanswered request answered, or reopens an answered one. */
    fun toggleAnswered(onDone: () -> Unit) {
        val current = _entry.value ?: return onDone()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.setEntryAnswered(
                    entryId,
                    if (current.answeredAt == null) System.currentTimeMillis() else null,
                )
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteEntry(entryId) }
            onDone()
        }
    }
}

/**
 * The actions for one entry: edit it, mark it answered / reopen it (requests
 * only), or delete it. Shown after tapping an entry on the person screen. A
 * back button sits in the top bar (see NOTES.md §1).
 */
class EntryActionsScreen(
    sealedActivity: SealedLightActivity,
    private val entryId: String,
    private val personId: String,
    private val repository: PrayerRepository,
) : LightScreen<Unit, EntryActionsScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<EntryActionsScreenViewModel>
        get() = EntryActionsScreenViewModel::class.java

    override fun createViewModel() = EntryActionsScreenViewModel(repository, entryId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val entry by viewModel.entry.collectAsState()
        val loaded by viewModel.loaded.collectAsState()

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
                    center = LightTopBarCenter.Text(typeLabel(entry?.type)),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                val current = entry
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (current != null) {
                        LightText(text = current.text, variant = LightTextVariant.Copy)
                        LightText(
                            text = statusLine(current),
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    } else if (loaded) {
                        LightText(
                            text = "This entry is no longer here.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    }
                }

                if (current != null) {
                    LightBottomBar(
                        items = buildList {
                            add(
                                LightBarButton.Text(
                                    text = "Edit",
                                    onClick = {
                                        navigateTo(screenFactory = {
                                            EntryEditScreen(
                                                it, personId, repository, entryId, current.type,
                                            )
                                        })
                                    },
                                ),
                            )
                            if (current.type == EntryType.REQUEST) {
                                add(
                                    LightBarButton.Text(
                                        text = if (current.answeredAt == null) "Answered" else "Reopen",
                                        onClick = { viewModel.toggleAnswered { goBack() } },
                                    ),
                                )
                            }
                            add(
                                LightBarButton.Text(
                                    text = "Delete",
                                    onClick = {
                                        navigateTo(screenFactory = {
                                            ConfirmDeleteScreen(
                                                it,
                                                title = "Delete ${typeLabel(current.type)}",
                                                message = entryDeleteMessage(current),
                                            )
                                        }) { confirmed ->
                                            if (confirmed) viewModel.delete { goBack() }
                                        }
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun typeLabel(type: EntryType?): String = when (type) {
    EntryType.REQUEST -> "Request"
    EntryType.PRAISE -> "Praise"
    EntryType.UPDATE -> "Update"
    null -> "Entry"
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

/** A one-line preview of the entry text for the confirmation message. */
private fun entrySnippet(text: String): String {
    val oneLine = text.replace("\n", " ").trim()
    return if (oneLine.length <= 80) oneLine else oneLine.take(79).trimEnd() + "…"
}

private fun entryDeleteMessage(entry: Entry): String {
    val kind = typeLabel(entry.type).lowercase(Locale.getDefault())
    return "Delete this $kind?\n\n“${entrySnippet(entry.text)}”\n\n" +
        "Only this entry is removed. This can't be undone."
}

private fun statusLine(entry: Entry): String {
    val added = "Added ${formatDate(entry.createdAt)}"
    return entry.answeredAt?.let { "$added  ·  Answered ${formatDate(it)}" }
        ?: added
}
