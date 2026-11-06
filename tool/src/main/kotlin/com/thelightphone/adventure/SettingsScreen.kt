package com.thelightphone.adventure

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Everything the player can set. */
data class AppSettings(
    val textSize: Int = TextSizes.DEFAULT,
    val theme: String = ThemePalettes.lantern.name,
    val quickRow: Boolean = true,
    val autosave: Boolean = true,
) {
    companion object {
        /** The settings last read, for code that is not on a screen. */
        @Volatile
        var latest = AppSettings()
    }
}

private val TEXT_SIZE = intPreferencesKey("text_size")
private val THEME = stringPreferencesKey("theme")
private val QUICK_ROW = booleanPreferencesKey("quick_row_shown")
private val AUTOSAVE = booleanPreferencesKey("autosave")

private fun Preferences.toSettings() = AppSettings(
    textSize = this[TEXT_SIZE] ?: TextSizes.DEFAULT,
    theme = this[THEME] ?: ThemePalettes.lantern.name,
    quickRow = this[QUICK_ROW] ?: true,
    autosave = this[AUTOSAVE] ?: true,
)

/** Keeps the settings in the SDK's data store, so they outlast the app. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.edit { saved ->
            val new = change(saved.toSettings())
            saved[TEXT_SIZE] = new.textSize
            saved[THEME] = new.theme
            saved[QUICK_ROW] = new.quickRow
            saved[AUTOSAVE] = new.autosave
        }
    }
}

/** The current settings, applying the color theme as they change. */
@Composable
fun rememberAppSettings(store: DataStore<Preferences>): AppSettings {
    val flow = remember(store) { SettingsStore(store).settings }
    val settings by flow.collectAsState(AppSettings.latest)
    SideEffect {
        AppSettings.latest = settings
        AdventureTheme.palette = ThemePalettes.byName(settings.theme)
    }
    return settings
}

class SettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = GameKeys.isIgnored(keyCode)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        GameKeys.isIgnored(keyCode)

    @Composable
    override fun Content() {
        val settings = rememberAppSettings(lightContext.dataStore)
        val store = remember { SettingsStore(lightContext.dataStore) }
        val scope = rememberCoroutineScope()
        fun change(update: (AppSettings) -> AppSettings) {
            scope.launch { store.update(update) }
        }
        LightTheme(colors = AdventureTheme.lightColors) {
            Column(Modifier.fillMaxSize().background(AdventureTheme.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                )
                Column(Modifier.padding(horizontal = 10.dp)) {
                    SettingRow("Text size", settings.textSize.toString()) {
                        change { it.copy(textSize = TextSizes.next(it.textSize)) }
                    }
                    SettingRow("Color theme", ThemePalettes.byName(settings.theme).name) {
                        change { it.copy(theme = ThemePalettes.next(it.theme).name) }
                    }
                    SettingRow("Quick buttons", onOff(settings.quickRow)) {
                        change { it.copy(quickRow = !it.quickRow) }
                    }
                    SettingRow("Automatic save", onOff(settings.autosave)) {
                        change { it.copy(autosave = !it.autosave) }
                    }
                }
            }
        }
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"
}
