package com.thelightphone.reader.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
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
 * Checks the database upgrade from version 1 (saved places only) to version 2 (plus
 * reading lists) keeps every saved reading position.
 *
 * The build rules allow no SQLite engine in PC tests, so this runs Room's generated
 * Migration against a stand-in connection that records each SQL statement, then checks
 * those statements against the schemas Room saved in `tool/schemas/`. On the phone,
 * Room also compares the migrated tables with version 2 before opening the database.
 */
class ReaderDatabaseMigrationTest {

    private val schemaDir = File("schemas/com.thelightphone.reader.data.ReaderDatabase")

    private fun runMigration(): List<String> {
        val connection = RecordingConnection()
        val migration = ReaderDatabase_AutoMigration_1_2_Impl()
        migration.migrate(connection)
        return connection.statements
    }

    /** The schema's tables by name. */
    private fun entities(version: Int): Map<String, JsonObject> {
        val root = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject
        return root.getValue("database").jsonObject.getValue("entities").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("tableName").jsonPrimitive.content }
    }

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
