package com.thelightphone.adventure.engine

/**
 * Returns the story inside a Blorb bundle (an IFF file of type IFRS, holding a ZCOD chunk beside
 * cover art and other extras). Anything that is not a Blorb comes back unchanged.
 */
fun storyFromBlorb(bytes: ByteArray): ByteArray {
    if (!isBlorb(bytes)) return bytes
    var at = 12
    while (at + 8 <= bytes.size) {
        val length = chunkLength(bytes, at + 4)
        if (length < 0 || at + 8 + length > bytes.size) break
        if (chunkId(bytes, at) == "ZCOD") return bytes.copyOfRange(at + 8, at + 8 + length)
        at += 8 + length + length % 2
    }
    throw ZMachineError("This Blorb bundle has no Z-machine story in it")
}

private fun isBlorb(bytes: ByteArray) =
    bytes.size >= 12 && chunkId(bytes, 0) == "FORM" && chunkId(bytes, 8) == "IFRS"

private fun chunkId(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.ISO_8859_1)

private fun chunkLength(bytes: ByteArray, at: Int) =
    (0 until 4).fold(0) { value, index -> (value shl 8) or (bytes[at + index].toInt() and 0xFF) }
