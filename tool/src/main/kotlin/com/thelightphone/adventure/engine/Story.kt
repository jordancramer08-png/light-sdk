package com.thelightphone.adventure.engine

/** A loaded story file with its parts wired together. */
class Story(bytes: ByteArray) {
    val memory = StoryMemory(bytes)
    val header = StoryHeader(memory)
    val text = ZText(memory, header)
    val objects = ObjectTable(memory, header, text)
    val dictionary = Dictionary(memory, text, header.dictionary)
}
