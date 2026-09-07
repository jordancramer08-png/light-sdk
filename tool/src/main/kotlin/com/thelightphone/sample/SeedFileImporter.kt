package com.thelightphone.sample

import com.thelightphone.sample.data.PrayerRepository
import com.thelightphone.sample.data.SeedImport
import com.thelightphone.sdk.LightFileShare

/**
 * Runs the one-time JSON seed import (build order phase 6).
 *
 * Reads `prayer_seed.json` from the shared file directory
 * (`lightContext.fileShare`), hands its groups and people to the repository,
 * then renames the file so a later launch skips it. Safe to call on every
 * launch: once the file has been imported and renamed, this does nothing.
 *
 * The file lives in the app's shared directory. To put one there from a
 * computer against a debug build:
 * ```
 * adb push prayer_seed.json /data/local/tmp/prayer_seed.json
 * adb shell run-as com.thelightphone.sample \
 *   cp /data/local/tmp/prayer_seed.json files/shared/prayer_seed.json
 * ```
 */
class SeedFileImporter(
    private val fileShare: LightFileShare,
    private val repository: PrayerRepository,
) {

    /** Blocking; call from a background coroutine. [now] stamps the renamed file. */
    fun runOnce(now: Long) {
        val text = fileShare.read(SeedImport.FILE_NAME) { it.readText() } ?: return

        val seed = try {
            SeedImport.parse(text)
        } catch (_: Exception) {
            // Malformed file: leave it in place so it can be fixed and retried.
            return
        }

        repository.importSeed(seed)

        // LightFileShare has no rename: copy to an archived name, then delete.
        fileShare.write(SeedImport.archivedName(now)) { it.write(text) }
        fileShare.delete(SeedImport.FILE_NAME)
    }
}
