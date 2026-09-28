package com.thelightphone.reader.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.thelightphone.reader.ReadingStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks each database upgrade keeps every saved reading position (and, from version 2 on,
 * every reading list): 1 -> 2 adds reading lists, 2 -> 3 adds reading status.
 *
 * The build rules allow no SQLite engine in PC tests, so this runs Room's generated
 * Migrations against a stand-in connection that records each SQL statement, then checks
 * those statements against the schemas Room saved in `tool/schemas/`. On the phone,
 * Room also compares the migrated tables with the new version before opening the database.
 */
class ReaderDatabaseMigrationTest {

    private val schemaDir = File("schemas/com.thelightphone.reader.data.ReaderDatabase")

    private fun statementsOf(migration: Migration): List<String> {
        val connection = RecordingConnection()
        migration.migrate(connection)
        return connection.statements
    }

    private fun runMigration(): List<String> = statementsOf(ReaderDatabase_AutoMigration_1_2_Impl())

    private fun runMigrationTo3(): List<String> = statementsOf(ReaderDatabase_AutoMigration_2_3_Impl())

    /** The schema's tables by name. */
    private fun entities(version: Int): Map<String, JsonObject> {
        val root = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject
        return root.getValue("database").jsonObject.getValue("entities").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("tableName").jsonPrimitive.content }
    }

    // --- 1 -> 2: reading lists -------------------------------------------------

    @Test
    fun migratesFromVersion1To2() {
        val migration = ReaderDatabase_AutoMigration_1_2_Impl()
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
    }

    @Test
    fun onlyCreatesNewTables() {
        val statements = runMigration()
        assertTrue(statements.isNotEmpty())
        for (sql in statements) {
            assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS"), "unexpected statement: $sql")
        }
    }

    @Test
    fun neverTouchesSavedPositions() {
        for (sql in runMigration()) {
            assertFalse(sql.contains("reading_position"), "migration touches saved places: $sql")
            for (word in listOf("DROP", "DELETE", "ALTER", "UPDATE", "INSERT", "RENAME")) {
                assertFalse(sql.uppercase().contains(word), "migration contains $word: $sql")
            }
        }
    }

    @Test
    fun savedPositionsTableIsIdenticalInBothVersions() {
        assertEquals(entities(1).getValue("reading_position"), entities(2).getValue("reading_position"))
    }

    @Test
    fun createsExactlyTheVersion2ListTables() {
        val v1 = entities(1)
        val newTables = entities(2).filterKeys { it !in v1 }
        assertEquals(setOf("reading_list", "reading_list_book"), newTables.keys)

        val expected = newTables.map { (name, entity) ->
            entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name)
        }.toSet()
        assertEquals(expected, runMigration().toSet())
    }

    // --- 2 -> 3: reading status ------------------------------------------------

    @Test
    fun migratesFromVersion2To3() {
        val migration = ReaderDatabase_AutoMigration_2_3_Impl()
        assertEquals(2, migration.startVersion)
        assertEquals(3, migration.endVersion)
    }

    @Test
    fun version3IsTheCurrentVersionAndEveryStepHasAMigration() {
        val latest = schemaDir.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals(3, latest)
        val steps = listOf(ReaderDatabase_AutoMigration_1_2_Impl(), ReaderDatabase_AutoMigration_2_3_Impl())
        assertEquals((1 until latest).map { it to it + 1 }, steps.map { it.startVersion to it.endVersion })
    }

    @Test
    fun createsTheStatusTableThenFillsIt() {
        val newTables = entities(3).filterKeys { it !in entities(2) }
        assertEquals(setOf("reading_status"), newTables.keys)
        val createSql = newTables.getValue("reading_status").getValue("createSql").jsonPrimitive.content
            .replace("\${TABLE_NAME}", "reading_status")

        assertEquals(listOf(createSql, MigrationToVersion3.BACKFILL_READING_STATUS_SQL), runMigrationTo3())
    }

    @Test
    fun booksWithASavedPlaceStartAsReading() {
        val backfill = MigrationToVersion3.BACKFILL_READING_STATUS_SQL
        assertTrue(backfill.startsWith("INSERT OR IGNORE INTO reading_status "))
        assertTrue(backfill.contains("FROM reading_position"))
        assertTrue(backfill.contains("'${ReadingStatus.READING.name}'"))
    }

    @Test
    fun version3OnlyReadsSavedPlacesAndListsNeverChangesThem() {
        for (sql in runMigrationTo3()) {
            val upper = sql.uppercase()
            // Whole words only: the column `updatedAt` isn't an UPDATE.
            for (word in listOf("DROP", "DELETE", "ALTER", "UPDATE", "RENAME", "REPLACE")) {
                assertFalse(Regex("\\b$word\\b").containsMatchIn(upper), "migration contains $word: $sql")
            }
            assertFalse(sql.contains("reading_list"), "migration touches reading lists: $sql")
            if (upper.startsWith("INSERT")) {
                assertTrue(sql.startsWith("INSERT OR IGNORE INTO reading_status "), "writes elsewhere: $sql")
            } else {
                assertFalse(sql.contains("reading_position"), "migration touches saved places: $sql")
            }
        }
    }

    @Test
    fun savedPlacesAndListTablesAreIdenticalInVersions2And3() {
        val v2 = entities(2)
        val v3 = entities(3)
        for (table in listOf("reading_position", "reading_list", "reading_list_book")) {
            assertEquals(v2.getValue(table), v3.getValue(table), "table $table changed")
        }
    }
}

/** A connection that runs nothing and remembers every statement it was asked to run. */
private class RecordingConnection : SQLiteConnection {
    val statements = mutableListOf<String>()

    override fun prepare(sql: String): SQLiteStatement {
        statements += sql
        return NoOpStatement
    }

    override fun close() = Unit
}

private object NoOpStatement : SQLiteStatement {
    override fun bindBlob(index: Int, value: ByteArray) = Unit
    override fun bindDouble(index: Int, value: Double) = Unit
    override fun bindLong(index: Int, value: Long) = Unit
    override fun bindText(index: Int, value: String) = Unit
    override fun bindNull(index: Int) = Unit
    override fun getBlob(index: Int) = ByteArray(0)
    override fun getDouble(index: Int) = 0.0
    override fun getLong(index: Int) = 0L
    override fun getText(index: Int) = ""
    override fun isNull(index: Int) = true
    override fun getColumnCount() = 0
    override fun getColumnName(index: Int) = ""
    override fun getColumnType(index: Int) = 0
    override fun step() = false
    override fun reset() = Unit
    override fun clearBindings() = Unit
    override fun close() = Unit
}
