// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** What each opcode does (Standard sections 14 and 15). */
internal class Opcodes(private val m: ZMachine, private val io: ZIo) {
    private val memory = m.memory
    private val objects = m.story.objects
    private val text = m.story.text
    private val version = m.header.version
    private var font = 1
    private var currentWindowIsUpper = false

    fun execute(ins: Instruction, a: IntArray) {
        if (a[0] == 0 && actsOnObject(ins)) {
            // Real games do this: Curses and So Far, every turn.
            m.gaps.note("unusual: object 0 used by ${ins.kind}:${ins.number}", ins.address)
            m.finishWithNothing(ins)
            return
        }
        when (ins.kind) {
            OpKind.TWO -> twoOp(ins, a)
            OpKind.ONE -> oneOp(ins, a)
            OpKind.ZERO -> zeroOp(ins)
            OpKind.VAR -> varOp(ins, a)
            OpKind.EXT -> extOp(ins, a)
        }
    }

    /** True for opcodes whose first operand is an object, where 0 means "no object". */
    private fun actsOnObject(ins: Instruction): Boolean = when (ins.kind) {
        OpKind.TWO -> ins.number in TWO_OBJECT_OPS
        OpKind.ONE -> ins.number in ONE_OBJECT_OPS
        OpKind.VAR -> ins.number == 3 // put_prop
        else -> false
    }

    private fun unimplemented(ins: Instruction) {
        val name = "${ins.kind}:${ins.number} (0x${ins.opcodeByte.toString(16)})"
        m.gaps.note("unimplemented opcode $name", ins.address)
    }

    private fun signed(value: Int) = value.toShort().toInt()

    private fun args(ins: Instruction, a: IntArray) = a.slice(1 until ins.operandValues.size)

    private fun twoOp(ins: Instruction, a: IntArray) {
        when (ins.number) {
            1 -> m.branch(ins, (1 until ins.operandValues.size).any { a[it] == a[0] }) // je
            2 -> m.branch(ins, signed(a[0]) < signed(a[1])) // jl
            3 -> m.branch(ins, signed(a[0]) > signed(a[1])) // jg
            4 -> m.branch(ins, addTo(a[0], -1) < signed(a[1])) // dec_chk
            5 -> m.branch(ins, addTo(a[0], 1) > signed(a[1])) // inc_chk
            6 -> m.branch(ins, objects.parent(a[0]) == a[1]) // jin
            7 -> m.branch(ins, a[0] and a[1] == a[1]) // test
            8 -> m.store(ins, a[0] or a[1])
            9 -> m.store(ins, a[0] and a[1])
            10 -> m.branch(ins, objects.hasAttribute(a[0], a[1])) // test_attr
            11 -> objects.setAttribute(a[0], a[1])
            12 -> objects.clearAttribute(a[0], a[1])
            13 -> m.writeIndirect(a[0], a[1]) // store
            14 -> objects.insert(a[0], a[1])
            15 -> m.store(ins, memory.readWord((a[0] + 2 * a[1]) and 0xFFFF)) // loadw
            16 -> m.store(ins, memory.readByte((a[0] + a[1]) and 0xFFFF)) // loadb
            17 -> m.store(ins, objects.getProperty(a[0], a[1]))
            18 -> m.store(ins, objects.propertyAddress(a[0], a[1]))
            19 -> m.store(ins, objects.nextProperty(a[0], a[1]))
            20 -> m.store(ins, signed(a[0]) + signed(a[1]))
            21 -> m.store(ins, signed(a[0]) - signed(a[1]))
            22 -> m.store(ins, signed(a[0]) * signed(a[1]))
            23 -> m.store(ins, signed(a[0]) / divisor(a[1]))
            24 -> m.store(ins, signed(a[0]) % divisor(a[1]))
            25, 26 -> m.call(a[0], args(ins, a), ins.store) // call_2s, call_2n
            27 -> io.setColors(color(a[0]), color(a[1]))
            28 -> m.throwTo(a[0], a[1])
            else -> unimplemented(ins)
        }
    }

    private fun addTo(variable: Int, amount: Int): Int {
        val value = signed(signed(m.readIndirect(variable)) + amount)
        m.writeIndirect(variable, value)
        return value
    }

