package dev.jdtech.jellyfin.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidFolder
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider

/** A play queue together with the item (and position) it should start at. */
data class AutoQueue(val items: List<MediaItem>, val startIndex: Int, val startPositionMs: Long)

/**
 * The browsable media tree exposed to Android Auto, backed by the Jellyfin repository:
 * ```
 * Root
 * ├── Continue Watching  (movies, episodes)
 * ├── Next Up            (episodes)
 * ├── Libraries
 * │   └── Library / Folder / Box set ─ movies, shows, nested folders
 * │       └── Show ─ Season ─ Episodes
 * └── Favorites
 * ```
 *
 * In offline mode the server is not available, so Libraries and Favorites are replaced by Downloads
 * (movies, shows ─ seasons ─ episodes), which is what the offline repository can serve. Downloads
 * is only offered there: online, a show's seasons and episodes come from the server and would
 * include ones that were not downloaded.
 *
 * Android Auto only shows audio-style UI while driving, so playable items are played audio-only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AutoMediaTree
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
    private val repositoryProvider: Provider<JellyfinRepository>,
) {
    private val streamUris = ConcurrentHashMap<UUID, Uri>()

    /** Whether a server and user are configured, i.e. whether anything can be browsed. */
    val isSignedIn: Boolean
        get() = jellyfinApi.userId != null

    /** Whether the app is in offline mode, which switches [repositoryProvider] to the database. */
    val isOffline: Boolean
        get() = appPreferences.getValue(appPreferences.offlineMode)

    fun clearCaches() = streamUris.clear()

    fun rootItem(recent: Boolean = false): MediaItem =
        if (recent) {
            folder(AutoMediaId.Recent, appName())
        } else {
            folder(AutoMediaId.Root, appName())
        }

    suspend fun getItem(id: AutoMediaId): MediaItem? =
        when (id) {
            AutoMediaId.Root -> rootItem()
            AutoMediaId.Recent -> rootItem(recent = true)
            AutoMediaId.ContinueWatching,
            AutoMediaId.NextUp,
            AutoMediaId.Libraries,
            AutoMediaId.Favorites,
            AutoMediaId.Downloads -> staticFolders().firstOrNull { it.mediaId == id.value }
            // The offline repository can only look things up by type, not with getItem.
            is AutoMediaId.Movie -> repositoryProvider.get().getMovie(id.id).toMediaItem()
            is AutoMediaId.Episode -> repositoryProvider.get().getEpisode(id.id).toMediaItem()
            is AutoMediaId.Show -> repositoryProvider.get().getShow(id.id).toMediaItem()
            is AutoMediaId.Season -> repositoryProvider.get().getSeason(id.id).toMediaItem()
            is AutoMediaId.Library,
            is AutoMediaId.Folder ->
                id.jellyfinItemId?.let { repositoryProvider.get().getItem(it)?.toMediaItem() }
        }

    suspend fun getChildren(parent: AutoMediaId, page: Int, pageSize: Int): List<MediaItem> {
        val repository = repositoryProvider.get()
        return when (parent) {
            AutoMediaId.Root -> staticChildren().paged(page, pageSize)
            AutoMediaId.Recent ->
                repository
                    .getResumeItems()
                    .mapNotNull { it.toMediaItem() }
                    .filter { it.mediaMetadata.isPlayable == true }
                    .take(1)
                    .paged(page, pageSize)
            AutoMediaId.ContinueWatching ->
                repository.getResumeItems().mapNotNull { it.toMediaItem() }.paged(page, pageSize)
            AutoMediaId.NextUp ->
                repository.getNextUp().mapNotNull { it.toMediaItem() }.paged(page, pageSize)
            AutoMediaId.Libraries ->
                if (isOffline) {
                    emptyList()
                } else {
                    repository.getLibraries().mapNotNull { it.toMediaItem() }.paged(page, pageSize)
                }
            // The offline repository does not implement favorites.
            AutoMediaId.Favorites ->
                if (isOffline) {
                    emptyList()
                } else {
                    repository
                        .getFavoriteItems()
                        .mapNotNull { it.toMediaItem() }
                        .paged(page, pageSize)
                }
            AutoMediaId.Downloads ->
                repository.getDownloads().mapNotNull { it.toMediaItem() }.paged(page, pageSize)
            is AutoMediaId.Library -> childrenOf(repository, parent.id, page, pageSize)
            is AutoMediaId.Folder -> childrenOf(repository, parent.id, page, pageSize)
            is AutoMediaId.Show ->
                repository
                    .getSeasons(parent.id)
                    .mapNotNull { it.toMediaItem() }
                    .paged(page, pageSize)
            is AutoMediaId.Season ->
                repository
                    .getEpisodes(seriesId = parent.seriesId, seasonId = parent.id)
                    .filter { !it.missing }
                    .mapNotNull { it.toMediaItem() }
                    .paged(page, pageSize)
            is AutoMediaId.Movie,
            is AutoMediaId.Episode -> emptyList()
        }
    }

    suspend fun search(query: String): List<MediaItem> =
        repositoryProvider.get().getSearchItems(query).mapNotNull { it.toMediaItem() }

    /**
     * Builds the queue for playing [id]: a movie plays on its own, an episode queues the rest of
     * its season, and a show or season starts with its next up (or first) episode.
     */
    suspend fun buildQueue(id: AutoMediaId): AutoQueue? {
        val repository = repositoryProvider.get()
        return when (id) {
            is AutoMediaId.Movie ->
                repository.getMovie(id.id).let { movie ->
                    AutoQueue(
                        items = listOfNotNull(movie.toMediaItem()),
                        startIndex = 0,
                        startPositionMs = movie.startPositionMs(),
                    )
                }
            is AutoMediaId.Episode -> {
                val episode = repository.getEpisode(id.id)
                val episodes =
                    repository.getEpisodes(episode.seriesId, episode.seasonId).filter {
                        !it.missing
                    }
                episodeQueue(episodes.ifEmpty { listOf(episode) }, startEpisodeId = episode.id)
            }
            is AutoMediaId.Season ->
                episodeQueue(
                    repository.getEpisodes(id.seriesId, id.id).filter { !it.missing },
                    startEpisodeId = null,
                )
            is AutoMediaId.Show -> {
                val nextUp = repository.getNextUp(id.id).firstOrNull()
                val seasonId =
                    nextUp?.seasonId
                        ?: repository.getSeasons(id.id).firstOrNull()?.id
                        ?: return null
                episodeQueue(
                    repository.getEpisodes(id.id, seasonId).filter { !it.missing },
                    startEpisodeId = nextUp?.id,
                )
            }
            else -> null
        }
    }

    /** The queue to play for a voice search, or for "just play something" (a blank query). */
    suspend fun buildQueueForSearch(query: String): AutoQueue? {
        val repository = repositoryProvider.get()
        val items =
            if (query.isBlank()) repository.getResumeItems() else repository.getSearchItems(query)
        return items.firstNotNullOfOrNull { it.autoId()?.let { id -> buildQueue(id) } }
    }

    /** The queue to resume with when a media button is pressed and nothing is loaded. */
    suspend fun buildResumeQueue(): AutoQueue? = buildQueueForSearch("")

    /**
     * Resolves the real stream URL (or local file) of [itemId]. Blocking-friendly: it is called
     * from the player's loading thread.
     */
    suspend fun resolveStreamUri(itemId: UUID): Uri {
        streamUris[itemId]?.let {
            return it
        }
        val sources = repositoryProvider.get().getMediaSources(itemId, includePath = true)
        val source =
            sources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: sources.firstOrNull()
        val path = source?.path
        if (path.isNullOrBlank()) throw IOException("No playable source for item $itemId")
        return path.toUri().also { streamUris[itemId] = it }
    }

    private suspend fun childrenOf(
        repository: JellyfinRepository,
        parentId: UUID,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> {
        val startIndex = page.toLong() * pageSize
        if (startIndex > Int.MAX_VALUE) return emptyList()
        return repository
            .getItems(
                parentId = parentId,
                startIndex = startIndex.toInt(),
                limit = pageSize.coerceAtMost(MAX_PAGE_SIZE),
            )
            .mapNotNull { it.toMediaItem() }
    }

    private fun episodeQueue(episodes: List<FindroidEpisode>, startEpisodeId: UUID?): AutoQueue? {
        if (episodes.isEmpty()) return null
        val startIndex = episodes.indexOfFirst { it.id == startEpisodeId }.coerceAtLeast(0)
        return AutoQueue(
            items = episodes.mapNotNull { it.toMediaItem() },
            startIndex = startIndex,
            startPositionMs = episodes[startIndex].startPositionMs(),
        )
    }

    private fun FindroidItem.startPositionMs(): Long =
        if (!played && playbackPositionTicks > 0) playbackPositionTicks / TICKS_PER_MS
        else C.TIME_UNSET

    private fun staticChildren(): List<MediaItem> =
        if (isOffline) {
            listOf(continueWatchingFolder(), nextUpFolder(), downloadsFolder())
        } else {
            listOf(
                continueWatchingFolder(),
                nextUpFolder(),
                folder(AutoMediaId.Libraries, context.getString(CoreR.string.libraries)),
                folder(AutoMediaId.Favorites, context.getString(CoreR.string.title_favorite)),
            )
        }

    /** Every static folder of the tree, whatever the mode, for looking one up by id. */
    private fun staticFolders(): List<MediaItem> =
        staticChildren() +
            listOf(
                folder(AutoMediaId.Libraries, context.getString(CoreR.string.libraries)),
                folder(AutoMediaId.Favorites, context.getString(CoreR.string.title_favorite)),
                downloadsFolder(),
            )

    private fun continueWatchingFolder() =
        folder(AutoMediaId.ContinueWatching, context.getString(FilmR.string.continue_watching))

    private fun nextUpFolder() = folder(AutoMediaId.NextUp, context.getString(CoreR.string.next_up))

    private fun downloadsFolder() =
        folder(AutoMediaId.Downloads, context.getString(CoreR.string.title_download))

    private fun appName(): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun FindroidItem.autoId(): AutoMediaId? =
        when (this) {
            is FindroidMovie -> AutoMediaId.Movie(id)
            is FindroidEpisode -> AutoMediaId.Episode(id)
            is FindroidShow -> AutoMediaId.Show(id)
            is FindroidSeason -> AutoMediaId.Season(seriesId, id)
            is FindroidCollection -> AutoMediaId.Library(id)
            is FindroidFolder,
            is FindroidBoxSet -> AutoMediaId.Folder(id)
            else -> null
        }

    private fun FindroidItem.toMediaItem(): MediaItem? {
        val id = autoId() ?: return null
        return when (this) {
            is FindroidMovie ->
                playable(
                    id = id,
                    title = name,
                    subtitle = productionYear?.toString(),
                    mediaType = MediaMetadata.MEDIA_TYPE_MOVIE,
                    item = this,
                )
            is FindroidEpisode ->
                playable(
                    id = id,
                    title = episodeTitle(),
                    subtitle = seriesName,
                    mediaType = MediaMetadata.MEDIA_TYPE_TV_SHOW,
                    item = this,
                    artist = seriesName,
                    album = seasonName,
                    trackNumber = indexNumber,
                )
            is FindroidShow ->
                folder(id, name, MediaMetadata.MEDIA_TYPE_FOLDER_TV_SHOWS, images = images)
            is FindroidSeason ->
                folder(
                    id,
                    name,
                    MediaMetadata.MEDIA_TYPE_FOLDER_TV_SHOWS,
                    subtitle = seriesName,
                    images = images,
                )
            is FindroidCollection ->
                folder(
                    id,
                    name,
                    when (type) {
                        CollectionType.Movies -> MediaMetadata.MEDIA_TYPE_FOLDER_MOVIES
                        CollectionType.TvShows -> MediaMetadata.MEDIA_TYPE_FOLDER_TV_SHOWS
                        else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    },
                    images = images,
                )
            else -> folder(id, name, images = images)
        }
    }

    private fun FindroidEpisode.episodeTitle(): String =
        if (indexNumberEnd == null) {
            context.getString(
                CoreR.string.episode_name_extended,
                parentIndexNumber,
                indexNumber,
                name,
            )
        } else {
            context.getString(
                CoreR.string.episode_name_extended_with_end,
                parentIndexNumber,
                indexNumber,
                indexNumberEnd,
                name,
            )
        }

    private fun folder(
        id: AutoMediaId,
        title: CharSequence,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        subtitle: CharSequence? = null,
        images: FindroidImages? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.value)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(images?.artwork())
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setExtras(images?.localArtworkExtras())
                    .build()
            )
            .build()

    private fun playable(
        id: AutoMediaId,
        title: CharSequence,
        subtitle: CharSequence?,
        mediaType: Int,
        item: FindroidItem,
        artist: CharSequence? = null,
        album: CharSequence? = null,
        trackNumber: Int? = null,
    ): MediaItem {
        val itemId = id.playableItemId ?: error("$id is not playable")
        return MediaItem.Builder()
            .setMediaId(id.value)
            .setUri(PlaybackUri.forItem(itemId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setDisplayTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setTrackNumber(trackNumber)
                    .setDurationMs((item.runtimeTicks / TICKS_PER_MS).takeIf { it > 0 })
                    .setArtworkUri(item.images.artwork())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(mediaType)
                    .setExtras(
                        item.completionExtras().apply {
                            item.images.localArtworkPath()?.let {
                                putString(EXTRA_LOCAL_ARTWORK, it)
                            }
                        }
                    )
                    .build()
            )
            .build()
    }

    /** Lets Android Auto show watched / in-progress markers next to playable items. */
    private fun FindroidItem.completionExtras(): Bundle =
        Bundle().apply {
            when {
                played ->
                    putInt(
                        MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                        MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
                    )
                playbackPositionTicks > 0 && runtimeTicks > 0 -> {
                    putInt(
                        MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                        MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
                    )
                    putDouble(
                        MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                        (playbackPositionTicks.toDouble() / runtimeTicks).coerceIn(0.0, 1.0),
                    )
                }
                else ->
                    putInt(
                        MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                        MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED,
                    )
            }
        }

    /** Android Auto can only load http(s) artwork; offline artwork is a relative local path. */
    private fun FindroidImages.artwork(): Uri? =
        (primary ?: showPrimary)?.takeIf { it.scheme == "http" || it.scheme == "https" }

    /**
     * Downloaded items have their images in the app's files directory (see ImagesDownloaderWorker).
     * Android Auto cannot read those, so the path travels in the extras for the car UI of this app.
     */
    private fun FindroidImages.localArtworkPath(): String? {
        val uri = primary ?: showPrimary ?: return null
        if (uri.scheme != null) return null
        val file = File(context.filesDir, uri.path.orEmpty())
        return file.takeIf { it.isFile }?.absolutePath
    }

    private fun FindroidImages.localArtworkExtras(): Bundle? =
        localArtworkPath()?.let { Bundle().apply { putString(EXTRA_LOCAL_ARTWORK, it) } }

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        val from = (page.toLong() * pageSize).coerceIn(0L, size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, to)
    }

    companion object {
        /** Media metadata extra holding the path of a downloaded image, for this app's own UI. */
        const val EXTRA_LOCAL_ARTWORK = "dev.jdtech.jellyfin.auto.LOCAL_ARTWORK"

        private const val TICKS_PER_MS = 10_000L
        private const val MAX_PAGE_SIZE = 200
    }
}
