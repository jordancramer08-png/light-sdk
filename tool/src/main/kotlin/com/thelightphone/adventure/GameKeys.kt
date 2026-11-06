package com.thelightphone.adventure

import android.view.KeyEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.ui.text.TextRange
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback

/**
 * LP3 keys kept from LightOS and ignored: camera full press (27), camera half press (80) and
 * the wheel press (319). Wheel turns (317, 318) are left out on purpose, so they still reach
 * LightOS, which uses them for brightness.
 */
object GameKeys {
    private val IGNORED = setOf(KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, 319)

    fun isIgnored(keyCode: Int) = keyCode in IGNORED
}

/** Sends the on-screen keyboard's presses to the input line. Return sends the command. */
class InputLineKeys(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = Unit

    override fun onKeyReleased(code: Int) = insert(buildString { appendCodePoint(code) })

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onKeyRepeated(code: Int) = insert(buildString { appendCodePoint(code) })

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insert(" ")
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteLast()
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) state.edit { delete(0, length) }
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insert(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insert(word.toString())

    private fun insert(text: String) {
        state.edit {
            append(text)
            selection = TextRange(length)
        }
    }

    private fun deleteLast() {
        state.edit {
            if (length > 0) delete(length - 1, length)
        }
    }
}
