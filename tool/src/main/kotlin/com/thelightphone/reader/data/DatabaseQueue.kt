package com.thelightphone.reader.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every reading-list and reading-status read and write goes through here, one at a time
 * and in order, off the main thread. So a change made just before leaving a screen is always saved (even
 * though the screen is gone), and the next screen always reads it back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object DatabaseQueue {
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Saves in the background; the caller doesn't wait. */
    fun write(change: () -> Unit) {
        scope.launch { change() }
    }

    /** Waits for earlier writes, then reads. */
    suspend fun <T> read(query: () -> T): T = withContext(dispatcher) { query() }
}
