package com.thelightphone.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingStatusTest {

    @Test
    fun savedStatusRoundTripsAndUnknownIsWantToRead() {
        ReadingStatus.entries.forEach { assertEquals(it, ReadingStatus.fromSavedName(it.name)) }
        assertEquals(ReadingStatus.WANT_TO_READ, ReadingStatus.fromSavedName(null))
        assertEquals(ReadingStatus.WANT_TO_READ, ReadingStatus.fromSavedName("ABANDONED"))
    }

    @Test
    fun openingMakesAWantToReadBookReading() {
        assertEquals(ReadingStatus.READING, statusAfterOpening(ReadingStatus.WANT_TO_READ))
    }

    @Test
    fun openingLeavesReadingAndFinishedAlone() {
        assertNull(statusAfterOpening(ReadingStatus.READING))
        assertNull(statusAfterOpening(ReadingStatus.FINISHED))
    }

    @Test
    fun savedFilterRoundTripsAndUnknownIsAll() {
        LibraryFilter.entries.forEach { assertEquals(it, LibraryFilter.fromSavedName(it.name)) }
        assertEquals(LibraryFilter.ALL, LibraryFilter.fromSavedName(null))
        assertEquals(LibraryFilter.ALL, LibraryFilter.fromSavedName("SOMETHING_OLD"))
    }

    @Test
    fun allShowsEveryStatusAndEachOtherFilterOnlyItsOwn() {
        ReadingStatus.entries.forEach { assertTrue(LibraryFilter.ALL.shows(it)) }
        assertTrue(LibraryFilter.FINISHED.shows(ReadingStatus.FINISHED))
        assertFalse(LibraryFilter.FINISHED.shows(ReadingStatus.READING))
        assertFalse(LibraryFilter.READING.shows(ReadingStatus.WANT_TO_READ))
        assertTrue(LibraryFilter.WANT_TO_READ.shows(ReadingStatus.WANT_TO_READ))
    }

    @Test
    fun emptyFilterMessages() {
        assertEquals("No books on this device yet.", emptyFilterText(LibraryFilter.ALL))
        assertEquals("No books marked Finished.", emptyFilterText(LibraryFilter.FINISHED))
        assertEquals("No books marked Want to Read.", emptyFilterText(LibraryFilter.WANT_TO_READ))
    }
}
