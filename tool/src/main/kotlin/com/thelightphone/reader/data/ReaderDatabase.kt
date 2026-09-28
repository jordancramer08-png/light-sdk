package com.thelightphone.reader.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
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
 */
@Database(
    entities = [ReadingPosition::class, ReadingList::class, ReadingListBook::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun readingListDao(): ReadingListDao

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

/** The shared database, opened (and migrated if needed) the first time it's asked for. */
fun SealedLightContext.readerDatabase(): ReaderDatabase =
    ReaderDatabase.getInstance { buildDatabase(ReaderDatabase::class.java, ReaderDatabase.NAME) }