    private fun divisor(value: Int): Int {
        if (value == 0) throw ZMachineError("Division by zero")
        return signed(value)
    }

    private fun color(code: Int) = ZColor.entries.firstOrNull { it.code == code } ?: ZColor.CURRENT

    private fun oneOp(ins: Instruction, a: IntArray) {
        when (ins.number) {
            0 -> m.branch(ins, a[0] == 0) // jz
            1 -> storeAndBranch(ins, objects.sibling(a[0]))
            2 -> storeAndBranch(ins, objects.child(a[0]))
            3 -> m.store(ins, objects.parent(a[0]))
            4 -> m.store(ins, objects.propertyLength(a[0]))
            5 -> addTo(a[0], 1)
            6 -> addTo(a[0], -1)
            7 -> m.streams.print(text.decode(a[0]).text) // print_addr
            8 -> m.call(a[0], emptyList(), ins.store) // call_1s
            9 -> objects.remove(a[0])
            10 -> m.streams.print(objects.name(a[0]))
            11 -> m.returnFromRoutine(a[0])
            12 -> m.pc += signed(a[0]) - 2 // jump
            13 -> m.streams.print(text.decode(m.header.unpack(a[0])).text) // print_paddr
            14 -> m.store(ins, m.readIndirect(a[0])) // load
            15 -> if (version < 5) m.store(ins, a[0].inv()) else m.call(a[0], emptyList(), null)
            else -> unimplemented(ins)
        }
    }

    private fun storeAndBranch(ins: Instruction, value: Int) {
        m.store(ins, value)
        m.branch(ins, value != 0)
    }

    private fun zeroOp(ins: Instruction) {
        when (ins.number) {
            0 -> m.returnFromRoutine(1)
            1 -> m.returnFromRoutine(0)
            2 -> printInline()
            3 -> printInlineAndReturn()
            4 -> Unit // nop
            5 -> if (version < 5) saveGame(ins) else unimplemented(ins)
            6 -> if (version < 5) restoreGame(ins) else unimplemented(ins)
            7 -> m.restart()
            8 -> m.returnFromRoutine(m.stack.pop())
            9 -> if (version < 5) m.stack.pop() else m.store(ins, m.stack.depth) // pop, catch
            10 -> m.quit()
            11 -> m.streams.print("\n")
            12 -> showStatus()
            13 -> m.branch(ins, m.header.computeChecksum() == m.header.checksum) // verify
            15 -> m.branch(ins, true) // piracy: the game is treated as genuine.
            else -> unimplemented(ins)
        }
    }

    private fun printInline() {
        val decoded = text.decode(m.pc)
        m.pc = decoded.nextAddress
        m.streams.print(decoded.text)
    }

    private fun printInlineAndReturn() {
        printInline()
        m.streams.print("\n")
        m.returnFromRoutine(1)
    }

    private fun saveGame(ins: Instruction) {
        val slot = io.chooseSaveSlot()
        reportSave(ins, slot != null && io.writeSave(slot, m.saveGame(ins)))
    }

    /** A successful restore continues from the saved game, so only failure comes back here. */
    private fun restoreGame(ins: Instruction) {
        val data = io.chooseRestoreSlot()?.let(io::readSave)
        if (data == null || !m.restoreGame(data, ins.address)) reportSave(ins, false)
    }

    /** Version 3 branches on success; later versions store 1, or 0 for failure. */
    private fun reportSave(ins: Instruction, success: Boolean) {
        if (version == 3) m.branch(ins, success) else m.store(ins, if (success) 1 else 0)
    }

    /** Sends the status line from globals 0-2 (Standard section 8.2). Version 3 only. */
    private fun showStatus() {
        if (version != 3) return
        val location = m.readVariable(16)
        io.showStatusLine(
            ZStatusLine(
                location = if (location == 0) "" else objects.name(location),
                isTime = m.header.statusShowsTime,
                first = signed(m.readVariable(17)),
                second = signed(m.readVariable(18)),
            ),
        )
    }

