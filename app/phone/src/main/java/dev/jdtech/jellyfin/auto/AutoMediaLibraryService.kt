package dev.jdtech.jellyfin.auto

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.MainActivity
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Makes Findroid available to Android Auto (and any other media controller, such as the Google
 * Assistant or Bluetooth head units).
 *
 * Android Auto does not allow video apps, so this exposes the Jellyfin library as a browsable tree
 * and plays the *audio* of the selected movie or episode, reporting progress back to the server. It
 * runs its own player, independent from the full screen video player activity.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class AutoMediaLibraryService : MediaLibraryService() {
    @Inject lateinit var tree: AutoMediaTree
    @Inject lateinit var repositoryProvider: Provider<JellyfinRepository>
    @Inject lateinit var appPreferences: AppPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var reporter: AutoPlaybackReporter
    private var mediaSession: MediaLibrarySession? = null

    // Search results are produced by `onSearch` and fetched afterwards by `onGetSearchResult`.
    private var lastSearch: Pair<String, List<MediaItem>>? = null

    override fun onCreate() {
        super.onCreate()

        player = createPlayer()
        reporter = AutoPlaybackReporter(player, repositoryProvider, scope)
        player.addListener(reporter)

        mediaSession =
            MediaLibrarySession.Builder(this, player, LibraryCallback())
                // The video player activity builds a session with the default id, ids must be
                // unique.
                .setId(SESSION_ID)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background (e.g. in the car), otherwise shut down.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        reporter.release()
        player.removeListener(reporter)
        mediaSession?.release()
        mediaSession = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun createPlayer(): ExoPlayer {
        // Items are queued with a placeholder URI; look up the real stream URL when it is loaded.
        val dataSourceFactory =
            ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { dataSpec ->
                val itemId = PlaybackUri.itemId(dataSpec.uri) ?: return@Factory dataSpec
                try {
                    dataSpec.withUri(runBlocking { tree.resolveStreamUri(itemId) })
                } catch (e: Exception) {
                    throw IOException("Failed to resolve stream of $itemId", e)
                }
            }

        val trackSelector =
            DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setPreferredAudioLanguage(
                            appPreferences.getValue(appPreferences.preferredAudioLanguage)
                        )
                )
            }

        return ExoPlayer.Builder(
                this,
                DefaultRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(appPreferences.getValue(appPreferences.playerSeekBackInc))
            .setSeekForwardIncrementMs(appPreferences.getValue(appPreferences.playerSeekForwardInc))
            .build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            tree.clearCaches()

            // Android Auto asks for a "recent" root to offer resuming what was played last.
            val root = tree.rootItem(recent = params?.isRecent == true)
            val rootParams =
                LibraryParams.Builder()
                    .setExtras(
                        Bundle().apply {
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                            )
                            putInt(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                            )
                        }
                    )
                    .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val id =
                AutoMediaId.parse(mediaId)
                    ?: return@future failure<MediaItem>(SessionError.ERROR_BAD_VALUE)
            browse<MediaItem> {
                tree.getItem(id)?.let { LibraryResult.ofItem(it, null) }
                    ?: failure(SessionError.ERROR_BAD_VALUE)
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val parent =
                AutoMediaId.parse(parentId)
                    ?: return@future failure<ImmutableList<MediaItem>>(SessionError.ERROR_BAD_VALUE)
            browse<ImmutableList<MediaItem>> {
                LibraryResult.ofItemList(tree.getChildren(parent, page, pageSize), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            browse<Void> {
                val results = tree.search(query)
                lastSearch = query to results
                session.notifySearchResultChanged(browser, query, results.size, params)
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            browse<ImmutableList<MediaItem>> {
                val results = lastSearch?.takeIf { it.first == query }?.second ?: tree.search(query)
                val from = (page.toLong() * pageSize).coerceIn(0L, results.size.toLong())
                val to = (from + pageSize).coerceAtMost(results.size.toLong())
                LibraryResult.ofItemList(results.subList(from.toInt(), to.toInt()), params)
            }
        }

        /**
         * Items picked in Android Auto only carry a media id (and voice commands only a search
         * query). Selecting a single one queues the rest of its season for episodes.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            requireSignedIn()

            val single = mediaItems.singleOrNull()
            val usesDefaultStart = startIndex <= 0 && startPositionMs == C.TIME_UNSET
            if (single != null && usesDefaultStart) {
                val searchQuery = single.requestMetadata.searchQuery
                val queue =
                    if (searchQuery != null) {
                        tree.buildQueueForSearch(searchQuery)
                    } else {
                        AutoMediaId.parse(single.mediaId)?.let { tree.buildQueue(it) }
                    }
                if (queue != null) {
                    return@future MediaSession.MediaItemsWithStartPosition(
                        queue.items,
                        queue.startIndex,
                        queue.startPositionMs,
                    )
                }
                if (searchQuery != null) throw UnsupportedOperationException("Nothing found")
            }

            MediaSession.MediaItemsWithStartPosition(
                resolve(mediaItems),
                startIndex,
                startPositionMs,
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future { resolve(mediaItems) }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            requireSignedIn()
            val queue =
                tree.buildResumeQueue() ?: throw UnsupportedOperationException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(
                queue.items,
                queue.startIndex,
                queue.startPositionMs,
            )
        }
    }

    private fun requireSignedIn() {
        if (!tree.isSignedIn)
            throw IllegalStateException(getString(CoreR.string.auto_setup_required))
    }

    /** Fills in the missing metadata / playback URI of items that only carry a media id. */
    private suspend fun resolve(mediaItems: List<MediaItem>): List<MediaItem> {
        requireSignedIn()
        return mediaItems.map { item ->
            if (item.localConfiguration != null) {
                item
            } else {
                AutoMediaId.parse(item.mediaId)?.let { tree.getItem(it) }
                    ?: throw UnsupportedOperationException("Unknown media item ${item.mediaId}")
            }
        }
    }

    private suspend fun <T : Any> browse(block: suspend () -> LibraryResult<T>): LibraryResult<T> {
        if (!tree.isSignedIn) {
            return LibraryResult.ofError<T>(
                SessionError(
                    SessionError.ERROR_SESSION_SETUP_REQUIRED,
                    getString(CoreR.string.auto_setup_required),
                )
            )
        }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to load media library")
            failure(SessionError.ERROR_IO)
        } catch (e: NotImplementedError) {
            // The offline repository still has TODO() stubs, which throw an Error.
            Timber.e(e, "Not available in offline mode")
            failure(SessionError.ERROR_NOT_SUPPORTED)
        }
    }

    private fun <T : Any> failure(code: Int): LibraryResult<T> = LibraryResult.ofError<T>(code)

    private companion object {
        const val SESSION_ID = "findroid_auto"
    }
}
