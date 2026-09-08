package com.thelightphone.sample

/**
 * Shared singular / plural phrasing for the delete confirmation screens, so
 * "1 entry" / "3 entries" reads the same everywhere.
 */
internal fun countPeople(count: Int): String =
    if (count == 1) "1 person" else "$count people"

internal fun countEntries(count: Int): String =
    if (count == 1) "1 entry" else "$count entries"