    private fun varOp(ins: Instruction, a: IntArray) {
        when (ins.number) {
            0, 12, 25, 26 -> m.call(a[0], args(ins, a), ins.store) // call_vs, vs2, vn, vn2
            1 -> memory.writeWord((a[0] + 2 * a[1]) and 0xFFFF, a[2]) // storew
            2 -> memory.writeByte((a[0] + a[1]) and 0xFFFF, a[2]) // storeb
            3 -> objects.putProperty(a[0], a[1], a[2])
            4 -> read(ins, a)
            5 -> printZscii(a[0])
            6 -> m.streams.print(signed(a[0]).toString())
            7 -> m.store(ins, m.random(signed(a[0])))
            8 -> m.stack.push(a[0])
            9 -> m.writeIndirect(a[0], m.stack.pop()) // pull
            10 -> io.splitWindow(a[0])
            11 -> selectWindow(a[0])
            13 -> io.eraseWindow(signed(a[0]))
            14 -> if (a[0] == 1) io.eraseLine()
            15 -> io.setCursor(signed(a[0]), signed(a[1]))
            16 -> getCursor(a[0])
            17 -> io.setTextStyle(a[0])
            18 -> io.setBufferMode(a[0] != 0)
            19 -> m.streams.select(signed(a[0]), a[1])
            20 -> if (a[0] != 0) stub(ins, "input stream ${a[0]}")
            21 -> stub(ins, "sound_effect")
            22 -> readChar(ins, a)
            23 -> scanTable(ins, a)
            24 -> m.store(ins, a[0].inv()) // not
            27 -> m.lineInput.tokenise(a[0], a[1], a[2], a[3] != 0)
            28 -> m.lineInput.encode(a[0], a[1], a[2], a[3])
            29 -> copyTable(a[0], a[1], signed(a[2]))
            30 -> printTable(a[0], a[1], if (ins.operandValues.size > 2) a[2] else 1, a[3])
            31 -> m.branch(ins, a[0] <= m.stack.current.argCount) // check_arg_count
            else -> unimplemented(ins)
        }
    }

    private fun selectWindow(window: Int) {
        currentWindowIsUpper = window == 1
        io.setWindow(window)
    }

    private fun printZscii(zscii: Int) {
        val codePoint = text.zsciiToUnicode(zscii) ?: return
        m.streams.print(StringBuilder().appendCodePoint(codePoint).toString())
    }

    private fun getCursor(table: Int) {
        val cursor = io.cursor()
        memory.writeWord(table, cursor.line)
        memory.writeWord(table + 2, cursor.column)
    }

    /** sread and aread. Timed input runs the game's routine whenever the time runs out. */
    private fun read(ins: Instruction, a: IntArray) {
        if (version == 3) showStatus()
        val timed = version >= 4 && a[2] != 0 && a[3] != 0
        if (timed) noteTimedInput(ins)
        val line = waitFor(if (timed) a[2] else 0, a[3]) { tenths ->
            io.readLine(m.lineInput.roomLeft(a[0]), tenths)
        }
        if (line == null) {
            m.store(ins, 0)
            return
        }
        m.streams.echoInput(line)
        m.lineInput.store(a[0], line)
        if (a[1] != 0) m.lineInput.tokenise(a[0], a[1])
        m.store(ins, ZSCII_RETURN)
    }

    private fun readChar(ins: Instruction, a: IntArray) {
        val timed = a[1] != 0 && a[2] != 0
        if (timed) noteTimedInput(ins)
        m.store(ins, waitFor(if (timed) a[1] else 0, a[2]) { io.readChar(it) } ?: 0)
    }

    private fun noteTimedInput(ins: Instruction) {
        if (!io.capabilities.timedInput) stub(ins, "timed input on a screen without it")
    }

    private fun stub(ins: Instruction, feature: String) {
        m.gaps.note("stub: $feature", ins.address)
    }

    /** Repeats [input] until it gives a value, the routine says stop, or the game ends. */
    private fun <T> waitFor(tenths: Int, routine: Int, input: (Int) -> T?): T? {
        while (true) {
            val value = input(tenths)
            if (value != null || tenths == 0) return value
            if (m.callInterrupt(routine) != 0 || m.halted) return null
        }
    }

