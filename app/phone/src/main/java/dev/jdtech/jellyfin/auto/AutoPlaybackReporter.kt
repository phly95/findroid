package dev.jdtech.jellyfin.auto

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Reports playback of the Android Auto player to Jellyfin (start, progress, stop), the same way the
 * in-app player does, so watch state and resume positions stay in sync across clients.
 *
 * Reports are sent one at a time and in order, so a "stop" for the previous item always reaches the
 * server before the "start" of the next one.
 */
class AutoPlaybackReporter(
    private val player: Player,
    private val repositoryProvider: Provider<JellyfinRepository>,
    private val mainScope: CoroutineScope,
) : Player.Listener {
    private val reports = Channel<suspend (JellyfinRepository) -> Unit>(Channel.UNLIMITED)

    // Lives on after the service is destroyed so the final "stop" can still be delivered.
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeMediaId: String? = null
    private var progressJob: Job? = null

    init {
        reportScope.launch {
            for (report in reports) {
                try {
                    report(repositoryProvider.get())
                } catch (e: Exception) {
                    Timber.e(e, "Failed to report playback")
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val itemId = mediaItem?.itemId() ?: return
        activeMediaId = mediaItem.mediaId
        reports.trySend { it.postPlaybackStart(itemId) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val oldItem = oldPosition.mediaItem ?: return
        val movedToOtherItem =
            oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
                oldItem.mediaId != newPosition.mediaItem?.mediaId
        if (movedToOtherItem) reportStopped(oldItem, oldPosition.positionMs)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            player.currentMediaItem?.let { reportStopped(it, player.duration) }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        progressJob?.cancel()
        reportProgress(isPaused = !isPlaying)
        if (isPlaying) {
            progressJob = mainScope.launch {
                while (true) {
                    delay(PROGRESS_INTERVAL_MS)
                    reportProgress(isPaused = false)
                }
            }
        }
    }

    /** Reports the item that is still playing as stopped. Call before releasing the player. */
    fun release() {
        progressJob?.cancel()
        player.currentMediaItem?.let { reportStopped(it, player.currentPosition) }
        reports.close()
    }

    private fun reportProgress(isPaused: Boolean) {
        val itemId = player.currentMediaItem?.takeIf { it.mediaId == activeMediaId }?.itemId()
        if (itemId == null) return
        val positionTicks = player.currentPosition * TICKS_PER_MS
        reports.trySend { it.postPlaybackProgress(itemId, positionTicks, isPaused) }
    }

    private fun reportStopped(mediaItem: MediaItem, positionMs: Long) {
        // Only report what was reported as started, and only once.
        if (mediaItem.mediaId != activeMediaId) return
        activeMediaId = null

        val itemId = mediaItem.itemId() ?: return
        val durationMs =
            mediaItem.mediaMetadata.durationMs
                ?: player.duration.takeIf { it != C.TIME_UNSET }
                ?: return
        val position = positionMs.coerceIn(0L, durationMs)
        val percentage = (position * 100 / durationMs).toInt()
        reports.trySend { it.postPlaybackStop(itemId, position * TICKS_PER_MS, percentage) }
    }

    private fun MediaItem.itemId() = AutoMediaId.parse(mediaId)?.playableItemId

    private companion object {
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val TICKS_PER_MS = 10_000L
    }
}
