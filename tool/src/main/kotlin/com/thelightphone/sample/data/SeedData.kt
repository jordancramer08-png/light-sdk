package com.thelightphone.sample.data

/**
 * A small made-up dataset so the lists have something in them before real
 * data is entered. Inserted once, on first launch, when the database is
 * empty (see [PrayerRepository.seedIfEmpty]).
 */
internal object SeedData {

    // Fixed ids keep the seed rows easy to refer to from here.
    private const val G_SMALL_GROUP = "seed-group-small"
    private const val G_BIBLE_STUDY = "seed-group-bible"
    private const val G_FAMILY = "seed-group-family"
    private const val G_CHURCH = "seed-group-church"

    val groups: List<Group> = listOf(
        Group(id = G_SMALL_GROUP, name = "Small Group", sortOrder = 0),
        Group(id = G_BIBLE_STUDY, name = "Bible Study", sortOrder = 1),
        Group(id = G_FAMILY, name = "Family", sortOrder = 2),
        Group(id = G_CHURCH, name = "Church", sortOrder = 3),
    )

    val people: List<Person> = listOf(
        Person(id = "seed-person-dave", name = "Dave Whitfield", groupId = G_SMALL_GROUP),
        Person(
            id = "seed-person-marcus",
            name = "Marcus Whitfield",
            groupId = G_SMALL_GROUP,
            note = "Dave's brother",
        ),
        Person(id = "seed-person-priya", name = "Priya Anand", groupId = G_SMALL_GROUP),
        Person(id = "seed-person-ellen", name = "Ellen Cho", groupId = G_BIBLE_STUDY),
        Person(id = "seed-person-tomasz", name = "Tomasz Nowak", groupId = G_BIBLE_STUDY),
        Person(
            id = "seed-person-grandma",
            name = "Grandma Ruth",
            groupId = G_FAMILY,
            note = "in the care home now",
        ),
        Person(id = "seed-person-aunt-sue", name = "Aunt Sue", groupId = G_FAMILY),
        Person(
            id = "seed-person-pastor-james",
            name = "Pastor James",
            groupId = G_CHURCH,
            note = "sabbatical through the spring",
        ),
        // A couple of ungrouped people so the "Ungrouped" row shows up.
        Person(id = "seed-person-neighbor-kim", name = "Kim next door"),
        Person(id = "seed-person-coworker-raj", name = "Raj (work)"),
    )

    /**
     * A handful of entries across all three types, including one answered
     * request, so the Requests / Praises / Updates tabs aren't all empty.
     * Timestamps are spread out backwards from [now].
     */
    fun entries(now: Long): List<Entry> {
        val day = 24L * 60 * 60 * 1000
        return listOf(
            Entry(
                id = "seed-entry-1",
                personId = "seed-person-dave",
                type = EntryType.REQUEST,
                text = "Job interview on Thursday - peace and clear thinking.",
                createdAt = now - 2 * day,
            ),
            Entry(
                id = "seed-entry-2",
                personId = "seed-person-dave",
                type = EntryType.UPDATE,
                text = "Moved into the new apartment over the weekend.",
                createdAt = now - 6 * day,
            ),
            Entry(
                id = "seed-entry-3",
                personId = "seed-person-priya",
                type = EntryType.REQUEST,
                text = "Recovery from knee surgery.",
                createdAt = now - 20 * day,
                answeredAt = now - 3 * day,
            ),
            Entry(
                id = "seed-entry-4",
                personId = "seed-person-priya",
                type = EntryType.PRAISE,
                text = "Surgery went well, home the same day.",
                createdAt = now - 12 * day,
            ),
            Entry(
                id = "seed-entry-5",
                personId = "seed-person-grandma",
                type = EntryType.REQUEST,
                text = "Settling in at the care home; less anxious in the evenings.",
                createdAt = now - 5 * day,
            ),
            Entry(
                id = "seed-entry-6",
                personId = "seed-person-grandma",
                type = EntryType.PRAISE,
                text = "Good long phone call on Sunday - sounded like herself.",
                createdAt = now - 1 * day,
            ),
            Entry(
                id = "seed-entry-7",
                personId = "seed-person-pastor-james",
                type = EntryType.UPDATE,
                text = "Sabbatical starts next month.",
                createdAt = now - 8 * day,
            ),
            Entry(
                id = "seed-entry-8",
                personId = "seed-person-neighbor-kim",
                type = EntryType.REQUEST,
                text = "Going through a hard stretch - open door to talk.",
                createdAt = now - 4 * day,
            ),
        )
    }
}
