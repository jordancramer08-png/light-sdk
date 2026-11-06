// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** One routine call: its local variables and how to go back (Standard section 6). */
class Frame(
    val returnPc: Int,
    val storeVariable: Int?,
    val locals: IntArray,
    val argCount: Int,
    val stackBase: Int,
) {
    fun copy() = Frame(returnPc, storeVariable, locals.copyOf(), argCount, stackBase)
}

/** The routine frames and the evaluation stack. Each routine sees only its own values. */
class CallStack {
    private val frames = ArrayList<Frame>()
    private val values = ArrayList<Int>()

    val depth: Int get() = frames.size
    val current: Frame get() = frames.last()

    /** Number of values on the stack, across all routines. */
    val size: Int get() = values.size

    fun clear() {
        frames.clear()
        values.clear()
    }

    fun enter(frame: Frame) {
        if (frames.size >= MAX_DEPTH) throw ZMachineError("Routine calls are nested too deeply")
        frames += frame
    }

    /** Leaves the current routine and drops whatever it left on the stack. */
    fun leave(): Frame {
        val frame = frames.removeAt(frames.size - 1)
        while (values.size > frame.stackBase) values.removeAt(values.size - 1)
        return frame
    }

    fun push(value: Int) {
        values += value and 0xFFFF
    }

    fun pop(): Int {
        requireValue()
        return values.removeAt(values.size - 1)
    }

    fun peek(): Int {
        requireValue()
        return values.last()
    }

    /** Changes the top value in place, as indirect references to the stack require. */
    fun replaceTop(value: Int) {
        requireValue()
        values[values.size - 1] = value and 0xFFFF
    }

    private fun requireValue() {
        if (values.size <= current.stackBase) throw ZMachineError("Stack underflow")
    }

    fun copy(): CallStack {
        val copy = CallStack()
        copy.restoreFrom(this)
        return copy
    }

    fun restoreFrom(saved: CallStack) {
        clear()
        saved.frames.mapTo(frames) { it.copy() }
        values += saved.values
    }

    private companion object {
        const val MAX_DEPTH = 4096
    }
}
