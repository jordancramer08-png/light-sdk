package com.thelightphone.bible.data

import com.thelightphone.sdk.LightFileShare
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads `reading_plan_2026.json` from the root of `lightContext.fileShare` (CLAUDE.md 8). */
class ReadingPlanRepository(private val fileShare: LightFileShare) {

    private val json = Json { ignoreUnknownKeys = true }
    private var cached: ReadingPlan? = null

    fun loadPlan(): ReadingPlan? {
        cached?.let { return it }
        val text = fileShare.read(PLAN_FILE) { it.readText() } ?: return null
        val plan = try {
            json.decodeFromString(ReadingPlan.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        cached = plan
        return plan
    }

    companion object {
        private const val PLAN_FILE = "reading_plan_2026.json"
    }
}