    private fun scanTable(ins: Instruction, a: IntArray) {
        val form = if (ins.operandValues.size > 3) a[3] else DEFAULT_SCAN_FORM
        val words = form and 0x80 != 0
        val entrySize = form and 0x7F
        var address = a[1]
        repeat(a[2]) {
            val value = if (words) memory.readWord(address) else memory.readByte(address)
            if (value == a[0]) {
                storeAndBranch(ins, address)
                return
            }
            address += entrySize
        }
        storeAndBranch(ins, 0)
    }

    /** A negative size forces a forward copy, even if that repeats overlapping bytes. */
    private fun copyTable(first: Int, second: Int, size: Int) {
        val count = kotlin.math.abs(size)
        when {
            second == 0 -> for (i in 0 until count) memory.writeByte(first + i, 0)
            size > 0 && second > first -> for (i in count - 1 downTo 0) copyByte(first, second, i)
            else -> for (i in 0 until count) copyByte(first, second, i)
        }
    }

    private fun copyByte(first: Int, second: Int, index: Int) {
        memory.writeByte(second + index, memory.readByte(first + index))
    }

    private fun printTable(table: Int, width: Int, height: Int, skip: Int) {
        val start = io.cursor()
        var address = table
        for (row in 0 until height) {
            if (row > 0) nextTableRow(start, row)
            for (column in 0 until width) printZscii(memory.readByte(address++))
            address += skip
        }
    }

    private fun nextTableRow(start: ZCursor, row: Int) {
        if (currentWindowIsUpper) io.setCursor(start.line + row, start.column) else m.streams.print("\n")
    }

    private fun extOp(ins: Instruction, a: IntArray) {
        when (ins.number) {
            0 -> if (noAuxiliaryFile(ins)) saveGame(ins)
            1 -> if (noAuxiliaryFile(ins)) restoreGame(ins)
            2 -> m.store(ins, logShift(a[0], signed(a[1])))
            3 -> m.store(ins, arithmeticShift(a[0], signed(a[1])))
            4 -> m.store(ins, setFont(a[0]))
            5, 7, 28 -> stub(ins, "pictures")
            6 -> pictureData(ins)
            9 -> saveUndo(ins)
            10 -> if (!m.restoreUndo()) m.store(ins, 0)
            11 -> printUnicode(a[0])
            12 -> m.store(ins, checkUnicode(a[0]))
            13 -> stub(ins, "set_true_colour")
            else -> unimplemented(ins)
        }
    }

    private fun saveUndo(ins: Instruction) {
        m.saveUndo(ins)
        m.store(ins, 1)
    }

    private fun pictureData(ins: Instruction) {
        stub(ins, "pictures")
        m.branch(ins, false)
    }

    /** Extended save and restore with operands use an auxiliary file, which is not supported. */
    private fun noAuxiliaryFile(ins: Instruction): Boolean {
        if (ins.operandValues.isEmpty()) return true
        stub(ins, "auxiliary save files")
        m.store(ins, 0)
        return false
    }

    private fun logShift(value: Int, places: Int) =
        if (places >= 0) value shl places else value ushr -places

    private fun arithmeticShift(value: Int, places: Int) =
        if (places >= 0) value shl places else signed(value) shr -places

    private fun setFont(requested: Int): Int {
        val previous = font
        return when (requested) {
            0 -> previous
            1, 4 -> {
                font = requested
                previous
            }
            else -> {
                m.gaps.note("stub: font $requested", m.pc)
                0
            }
        }
    }

    private fun printUnicode(codePoint: Int) {
        m.streams.print(StringBuilder().appendCodePoint(codePoint).toString())
    }

    /** Bit 0: can be printed. Bit 1: can be typed, meaning it has a ZSCII code. */
    private fun checkUnicode(codePoint: Int): Int {
        val printable = codePoint >= 32 && codePoint !in 0x7F..0x9F
        val typeable = codePoint == '?'.code || text.unicodeToZscii(codePoint) != '?'.code
        return (if (printable) 1 else 0) or (if (typeable) 2 else 0)
    }

    private companion object {
        const val ZSCII_RETURN = 13
        const val DEFAULT_SCAN_FORM = 0x82
        val TWO_OBJECT_OPS = setOf(6, 10, 11, 12, 14, 17, 18, 19)
        val ONE_OBJECT_OPS = setOf(1, 2, 3, 9, 10)
    }
}
