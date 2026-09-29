package com.thelightphone.listen

import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.playback.MusicNowPlayingScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * What every Listen screen does: each time it comes to the front (launch and resume) it
 * checks the library for changes and makes sure the player is open with the music spot
 * restored; when Listen goes to the background it saves the music spot.
 */
abstract class ListenScreen(private val sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    override fun willShow() {
        super.willShow()
        if (StorageAccess.hasAllFilesAccess()) {
            MusicLibrary.refresh(lightContext.filesDir)
            PlaybackHub.attach(sealed)
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        PlaybackHub.saveNow()
    }

    protected fun openNowPlaying() {
        navigateTo(screenFactory = { MusicNowPlayingScreen(it) })
    }
}
