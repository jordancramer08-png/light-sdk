// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

import kotlin.random.Random

/**
 * The running game: program counter, variables, routine calls, undo and restart. The opcodes
 * themselves are in [Opcodes]. Give a [seed] to make random numbers repeat from run to run.
 */
class ZMachine(val story: Story, private val io: ZIo, private val seed: Long? = null) {
    /** The read instruction waiting for the player, with its operand values. */
    internal class InputPoint(val instruction: Instruction, val operands: IntArray)

    private class UndoState(
        val memory: ByteArray,
        val stack: CallStack,
        val pc: Int,
        val store: Int?,
    )

    internal val memory = story.memory
    internal val header = story.header

    /** Everything the engine could not do or found odd, for developers to read. */
    val gaps = GapLog(io)

    internal val streams = OutputStreams(memory, story.text, io, gaps)
    internal val lineInput = LineInput(memory, story.text, header.version, story.dictionary)
    internal val stack = CallStack()
    internal var pc = 0

    private val decoder = InstructionDecoder(memory, header.version)
    private val quetzal = Quetzal(memory)
    private val opcodes = Opcodes(this, io)
    private val undoStates = ArrayDeque<UndoState>()
    private var random = newRandom()
    private var lastReturnValue = 0

    internal var inputPoint: InputPoint? = null

    /** True once the game has quit or can no longer run. */
    var halted = false
        private set

    /** Why the engine had to stop, or null if it did not. A game that quits is not a failure. */
    var failure: String? = null
        private set

    /** True if the game itself ended with the quit opcode. */
    var quitByGame = false
        private set

    @Volatile
    private var stopDeadline = Long.MAX_VALUE

    init {
        start()
    }

    /** Runs until the game quits, or [requestStop]'s time runs out. */
    fun run() {
        var steps = 0
        while (!halted) {
            step()
            if (++steps and STOP_CHECK_MASK == 0 && System.nanoTime() > stopDeadline) return
        }
    }

    /**
     * Ends [run] from another thread. A game that is only finishing a turn gets a moment to reach
     * its next prompt, so that turn can be saved. One stuck in a loop is then stopped.
     */
    fun requestStop() {
        stopDeadline = System.nanoTime() + STOP_GRACE_NANOS
    }

    private fun start() {
        writeHeader()
        stack.clear()
        stack.enter(Frame(0, null, IntArray(0), 0, 0))
        streams.reset()
        pc = header.initialPc
    }

    /** Fills in the header bytes the interpreter owns (Standard section 11). */
    private fun writeHeader() {
        val screen = io.capabilities
        memory.writeByte(0x01, flags1(screen))
        val noPicturesMouseOrSound = memory.readByte(0x11) and 0x57
        memory.writeByte(0x11, noPicturesMouseOrSound)
        memory.writeByte(0x1E, INTERPRETER_NUMBER)
        memory.writeByte(0x1F, 'A'.code)
        memory.writeByte(0x20, screen.screenHeight)
        memory.writeByte(0x21, screen.screenWidth)
        if (header.version >= 5) writeScreenUnits(screen)
        memory.writeByte(0x32, 1) // Standard revision 1.1.
        memory.writeByte(0x33, 1)
    }

    private fun flags1(screen: ZCapabilities): Int {
        val old = memory.readByte(0x01)
        if (header.version == 3) {
            val kept = old and 0x0F // Clears "no status line" and "variable pitch default".
            return if (screen.splitScreen) kept or 0x20 else kept
        }
        var flags = 0
        if (screen.color) flags = flags or 0x01
        if (screen.bold) flags = flags or 0x04
        if (screen.italic) flags = flags or 0x08
        if (screen.fixedPitch) flags = flags or 0x10
        if (screen.timedInput) flags = flags or 0x80
        return flags
    }

    private fun writeScreenUnits(screen: ZCapabilities) {
        memory.writeWord(0x22, screen.screenWidth)
        memory.writeWord(0x24, screen.screenHeight)
        memory.writeByte(0x26, 1)
        memory.writeByte(0x27, 1)
        memory.writeByte(0x2C, ZColor.DEFAULT.code)
        memory.writeByte(0x2D, ZColor.DEFAULT.code)
    }

    /** Decodes and runs one instruction. A bad instruction is logged and the game carries on. */
    internal fun step() {
        val instruction = try {
            decoder.decode(pc)
        } catch (error: ZMachineError) {
            stop("cannot read instruction at 0x${pc.toString(16)}: ${error.message}")
            return
        }
        pc = instruction.nextAddress
        try {
            opcodes.execute(instruction, resolveOperands(instruction))
        } catch (error: ZMachineError) {
            gaps.note("error: ${error.message}", instruction.address)
            recover(instruction)
        } catch (cancelled: ZInputCancelled) {
            // The game was replaced while waiting for input, and carries on from its new state.
        }
    }

