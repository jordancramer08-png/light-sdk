package com.thelightphone.listen.music

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/** Reads a song file's tags on the phone. Always call off the main thread. */
object TagReader {

    /** The file's tags, or null if Android can't read the file at all. */
    fun read(file: File): RawTags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            RawTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Can't read tags of ${file.path}: $e")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private const val TAG = "Listen"
}
