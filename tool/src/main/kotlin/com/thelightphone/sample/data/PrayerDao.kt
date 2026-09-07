package com.thelightphone.sample.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * All database access for the prayer list. Every method is blocking; callers
 * run it off the main thread (see [PrayerRepository]).
 */
@Dao
interface PrayerDao {

    // --- Groups ---------------------------------------------------------------

    @Insert
    fun insertGroup(group: Group)

    @Update
    fun updateGroup(group: Group)

    @Query("DELETE FROM prayer_groups WHERE id = :groupId")
    fun deleteGroup(groupId: String)

    @Query("SELECT * FROM prayer_groups ORDER BY sortOrder")
    fun listGroups(): List<Group>

    @Query("SELECT * FROM prayer_groups WHERE id = :groupId")
    fun getGroup(groupId: String): Group?

    // --- People ------------------------------------------------------------

    @Insert
    fun insertPerson(person: Person)

    @Update
    fun updatePerson(person: Person)

    @Query("SELECT * FROM people WHERE id = :personId")
    fun getPerson(personId: String): Person?

    /** Active (non-archived) people in one group, alphabetical. */
    @Query(
        "SELECT * FROM people WHERE groupId = :groupId AND archived = 0 " +
            "ORDER BY name COLLATE NOCASE"
    )
    fun listPeopleInGroup(groupId: String): List<Person>

    /** Active people with no group, alphabetical. */
    @Query(
        "SELECT * FROM people WHERE groupId IS NULL AND archived = 0 " +
            "ORDER BY name COLLATE NOCASE"
    )
    fun listUngroupedPeople(): List<Person>

    /** Every active person, used for group person-counts on the home screen. */
    @Query("SELECT * FROM people WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun listActivePeople(): List<Person>

    @Query("SELECT * FROM people WHERE archived = 1 ORDER BY name COLLATE NOCASE")
    fun listArchivedPeople(): List<Person>

    /** Used when a group is deleted: its people become ungrouped. */
    @Query("UPDATE people SET groupId = NULL WHERE groupId = :groupId")
    fun clearGroupFromPeople(groupId: String)

    @Query("UPDATE people SET groupId = :groupId WHERE id = :personId")
    fun setPersonGroup(personId: String, groupId: String?)

    @Query("UPDATE people SET archived = :archived WHERE id = :personId")
    fun setPersonArchived(personId: String, archived: Boolean)

    // --- Entries ---------------------------------------------------------------

    @Insert
    fun insertEntry(entry: Entry)

    @Update
    fun updateEntry(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :entryId")
    fun deleteEntry(entryId: String)

    @Query("SELECT * FROM entries WHERE id = :entryId")
    fun getEntry(entryId: String): Entry?

    /** Active entries of one type for a person, newest first. */
    @Query(
        "SELECT * FROM entries WHERE personId = :personId AND type = :type " +
            "AND archived = 0 ORDER BY createdAt DESC"
    )
    fun listEntries(personId: String, type: EntryType): List<Entry>

    @Query("UPDATE entries SET answeredAt = :answeredAt WHERE id = :entryId")
    fun setEntryAnswered(entryId: String, answeredAt: Long?)

    @Query("UPDATE entries SET archived = :archived WHERE id = :entryId")
    fun setEntryArchived(entryId: String, archived: Boolean)

    // --- Seed guard ----------------------------------------------------------

    @Query("SELECT COUNT(*) FROM people")
    fun personCount(): Int
}