    /** Carries on after a failed instruction, or stops if even that is impossible. */
    private fun recover(instruction: Instruction) {
        try {
            finishWithNothing(instruction)
        } catch (error: ZMachineError) {
            stop("cannot recover at 0x${instruction.address.toString(16)}: ${error.message}")
        }
    }

    /** Ends an instruction that could not do its work: it stores 0 and its test is false. */
    internal fun finishWithNothing(instruction: Instruction) {
        store(instruction, 0)
        branch(instruction, false)
    }

    private fun stop(reason: String) {
        gaps.note(reason, pc)
        failure = reason
        halted = true
    }

    /** Operand values, padded with zeros so a missing operand reads as 0. */
    private fun resolveOperands(instruction: Instruction): IntArray {
        val values = IntArray(maxOf(MIN_OPERANDS, instruction.operandValues.size))
        instruction.operandValues.forEachIndexed { index, value ->
            val isVariable = instruction.operandTypes[index] == InstructionDecoder.VARIABLE
            values[index] = if (isVariable) readVariable(value) else value
        }
        return values
    }

    /** Variable 0 is the stack, 1-15 locals and 16-255 globals. Reading 0 pops. */
    internal fun readVariable(number: Int): Int = when {
        number == 0 -> stack.pop()
        number < 16 -> stack.current.locals[localIndex(number)]
        else -> memory.readWord(globalAddress(number))
    }

    internal fun writeVariable(number: Int, value: Int) {
        when {
            number == 0 -> stack.push(value)
            number < 16 -> stack.current.locals[localIndex(number)] = value and 0xFFFF
            else -> memory.writeWord(globalAddress(number), value and 0xFFFF)
        }
    }

    /** For opcodes that name a variable: here the stack is used in place, not popped. */
    internal fun readIndirect(number: Int): Int =
        if (number and 0xFF == 0) stack.peek() else readVariable(number and 0xFF)

    internal fun writeIndirect(number: Int, value: Int) {
        if (number and 0xFF == 0) stack.replaceTop(value) else writeVariable(number and 0xFF, value)
    }

    private fun localIndex(number: Int): Int {
        if (number > stack.current.locals.size) throw ZMachineError("Routine has no local $number")
        return number - 1
    }

    private fun globalAddress(number: Int) = header.globals + 2 * (number - 16)

    internal fun store(instruction: Instruction, value: Int) {
        instruction.store?.let { writeVariable(it, value) }
    }

    internal fun branch(instruction: Instruction, condition: Boolean) {
        val branch = instruction.branch ?: return
        if (condition == branch.onTrue) takeBranch(branch)
    }

    private fun takeBranch(branch: Branch) {
        when (branch.offset) {
            0 -> returnFromRoutine(0)
            1 -> returnFromRoutine(1)
            else -> pc += branch.offset - 2
        }
    }

    /** Calls a packed routine address. Calling address 0 just gives false. */
    internal fun call(packed: Int, args: List<Int>, storeVariable: Int?) {
        if (packed == 0) {
            storeVariable?.let { writeVariable(it, 0) }
            return
        }
        val address = header.unpack(packed)
        val localCount = memory.readByte(address)
        if (localCount > 15) throw ZMachineError("Routine at 0x${address.toString(16)} has $localCount locals")
        val locals = IntArray(localCount)
        var next = address + 1
        if (header.version < 5) {
            for (index in locals.indices) locals[index] = memory.readWord(next + 2 * index)
            next += 2 * localCount
        }
        args.take(localCount).forEachIndexed { index, value -> locals[index] = value }
        stack.enter(Frame(pc, storeVariable, locals, args.size, stack.size))
        pc = next
    }

    internal fun returnFromRoutine(value: Int) {
        if (stack.depth <= 1) {
            stop("return from the main routine")
            return
        }
        val frame = stack.leave()
        pc = frame.returnPc
        lastReturnValue = value
        frame.storeVariable?.let { writeVariable(it, value) }
    }

    /** Runs a timed-input routine to its end and returns its result. */
    internal fun callInterrupt(routine: Int): Int {
        val depth = stack.depth
        call(routine, emptyList(), null)
        while (!halted && stack.depth > depth) step()
        return lastReturnValue
    }

