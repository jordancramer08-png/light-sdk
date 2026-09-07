package com.thelightphone.sample.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The three kinds of note kept for a person. Only REQUEST entries can be
 * marked answered (see [Entry.answeredAt]).
 */
enum class EntryType { REQUEST, PRAISE, UPDATE }

/**
 * A named group of people, e.g. "Small Group" or "Family".
 * Groups are shown in [sortOrder] order on the home screen.
 */
@Entity(tableName = "prayer_groups")
data class Group(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)

/**
 * A person I pray for. Belongs to at most one [Group]; a null [groupId]
 * means "ungrouped". Archiving hides the person from lists but keeps every
 * entry.
 */
@Entity(
    tableName = "people",
    indices = [Index("groupId")],
)
data class Person(
    @PrimaryKey val id: String,
    val name: String,
    val groupId: String? = null,
    val note: String? = null,
    val archived: Boolean = false,
)

/**
 * A single note about a person: a prayer request, a praise, or a life
 * update. [answeredAt] is only ever set for REQUEST entries; non-null means
 * the request has been answered.
 */
@Entity(
    tableName = "entries",
    indices = [Index("personId")],
)
data class Entry(
    @PrimaryKey val id: String,
    val personId: String,
    val type: EntryType,
    val text: String,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val archived: Boolean = false,
)
