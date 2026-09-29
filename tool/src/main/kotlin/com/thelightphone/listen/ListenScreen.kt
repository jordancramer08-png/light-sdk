package com.thelightphone.listen

import com.thelightphone.listen.artwork.ArtworkCache
import com.thelightphone.listen.music.Album
import com.thelightphone.listen.music.AlbumScreen
import com.thelightphone.listen.music.Artist
import com.thelightphone.listen.music.ArtistScreen
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.playback.MusicNowPlayingScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * What every Listen screen does: each time it comes to the front (launch and resume) it
 * checks the library for changes and makes sure the player is open with the music spot
 * restored, and that the settings are read; when Listen goes to the background it saves the
 * music spot.
 */
abstract class ListenScreen(private val sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    override fun willShow() {
        super.willShow()
        ArtworkCache.setFilesDir(lightContext.filesDir)
        if (StorageAccess.hasAllFilesAccess()) {
            Settings.load()
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

    protected fun openAlbum(album: Album) {
        navigateTo(screenFactory = { AlbumScreen(it, album.key) })
    }

    protected fun openArtist(artist: Artist) {
        navigateTo(screenFactory = { ArtistScreen(it, artist.key) })
    }
}