    /** The throw opcode: unwinds to the frame a catch recorded, then returns from it. */
    internal fun throwTo(value: Int, frameDepth: Int) {
        if (frameDepth !in 1..stack.depth) throw ZMachineError("throw to a routine that has ended")
        while (stack.depth > frameDepth) stack.leave()
        returnFromRoutine(value)
    }

    internal fun quit() {
        quitByGame = true
        halted = true
    }

    /** Starts over. Only the transcript and fixed-pitch bits survive (Standard section 6.1.3). */
    internal fun restart() {
        replaceMemory { memory.resetDynamic() }
        undoStates.clear()
        opcodes.reset()
        start()
    }

    /** Swaps dynamic memory, keeping the flags and header bytes that belong to the interpreter. */
    private fun replaceMemory(replace: () -> Unit) {
        val keptFlags = memory.readByte(0x11) and 0x03
        replace()
        memory.writeByte(0x11, (memory.readByte(0x11) and 0x03.inv()) or keptFlags)
        writeHeader()
    }

    /** The game as a Quetzal file, ready to resume at this save instruction's result. */
    internal fun saveGame(instruction: Instruction): ByteArray =
        quetzal.write(stack, instruction.resultAddress)

    /**
     * The game as a Quetzal file that resumes by waiting for this same input again. Only works
     * while the game is waiting; null otherwise. Call it from inside readLine or readChar.
     */
    fun saveAtInput(): ByteArray? {
        val point = inputPoint ?: return null
        val copy = stack.copy()
        val instruction = point.instruction
        // Stack operands were popped when the read began. Running it again needs them back.
        for (index in instruction.operandValues.indices.reversed()) {
            val isVariable = instruction.operandTypes[index] == InstructionDecoder.VARIABLE
            if (isVariable && instruction.operandValues[index] == 0) copy.push(point.operands[index])
        }
        return quetzal.write(copy, instruction.address, atInput = true)
    }

    /** Loads a saved game from outside the game's own restore. False if it is unusable. */
    fun loadSaved(data: ByteArray): Boolean = restoreGame(data, pc)

    /** Restarts from outside the game's own restart. */
    fun restartNow() = restart()

    /** Loads a Quetzal file and resumes there. False, with nothing changed, if it is unusable. */
    internal fun restoreGame(data: ByteArray, address: Int): Boolean {
        val saved = try {
            quetzal.read(data)
        } catch (error: ZMachineError) {
            gaps.note("unusual: save file refused: ${error.message}", address)
            return false
        }
        replaceMemory { memory.restoreDynamic(saved.memory) }
        stack.load(saved.frames, saved.values)
        undoStates.clear()
        inputPoint = null
        pc = saved.pc
        if (!saved.atInput) finishRestoredSave()
        return true
    }

    /** The save instruction now finishes: version 3 branches as if true, others store 2. */
    private fun finishRestoredSave() {
        if (header.version == 3) {
            val (branch, next) = decoder.branchAt(pc)
            pc = next
            if (branch.onTrue) takeBranch(branch)
        } else {
            val variable = memory.readByte(pc++)
            writeVariable(variable, 2)
        }
    }

    /** Remembers this moment for restore_undo, which resumes just after this instruction. */
    internal fun saveUndo(instruction: Instruction) {
        if (undoStates.size >= MAX_UNDO) undoStates.removeFirst()
        undoStates.addLast(UndoState(memory.copyDynamic(), stack.copy(), pc, instruction.store))
    }

    /** Goes back to the last save_undo, which then appears to return 2. False if none. */
    internal fun restoreUndo(): Boolean {
        val state = undoStates.removeLastOrNull() ?: return false
        memory.restoreDynamic(state.memory)
        stack.restoreFrom(state.stack)
        pc = state.pc
        state.store?.let { writeVariable(it, 2) }
        return true
    }

    /** The random opcode: above 0 picks 1..range, below 0 seeds, 0 goes back to unseeded. */
    internal fun random(range: Int): Int {
        if (range > 0) return random.nextInt(range) + 1
        random = if (range < 0) Random(-range.toLong()) else newRandom()
        return 0
    }

    private fun newRandom(): Random = seed?.let { Random(it) } ?: Random.Default

    private companion object {
        const val INTERPRETER_NUMBER = 6 // IBM PC, a neutral choice among the Standard's list.
        const val MIN_OPERANDS = 8
        const val MAX_UNDO = 10
        const val STOP_CHECK_MASK = 0xFFFF
        const val STOP_GRACE_NANOS = 1_500_000_000L
    }
}
