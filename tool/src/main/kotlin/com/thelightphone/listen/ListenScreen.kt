package com.thelightphone.listen

import com.thelightphone.listen.artwork.ArtworkCache
import com.thelightphone.listen.books.BookLibrary
import com.thelightphone.listen.books.BookPositions
import com.thelightphone.listen.books.BookScreen
import com.thelightphone.listen.music.Album
import com.thelightphone.listen.music.AlbumScreen
import com.thelightphone.listen.music.Artist
import com.thelightphone.listen.music.ArtistScreen
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.playback.MusicNowPlayingScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playlists.AddToPlaylistScreen
import com.thelightphone.listen.playlists.PlaylistEntry
import com.thelightphone.listen.playlists.Playlists
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * What every Listen screen does: each time it comes to the front (launch and resume) it
 * checks the library for changes and makes sure the player is open with the music spot
 * restored, and that the settings and playlists are read; when Listen goes to the background it saves the
 * music spot.
 */
abstract class ListenScreen(private val sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    override fun willShow() {
        super.willShow()
        ArtworkCache.setFilesDir(lightContext.filesDir)
        if (StorageAccess.hasAllFilesAccess()) {
            Settings.load()
            Playlists.load()
            BookPositions.load()
            MusicLibrary.refresh(lightContext.filesDir)
            BookLibrary.refresh(lightContext.filesDir)
            PlaybackHub.attach(sealed)
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        PlaybackHub.saveNow()
    }

    /** Now Playing; while a book is loaded that's its book screen (its own player comes in Session 6). */
    protected fun openNowPlaying() {
        val book = PlaybackHub.book.value
        if (book != null) {
            openBook(book.id)
        } else {
            navigateTo(screenFactory = { MusicNowPlayingScreen(it) })
        }
    }

    protected fun openBook(id: String) {
        navigateTo(screenFactory = { BookScreen(it, id) })
    }

    protected fun openAlbum(album: Album) {
        navigateTo(screenFactory = { AlbumScreen(it, album.key) })
    }

    protected fun openArtist(artist: Artist) {
        navigateTo(screenFactory = { ArtistScreen(it, artist.key) })
    }

    /** "Add to playlist…" for a song, an album or an artist. */
    protected fun openAddToPlaylist(entry: PlaylistEntry) {
        navigateTo(screenFactory = { AddToPlaylistScreen(it, entry) })
    }

    protected fun addToPlaylist(song: Song) = openAddToPlaylist(PlaylistEntry.song(song.path))
    protected fun addToPlaylist(album: Album) = openAddToPlaylist(PlaylistEntry.album(album.artist, album.title))
    protected fun addToPlaylist(artist: Artist) = openAddToPlaylist(PlaylistEntry.artist(artist.name))
}
