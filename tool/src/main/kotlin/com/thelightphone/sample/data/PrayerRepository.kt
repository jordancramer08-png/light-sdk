package com.thelightphone.sample.data

import java.util.UUID

/**
 * The one place the rest of the app talks to for prayer-list data. Wraps
 * [PrayerDao] with plain domain methods and generates ids for new rows.
 *
 * Every method here is blocking - call it from a background coroutine
 * (`Dispatchers.IO`), the same way the authenticator example does.
 */
class PrayerRepository private constructor(database: PrayerDatabase) {

    private val dao = database.prayerDao()

    // --- Groups ------------------------------------------------------------

    fun listGroups(): List<Group> = dao.listGroups()

    fun getGroup(groupId: String): Group? = dao.getGroup(groupId)

    /** Creates a group at the end of the list. Returns the new id. */
    fun addGroup(name: String): String {
        val id = newId()
        val nextOrder = (dao.listGroups().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.insertGroup(Group(id = id, name = name, sortOrder = nextOrder))
        return id
    }

    fun renameGroup(groupId: String, name: String) {
        val group = dao.getGroup(groupId) ?: return
        dao.updateGroup(group.copy(name = name))
    }

    fun setGroupSortOrder(groupId: String, sortOrder: Int) {
        val group = dao.getGroup(groupId) ?: return
        dao.updateGroup(group.copy(sortOrder = sortOrder))
    }

    /** Deleting a group leaves its people in place, just ungrouped. */
    fun deleteGroup(groupId: String) {
        dao.clearGroupFromPeople(groupId)
        dao.deleteGroup(groupId)
    }

    // --- People ----------------------------------------------------------

    fun getPerson(personId: String): Person? = dao.getPerson(personId)

    fun listPeopleInGroup(groupId: String): List<Person> = dao.listPeopleInGroup(groupId)

    fun listUngroupedPeople(): List<Person> = dao.listUngroupedPeople()

    fun listActivePeople(): List<Person> = dao.listActivePeople()

    fun listArchivedPeople(): List<Person> = dao.listArchivedPeople()

    fun addPerson(name: String, groupId: String? = null, note: String? = null): String {
        val id = newId()
        dao.insertPerson(Person(id = id, name = name, groupId = groupId, note = note))
        return id
    }

    fun renamePerson(personId: String, name: String) {
        val person = dao.getPerson(personId) ?: return
        dao.updatePerson(person.copy(name = name))
    }

    fun setPersonNote(personId: String, note: String?) {
        val person = dao.getPerson(personId) ?: return
        dao.updatePerson(person.copy(note = note))
    }

    fun movePerson(personId: String, groupId: String?) = dao.setPersonGroup(personId, groupId)

    fun archivePerson(personId: String) = dao.setPersonArchived(personId, true)

    fun unarchivePerson(personId: String) = dao.setPersonArchived(personId, false)

    // --- Entries ---------------------------------------------------------

    fun getEntry(entryId: String): Entry? = dao.getEntry(entryId)

    fun listEntries(personId: String, type: EntryType): List<Entry> =
        dao.listEntries(personId, type)

    fun addEntry(personId: String, type: EntryType, text: String, createdAt: Long): String {
        val id = newId()
        dao.insertEntry(
            Entry(id = id, personId = personId, type = type, text = text, createdAt = createdAt),
        )
        return id
    }

    fun editEntryText(entryId: String, text: String) {
        val entry = dao.getEntry(entryId) ?: return
        dao.updateEntry(entry.copy(text = text))
    }

    /**
     * Replaces an entry's text and type. Moving a REQUEST to another type
     * clears its answered state, since only requests can be answered.
     */
    fun updateEntry(entryId: String, text: String, type: EntryType) {
        val entry = dao.getEntry(entryId) ?: return
        dao.updateEntry(
            entry.copy(
                text = text,
                type = type,
                answeredAt = if (type == EntryType.REQUEST) entry.answeredAt else null,
            ),
        )
    }

    /** REQUEST entries only; [answeredAt] null clears the answered state. */
    fun setEntryAnswered(entryId: String, answeredAt: Long?) =
        dao.setEntryAnswered(entryId, answeredAt)

    fun deleteEntry(entryId: String) = dao.deleteEntry(entryId)

    fun archiveEntry(entryId: String) = dao.setEntryArchived(entryId, true)

    // --- JSON seed import --------------------------------------------------

    /**
     * Adds the groups and people from a parsed [SeedFile]. Additive: a group
     * whose name already exists (case-insensitive) is reused rather than
     * duplicated; people are always added. Blank names are skipped. The caller
     * ([com.thelightphone.sample.SeedFileImporter]) renames the source file
     * afterwards so this never runs twice for the same file. Returns how many
     * people were added.
     */
    fun importSeed(seed: SeedFile): Int {
        val groupIdsByName = HashMap<String, String>()
        dao.listGroups().forEach { groupIdsByName[it.name.trim().lowercase()] = it.id }

        var peopleAdded = 0
        seed.groups.forEach { seedGroup ->
            val groupName = seedGroup.name.trim()
            if (groupName.isEmpty()) return@forEach
            val key = groupName.lowercase()
            val groupId = groupIdsByName[key] ?: addGroup(groupName).also { groupIdsByName[key] = it }
            seedGroup.people.forEach { person ->
                if (addSeedPerson(person, groupId)) peopleAdded++
            }
        }
        seed.ungrouped.forEach { person ->
            if (addSeedPerson(person, groupId = null)) peopleAdded++
        }
        return peopleAdded
    }

    private fun addSeedPerson(person: SeedPerson, groupId: String?): Boolean {
        val name = person.name.trim()
        if (name.isEmpty()) return false
        addPerson(name = name, groupId = groupId, note = person.note?.trim()?.ifEmpty { null })
        return true
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        const val DATABASE_NAME = "prayer_list.db"

        @Volatile
        private var instance: PrayerRepository? = null

        fun getInstance(databaseProvider: () -> PrayerDatabase): PrayerRepository {
            return instance ?: synchronized(this) {
                instance ?: PrayerRepository(databaseProvider()).also { instance = it }
            }
        }
    }
}
