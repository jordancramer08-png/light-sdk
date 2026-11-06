// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** The opcode families of Standard section 14. Each numbers its opcodes from 0. */
enum class OpKind { TWO, ONE, ZERO, VAR, EXT }

/** Where a branch goes: 0 and 1 mean return false and true, others are relative jumps. */
class Branch(val onTrue: Boolean, val offset: Int)

/** One decoded instruction. Variable operands are read later, when it runs. */
class Instruction(
    val address: Int,
    val opcodeByte: Int,
    val kind: OpKind,
    val number: Int,
    val operandTypes: IntArray,
    val operandValues: IntArray,
    val store: Int?,
    val branch: Branch?,
    val nextAddress: Int,
    /** Where the store byte or branch data begins. Quetzal saves record this address. */
    val resultAddress: Int,
)

/** Reads instructions from memory (Standard section 4). */
class InstructionDecoder(private val memory: StoryMemory, private val version: Int) {
    private var at = 0

    fun decode(address: Int): Instruction {
        at = address
        val op = nextByte()
        return when {
            op == 0xBE && version >= 5 -> extendedForm(address)
            op >= 0xC0 -> variableForm(address, op)
            op >= 0x80 -> shortForm(address, op)
            else -> longForm(address, op)
        }
    }

    private fun longForm(address: Int, op: Int): Instruction {
        val first = if (op and 0x40 != 0) VARIABLE else SMALL
        val second = if (op and 0x20 != 0) VARIABLE else SMALL
        return finish(address, op, OpKind.TWO, op and 0x1F, intArrayOf(first, second))
    }

    private fun shortForm(address: Int, op: Int): Instruction {
        val type = (op shr 4) and 3
        if (type == OMITTED) return finish(address, op, OpKind.ZERO, op and 0x0F, IntArray(0))
        return finish(address, op, OpKind.ONE, op and 0x0F, intArrayOf(type))
    }

    private fun variableForm(address: Int, op: Int): Instruction {
        val kind = if (op and 0x20 != 0) OpKind.VAR else OpKind.TWO
        val number = op and 0x1F
        // call_vs2 and call_vn2 have a second type byte, for up to 8 operands.
        val typeBytes = if (kind == OpKind.VAR && (number == 12 || number == 26)) 2 else 1
        return finish(address, op, kind, number, readTypes(typeBytes))
    }

    private fun extendedForm(address: Int): Instruction {
        val number = nextByte()
        return finish(address, 0xBE, OpKind.EXT, number, readTypes(1))
    }

    private fun readTypes(typeBytes: Int): IntArray {
        val bytes = IntArray(typeBytes) { nextByte() }
        val types = ArrayList<Int>()
        for (byte in bytes) {
            for (shift in intArrayOf(6, 4, 2, 0)) {
                val type = (byte shr shift) and 3
                if (type == OMITTED) return types.toIntArray()
                types += type
            }
        }
        return types.toIntArray()
    }

    private fun finish(address: Int, op: Int, kind: OpKind, number: Int, types: IntArray): Instruction {
        val values = IntArray(types.size) { if (types[it] == LARGE) nextWord() else nextByte() }
        val resultAddress = at
        val store = if (OpcodeShape.stores(kind, number, version)) nextByte() else null
        val branch = if (OpcodeShape.branches(kind, number, version)) readBranch() else null
        return Instruction(address, op, kind, number, types, values, store, branch, at, resultAddress)
    }

    /** Reads branch data on its own, and gives it with the address after it. */
    fun branchAt(address: Int): Pair<Branch, Int> {
        at = address
        val branch = readBranch()
        return branch to at
    }

    private fun readBranch(): Branch {
        val first = nextByte()
        val onTrue = first and 0x80 != 0
        if (first and 0x40 != 0) return Branch(onTrue, first and 0x3F)
        val raw = ((first and 0x3F) shl 8) or nextByte()
        return Branch(onTrue, if (raw >= 0x2000) raw - 0x4000 else raw) // 14 bits, signed.
    }

    private fun nextByte() = memory.readByte(at++)

    private fun nextWord() = (nextByte() shl 8) or nextByte()

    companion object {
        const val LARGE = 0
        const val SMALL = 1
        const val VARIABLE = 2
        const val OMITTED = 3
    }
}

/** Which opcodes are followed by a store byte or branch data (Standard section 14). */
internal object OpcodeShape {
    private val TWO_STORES = setOf(8, 9, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
    private val TWO_BRANCHES = setOf(1, 2, 3, 4, 5, 6, 7, 10)
    private val ONE_STORES = setOf(1, 2, 3, 4, 8, 14)
    private val ONE_BRANCHES = setOf(0, 1, 2)
    private val VAR_STORES = setOf(0, 7, 12, 22, 23, 24)
    private val VAR_BRANCHES = setOf(23, 31)
    private val EXT_STORES = setOf(0, 1, 2, 3, 4, 9, 10, 12)

    fun stores(kind: OpKind, number: Int, version: Int): Boolean = when (kind) {
        OpKind.TWO -> number in TWO_STORES
        OpKind.ONE -> number in ONE_STORES || (number == 15 && version < 5) // not
        OpKind.ZERO -> ((number == 5 || number == 6) && version == 4) || (number == 9 && version >= 5)
        OpKind.VAR -> number in VAR_STORES || (number == 4 && version >= 5) // aread
        OpKind.EXT -> number in EXT_STORES
    }

    fun branches(kind: OpKind, number: Int, version: Int): Boolean = when (kind) {
        OpKind.TWO -> number in TWO_BRANCHES
        OpKind.ONE -> number in ONE_BRANCHES
        OpKind.ZERO -> ((number == 5 || number == 6) && version == 3) || number == 13 || number == 15
        OpKind.VAR -> number in VAR_BRANCHES
        OpKind.EXT -> number == 6 // picture_data
    }
}
