// Written from the Z-Machine Standards Document 1.1. No code is ported from JSZM or ifvms.js.
package com.thelightphone.adventure.engine

/** The object table (Standard section 12). Objects are numbered from 1; 0 means "nothing". */
class ObjectTable(
    private val memory: StoryMemory,
    private val header: StoryHeader,
    private val text: ZText,
) {
    private val small = header.version == 3
    private val defaultsCount = if (small) 31 else 63
    private val entrySize = if (small) 9 else 14
    private val attributeBytes = if (small) 4 else 6
    private val maxObject = if (small) 255 else 65535
    private val maxAttribute = if (small) 31 else 47
    private val firstEntry = header.objectTable + 2 * defaultsCount

    /**
     * The file does not store a count. By convention property tables follow the last entry, so
     * entries end where the lowest property table begins.
     */
    val objectCount: Int by lazy {
        var lowestTable = memory.size
        var count = 0
        while (count < maxObject && entryAddress(count + 1) + entrySize <= lowestTable) {
            count++
            lowestTable = minOf(lowestTable, propertyTable(count))
        }
        count
    }

    private fun entryAddress(obj: Int) = firstEntry + (obj - 1) * entrySize

    private fun entry(obj: Int): Int {
        if (obj !in 1..maxObject) throw ZMachineError("Illegal object number $obj")
        return entryAddress(obj)
    }

    fun hasAttribute(obj: Int, attribute: Int): Boolean =
        memory.readByte(attributeByte(obj, attribute)) and attributeMask(attribute) != 0

    fun setAttribute(obj: Int, attribute: Int) {
        val address = attributeByte(obj, attribute)
        memory.writeByte(address, memory.readByte(address) or attributeMask(attribute))
    }

    fun clearAttribute(obj: Int, attribute: Int) {
        val address = attributeByte(obj, attribute)
        memory.writeByte(address, memory.readByte(address) and attributeMask(attribute).inv())
    }

    private fun attributeByte(obj: Int, attribute: Int): Int {
        if (attribute !in 0..maxAttribute) throw ZMachineError("Illegal attribute $attribute")
        return entry(obj) + attribute / 8
    }

    // Attribute 0 is the top bit of the first byte.
    private fun attributeMask(attribute: Int) = 0x80 shr (attribute % 8)

    fun parent(obj: Int) = readLink(obj, 0)
    fun sibling(obj: Int) = readLink(obj, 1)
    fun child(obj: Int) = readLink(obj, 2)

    private fun readLink(obj: Int, index: Int): Int {
        val address = entry(obj) + attributeBytes
        return if (small) memory.readByte(address + index) else memory.readWord(address + 2 * index)
    }

    private fun writeLink(obj: Int, index: Int, value: Int) {
        val address = entry(obj) + attributeBytes
        if (small) memory.writeByte(address + index, value) else memory.writeWord(address + 2 * index, value)
    }

    /** Takes an object out of its parent. Its own children stay with it. */
    fun remove(obj: Int) {
        val parent = parent(obj)
        if (parent != 0) {
            if (child(parent) == obj) {
                writeLink(parent, 2, sibling(obj))
            } else {
                var previous = child(parent)
                while (sibling(previous) != obj) {
                    previous = sibling(previous)
                    if (previous == 0) throw ZMachineError("Object $obj is missing from its parent")
                }
                writeLink(previous, 1, sibling(obj))
            }
        }
        writeLink(obj, 0, 0)
        writeLink(obj, 1, 0)
    }

    /** Moves an object to be the first child of [destination]. */
    fun insert(obj: Int, destination: Int) {
        remove(obj)
        writeLink(obj, 1, child(destination))
        writeLink(obj, 0, destination)
        writeLink(destination, 2, obj)
    }

    fun propertyTable(obj: Int): Int = memory.readWord(entry(obj) + attributeBytes + if (small) 3 else 6)

    /** The object's short name, which may be empty. */
    fun name(obj: Int): String {
        val table = propertyTable(obj)
        return if (memory.readByte(table) == 0) "" else text.decode(table + 1).text
    }

    private class Property(val number: Int, val size: Int, val dataAddress: Int)

    /** Reads the property block at [address], or null at the end of the list. */
    private fun propertyAt(address: Int): Property? {
        val first = memory.readByte(address)
        if (first == 0) return null
        if (small) return Property(first and 31, (first shr 5) + 1, address + 1)
        if (first and 0x80 == 0) {
            return Property(first and 63, if (first and 0x40 != 0) 2 else 1, address + 1)
        }
        val size = memory.readByte(address + 1) and 63
        return Property(first and 63, if (size == 0) 64 else size, address + 2)
    }

    private fun properties(obj: Int): List<Property> {
        val table = propertyTable(obj)
        var address = table + 1 + 2 * memory.readByte(table)
        val found = ArrayList<Property>()
        while (true) {
            val property = propertyAt(address) ?: return found
            found += property
            address = property.dataAddress + property.size
        }
    }

    /**
     * Every property number in stored order. Curses object 448 lists property 11 twice, so
     * this shows the repeat, while walking with [nextProperty] skips it.
     */
    fun propertyNumbers(obj: Int): List<Int> = properties(obj).map { it.number }

    private fun find(obj: Int, number: Int) = properties(obj).firstOrNull { it.number == number }

    /** The `get_prop` value: the object's own value, or the default for that property. */
    fun getProperty(obj: Int, number: Int): Int {
        val property = find(obj, number) ?: return defaultProperty(number)
        return if (property.size == 1) {
            memory.readByte(property.dataAddress)
        } else {
            memory.readWord(property.dataAddress) // Longer than 2 is undefined; use the first word.
        }
    }

    fun defaultProperty(number: Int): Int {
        if (number !in 1..defaultsCount) throw ZMachineError("Illegal property number $number")
        return memory.readWord(header.objectTable + 2 * (number - 1))
    }

    fun putProperty(obj: Int, number: Int, value: Int) {
        val property = find(obj, number)
            ?: throw ZMachineError("Object $obj has no property $number")
        when (property.size) {
            1 -> memory.writeByte(property.dataAddress, value)
            2 -> memory.writeWord(property.dataAddress, value)
            else -> throw ZMachineError("Property $number of object $obj is longer than 2 bytes")
        }
    }

    /** The `get_prop_addr` value: the address of the data, or 0 if the object lacks it. */
    fun propertyAddress(obj: Int, number: Int): Int = find(obj, number)?.dataAddress ?: 0

    /** The `get_prop_len` value, given a data address. Address 0 gives 0. */
    fun propertyLength(dataAddress: Int): Int {
        if (dataAddress == 0) return 0
        val before = memory.readByte(dataAddress - 1)
        return when {
            small -> (before shr 5) + 1
            before and 0x80 != 0 -> (before and 63).let { if (it == 0) 64 else it }
            before and 0x40 != 0 -> 2
            else -> 1
        }
    }

    /** The `get_next_prop` value. Number 0 gives the first property; 0 means no more. */
    fun nextProperty(obj: Int, number: Int): Int {
        val all = properties(obj)
        if (number == 0) return all.firstOrNull()?.number ?: 0
        // The last match, so a property listed twice (Curses object 448) cannot loop forever.
        val index = all.indexOfLast { it.number == number }
        if (index < 0) throw ZMachineError("Object $obj has no property $number")
        return all.getOrNull(index + 1)?.number ?: 0
    }
}
