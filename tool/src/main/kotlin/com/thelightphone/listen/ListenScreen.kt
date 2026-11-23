package com.thelightphone.listen

import com.thelightphone.listen.artwork.ArtworkCache
import com.thelightphone.listen.books.BookLibrary
import com.thelightphone.listen.books.BookPositions
import com.thelightphone.listen.books.BookScreen
import com.thelightphone.listen.music.Album
import com.thelightphone.listen.music.AlbumScreen
import com.thelightphone.listen.music.Artist
import com.thelightphone.listen.music.ArtistScreen
import com.thelightphone.listen.music.Genre
import com.thelightphone.listen.music.GenreScreen
import com.thelightphone.listen.music.MusicLibrary
import com.thelightphone.listen.music.MusicLibraryState
import com.thelightphone.listen.music.Song
import com.thelightphone.listen.playback.BookNowPlayingScreen
import com.thelightphone.listen.playback.MusicNowPlayingScreen
import com.thelightphone.listen.playback.PlaybackHub
import com.thelightphone.listen.playback.QueueSource
import com.thelightphone.listen.playlists.AddToPlaylistScreen
import com.thelightphone.listen.playlists.Playlist
import com.thelightphone.listen.playlists.PlaylistEntry
import com.thelightphone.listen.playlists.playlistSongs
import com.thelightphone.listen.playlists.Playlists
import com.thelightphone.listen.podcasts.PodcastNowPlayingScreen
import com.thelightphone.listen.podcasts.Podcasts
import com.thelightphone.listen.storage.Settings
import com.thelightphone.listen.storage.StorageAccess
import com.thelightphone.listen.ui.ActionMenuScreen
import com.thelightphone.listen.ui.AppVisibility
import com.thelightphone.listen.ui.showNotice
import com.thelightphone.listen.ui.VolumeKeyScreen
import com.thelightphone.sdk.SealedLightActivity

/**
 * What every Listen screen does: each time it comes to the front (launch and resume) it
 * checks the library for changes and makes sure the player is open with the music spot
 * restored, and that the settings and playlists are read; when Listen goes to the background it saves the
 * music spot or the book's place.
 */
abstract class ListenScreen(private val sealed: SealedLightActivity) : VolumeKeyScreen<Unit>(sealed) {

    override fun willShow() {
        super.willShow()
        AppVisibility.set(true)
        ArtworkCache.setFilesDir(lightContext.filesDir)
        Podcasts.useNetworkCheck(lightContext.network)
        if (StorageAccess.hasAllFilesAccess()) {
            Settings.load()
            Playlists.load()
            BookPositions.load()
            Podcasts.load()
            MusicLibrary.refresh(lightContext.filesDir)
            BookLibrary.refresh(lightContext.filesDir)
            PlaybackHub.attach(sealed)
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        AppVisibility.set(false)
        PlaybackHub.saveNow()
    }

    /** Now Playing: the podcast player for an episode, the audiobook player for a book, else the music player. */
    protected fun openNowPlaying() {
        if (PlaybackHub.episode.value != null) {
            navigateTo(screenFactory = { PodcastNowPlayingScreen(it) })
        } else if (PlaybackHub.book.value != null) {
            navigateTo(screenFactory = { BookNowPlayingScreen(it) })
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
    protected fun addToPlaylist(genre: Genre) = openAddToPlaylist(genreEntry(genre))

    protected fun openGenre(genre: Genre) {
        navigateTo(screenFactory = { GenreScreen(it, genre.key) })
    }

    // ---- Press and hold: Play next, Add to queue, Add to playlist… ----

    protected fun showActions(song: Song) =
        showActions(song.title, "Song", QueueSource(), PlaylistEntry.song(song.path)) { library ->
            listOfNotNull(library.song(song.path) ?: song)
        }

    protected fun showActions(album: Album) =
        showActions(album.title, "Album", QueueSource(QueueSource.KIND_ALBUM, album.key), PlaylistEntry.album(album.artist, album.title)) { library ->
            (library.album(album.key) ?: album).songs
        }

    protected fun showActions(artist: Artist) =
        showActions(artist.name, "Artist", QueueSource(QueueSource.KIND_ARTIST, artist.key), PlaylistEntry.artist(artist.name)) { library ->
            (library.artist(artist.key) ?: artist).songs
        }

    protected fun showActions(genre: Genre) =
        showActions(genre.name, "Genre", QueueSource(QueueSource.KIND_GENRE, genre.key), genreEntry(genre)) { library ->
            (library.genre(genre.key) ?: genre).songs
        }

    /** A playlist can be queued, but not put in another playlist. */
    protected fun showActions(playlist: Playlist) =
        showActions(playlist.name, "Playlist", QueueSource(QueueSource.KIND_PLAYLIST, playlist.id), entry = null) { library ->
            Playlists.playlist(playlist.id)?.let { playlistSongs(it, library) }.orEmpty()
        }

    private fun showActions(
        name: String,
        what: String,
        source: QueueSource,
        entry: PlaylistEntry?,
        songs: (MusicLibraryState) -> List<Song>,
    ) {
        val actions = listOfNotNull(PLAY_NEXT, ADD_TO_QUEUE, entry?.let { ADD_TO_PLAYLIST })
        navigateTo(
            screenFactory = { ActionMenuScreen(it, name, what, actions) },
            resultCallback = { chosen ->
                when (actions.getOrNull(chosen)) {
                    PLAY_NEXT -> showNotice(PlaybackHub.enqueue(songs(MusicLibrary.state.value), next = true, source))
                    ADD_TO_QUEUE -> showNotice(PlaybackHub.enqueue(songs(MusicLibrary.state.value), next = false, source))
                    ADD_TO_PLAYLIST -> entry?.let(::openAddToPlaylist)
                }
            },
        )
    }

    private companion object {
        const val PLAY_NEXT = "Play next"
        const val ADD_TO_QUEUE = "Add to queue"
        const val ADD_TO_PLAYLIST = "Add to playlist…"

        /** "No Genre" is saved as an empty genre, which matches songs without one. */
        fun genreEntry(genre: Genre) = PlaylistEntry.genre(if (genre.isNoGenre) "" else genre.name)
    }
}
