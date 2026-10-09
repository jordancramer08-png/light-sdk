package com.thelightphone.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the confirm screen is: working out the question, asking it, removing, or it couldn't remove. */
sealed interface RemoveState {
    data object Measuring : RemoveState

    data class Asking(val question: String) : RemoveState

    data object Removing : RemoveState

    data object Failed : RemoveState
}

class RemoveViewModel(
    private val measure: () -> String,
    private val remove: suspend (forget: Boolean) -> Boolean,
) : LightViewModel<Boolean>() {

    private val _state = MutableStateFlow<RemoveState>(RemoveState.Measuring)
    val state: StateFlow<RemoveState> = _state.asStateFlow()

    /** "Also forget my place and settings": off until the reader turns it on. */
    private val _forget = MutableStateFlow(false)
    val forget: StateFlow<Boolean> = _forget.asStateFlow()

    fun toggleForget() {
        _forget.value = !_forget.value
    }

    init {
        viewModelScope.launch {
            _state.value = RemoveState.Asking(withContext(Dispatchers.IO) { measure() })
        }
    }

    /** Deletes the files off the main thread, then calls [onRemoved]. */
    fun confirm(onRemoved: () -> Unit) {
        if (_state.value !is RemoveState.Asking) return
        _state.value = RemoveState.Removing
        viewModelScope.launch {
            val forget = _forget.value
            val removed = withContext(Dispatchers.IO) { remove(forget) }
            if (removed) onRemoved() else _state.value = RemoveState.Failed
        }
    }
}

/**
 * "Remove from phone" (CLAUDE.md 9, 12): names what will go and the space it frees, e.g.
 * "Remove 12 comics (1.4 GB)?", with a REMOVE button. [measure] gives that question and
 * [remove] deletes the files (both run off the main thread; [remove] is false if it
 * couldn't). With [offerForget] (comics), an "Also forget my place and settings" row (off at
 * first) is shown above REMOVE, and [remove] is told whether it was on. Hands back true once
 * removed; back hands back nothing.
 */
class RemoveScreen(
    sealedActivity: SealedLightActivity,
    private val name: String,
    private val measure: () -> String,
    private val offerForget: Boolean = false,
    private val remove: suspend (forget: Boolean) -> Boolean,
) : LightScreen<Boolean, RemoveViewModel>(sealedActivity) {

    override val viewModelClass: Class<RemoveViewModel>
        get() = RemoveViewModel::class.java

    override fun createViewModel() = RemoveViewModel(measure, remove)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val forget by viewModel.forget.collectAsState()

        ThemedScreen {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(name),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = messageFor(state, forget),
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state is RemoveState.Asking && offerForget) {
                HairlineDivider()
                OnOffRow(label = "Also forget my place and settings", isOn = forget, onClick = viewModel::toggleForget)
            }
            if (state is RemoveState.Asking) {
                LightBottomBar(
                    items = listOf(LightBarButton.Text(text = "REMOVE", onClick = { viewModel.confirm { goBack(true) } })),
                )
            }
        }
    }
}

private fun messageFor(state: RemoveState, forget: Boolean): String = when (state) {
    RemoveState.Measuring -> ""
    is RemoveState.Asking -> "${state.question}\n\n${if (forget) REMOVAL_FORGETS else REMOVAL_KEEPS}"
    RemoveState.Removing -> "Removing…"
    RemoveState.Failed -> "Couldn't remove it."
}
