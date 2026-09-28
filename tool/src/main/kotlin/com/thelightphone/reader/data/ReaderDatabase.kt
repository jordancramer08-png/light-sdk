package com.thelightphone.reader.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

/**
 * The app's one database, `reading_position.db`.
 *
 * - Version 1 (the September app onward): saved reading positions.
 * - Version 2: adds reading lists. The 1 -> 2 step only creates the two new tables, so
 *   every saved place is kept. It is a Room [AutoMigration]: Room compares the saved schemas
 *   in `tool/schemas/` and generates the Migration class (`ReaderDatabase_AutoMigration_1_2_Impl`)
 *   at build time. It has to be declared here because the SDK's `buildDatabase` has no way
 *   to pass a hand-written Migration in. There is deliberately no destructive fallback: if a
 *   migration were ever missing, the app would stop with an error rather than wipe places.
 * - Version 3: adds reading status. The 2 -> 3 step creates the new table, then
 *   [MigrationToVersion3] fills it in (see there). Saved places and reading lists are
 *   left exactly as they were.
 */
@Database(
    entities = [ReadingPosition::class, ReadingList::class, ReadingListBook::class, BookStatus::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = MigrationToVersion3::class),
    ],
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun readingListDao(): ReadingListDao
    abstract fun bookStatusDao(): BookStatusDao

    companion object {
        /** The September app's file name — keep it, or old saved places are lost. */
        const val NAME = "reading_position.db"

        @Volatile
        private var instance: ReaderDatabase? = null

        /** One open database for the whole app, shared by every repository. */
        fun getInstance(build: () -> ReaderDatabase): ReaderDatabase {
            return instance ?: synchronized(this) {
                instance ?: build().also { instance = it }
            }
        }
    }
}

/**
 * The hand-written part of the 2 -> 3 upgrade, run right after Room creates the
 * `reading_status` table: every book that already has a saved place was opened before
 * statuses existed, so it starts as Reading rather than Want to Read. It only reads
 * `reading_position`; it never changes it.
 */
class MigrationToVersion3 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(BACKFILL_READING_STATUS_SQL)
    }

    companion object {
        const val BACKFILL_READING_STATUS_SQL =
            "INSERT OR IGNORE INTO reading_status (bookSlug, status, updatedAt) " +
                "SELECT bookSlug, 'READING', updatedAt FROM reading_position"
    }
}

/** The shared database, opened (and migrated if needed) the first time it's asked for. */
fun SealedLightContext.readerDatabase(): ReaderDatabase =
    ReaderDatabase.getInstance { buildDatabase(ReaderDatabase::class.java, ReaderDatabase.NAME) }
