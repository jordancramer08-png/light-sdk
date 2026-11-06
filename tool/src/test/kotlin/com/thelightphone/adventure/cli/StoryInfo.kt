package com.thelightphone.adventure.cli

import com.thelightphone.adventure.engine.Story
import com.thelightphone.adventure.engine.ZMachineError
import java.io.File
import kotlin.system.exitProcess

/** The summary lines for a story. Kept apart from main so tests can call it. */
fun storyInfo(story: Story): List<String> = listOf(
    "Version:          ${story.header.version}",
    "Release:          ${story.header.release}",
    "Serial:           ${story.header.serial}",
    "Objects:          ${story.objects.objectCount}",
    "Dictionary words: ${story.dictionary.size}",
    "Object 1 name:    ${story.objects.name(1)}",
)

/** Laptop-only program. It lives under src/test so it never ships in the APK. */
fun main(args: Array<String>) {
    if (args.size != 1) {
        println("Usage: story-info <story file>")
        exitProcess(2)
    }
    try {
        storyInfo(Story(File(args[0]).readBytes())).forEach(::println)
    } catch (error: ZMachineError) {
        println("Cannot read ${args[0]}: ${error.message}")
        exitProcess(1)
    }
}
