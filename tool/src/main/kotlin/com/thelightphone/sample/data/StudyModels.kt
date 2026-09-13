package com.thelightphone.sample.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The full study, parsed exactly as `if_this_is_the_end.json` states it
 * (CLAUDE.md 4). Never mutated or regenerated - if something looks wrong,
 * surface it rather than fixing it.
 */
@Serializable
data class StudyContent(
    val title: String,
    val subtitle: String = "",
    val author: String = "",
    val lessonCount: Int,
    val note: String = "",
    val lessons: List<Lesson> = emptyList(),
) {
    fun lesson(number: Int): Lesson? = lessons.find { it.lesson == number }
}

@Serializable
data class Lesson(
    val lesson: Int,
    val title: String,
    val passage: String,
    val scriptureRef: String = "",
    val scripture: String = "",
    val commentary: String = "",
    val items: List<Item> = emptyList(),
    val footnotes: List<String> = emptyList(),
) {
    /** Numbered study questions, in book order - what a lesson's "N/M" count is over. */
    val questions: List<Item> get() = items.filter { it.type == ItemType.QUESTION }

    /** Stable key for this lesson's free-text Notes field (CLAUDE.md 5). */
    val notesId: String get() = "L${lesson}NOTES"
}

/**
 * One entry in a lesson's book-order [Lesson.items] list: a numbered study
 * question, or an unnumbered Children's Question. [id] is the stable key
 * answers are stored against - never the item's position in the list.
 */
@Serializable
data class Item(
    val id: String,
    val type: ItemType,
    val number: Int? = null,
    val text: String,
)

@Serializable
enum class ItemType {
    @SerialName("question") QUESTION,
    @SerialName("childrens") CHILDRENS,
}
