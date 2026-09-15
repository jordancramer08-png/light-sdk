package com.thelightphone.bible.data

import kotlinx.serialization.Serializable

/** Mirrors `reading_plan_2026.json` exactly as it's placed on the device (CLAUDE.md 8). */
@Serializable
data class ReadingPlan(
    val name: String,
    val year: Int,
    val source: String,
    val totalReadingDays: Int,
    val days: List<ReadingPlanDay>,
    val bookAliases: Map<String, List<String>>,
    val canonicalBookOrder: List<String>,
)

@Serializable
data class ReadingPlanDay(
    val day: Int,
    val date: String,
    val weekday: String,
    val displayDate: String,
    val complete: Boolean,
    val label: String,
    val passages: List<ReadingPlanPassage>,
)

@Serializable
data class ReadingPlanPassage(
    val book: String,
    val startChapter: Int,
    val endChapter: Int,
    val startVerse: Int? = null,
    val endVerse: Int? = null,
)
