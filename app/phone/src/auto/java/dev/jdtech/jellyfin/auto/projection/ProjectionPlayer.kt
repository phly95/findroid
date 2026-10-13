package dev.jdtech.jellyfin.auto.projection

import android.app.Application
import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import dagger.hilt.android.EntryPointAccessors
import dev.jdtech.jellyfin.R as AppR
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The video player of the car UI. It is the same [PlayerViewModel] and control layout as the
 * phone's `PlayerActivity` (playlist, resume position, track selection, server progress...), but
 * hosted without an Activity, so the Activity-bound parts (dialogs, gestures, picture-in-picture,
 * screen lock) are replaced by [Host] callbacks or left out.
 *
 * The backend is always mpv, whatever the user chose for the phone: it renders SSA/ASS subtitles
 * itself (libass), which ExoPlayer cannot do properly.
 */
class ProjectionPlayer(
    context: Context,
    private val playerView: PlayerView,
    private val host: Host,
) {
    interface Host {
        fun getString(resId: Int): String

        fun close()

        /**
         * Shows a list of [options] with the one at [selected] marked, [onSelect] gets its index.
         */
        fun showChoices(
            title: String,
            options: List<String>,
            selected: Int,
            onSelect: (Int) -> Unit,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()
    private val entryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CarPlayerEntryPoint::class.java,
        )
    private val appPreferences = entryPoint.appPreferences()
    private val viewModel: PlayerViewModel

    init {
        val application = context.applicationContext as Application
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(
                        application,
                        entryPoint.playlistManager(),
                        entryPoint.repository(),
                        entryPoint.appPreferences(),
                        SavedStateHandle(
                            mapOf(PlayerViewModel.KEY_PLAYER_BACKEND_OVERRIDE to BACKEND_MPV)
                        ),
                    )
                        as T
            }
        viewModel = ViewModelProvider.create(store, factory)[PlayerViewModel::class.java]
        playerView.player = viewModel.player

        wireControls()
        observeViewModel()
    }

    fun start(itemId: UUID, itemKind: String) {
        viewModel.initializePlayer(itemId, itemKind, startFromBeginning = false)
    }

    /** The car UI went to the background: pause and save the position, like the phone player. */
    fun onHostPause() {
        viewModel.playWhenReady = viewModel.player.playWhenReady
        viewModel.player.playWhenReady = false
        viewModel.updatePlaybackProgress()
    }

    fun onHostResume() {
        viewModel.player.playWhenReady = viewModel.playWhenReady
    }

    fun release() {
        scope.cancel()
        try {
            viewModel.player.clearVideoSurfaceView(playerView.videoSurfaceView as SurfaceView)
        } catch (e: Exception) {
            Timber.e(e)
        }
        playerView.player = null
        // Releases the player and reports the playback as stopped.
        store.clear()
    }

    private fun <T : View> find(id: Int): T = playerView.findViewById(id)

    private fun wireControls() {
        find<View>(AppR.id.back_button).setOnClickListener { host.close() }

        // Screen lock and picture-in-picture only make sense on the phone.
        find<View>(AppR.id.btn_lockview).isVisible = false
        find<View>(AppR.id.btn_pip).isVisible = false
        find<View>(AppR.id.space_pip).isVisible = false

        find<ImageButton>(AppR.id.btn_audio_track).setOnClickListener {
            chooseTrack(C.TRACK_TYPE_AUDIO, PlayerR.string.select_audio_track)
        }
        find<ImageButton>(AppR.id.btn_subtitle).setOnClickListener {
            chooseTrack(C.TRACK_TYPE_TEXT, PlayerR.string.select_subtitle_track)
        }
        find<ImageButton>(AppR.id.btn_speed).setOnClickListener { chooseSpeed() }
    }

    private fun chooseTrack(type: @C.TrackType Int, titleRes: Int) {
        val groups =
            viewModel.player.currentTracks.groups.filter { it.type == type && it.isSupported }
        val options = listOf(host.getString(PlayerR.string.none)) + groups.getTrackNames()
        // "None" is at index 0, tracks follow it.
        val selected = groups.indexOfFirst { it.isSelected } + 1
        host.showChoices(host.getString(titleRes), options, selected) { which ->
            viewModel.switchToTrack(type, which - 1)
        }
    }

    private fun chooseSpeed() {
        host.showChoices(
            host.getString(PlayerR.string.select_playback_speed),
            SPEEDS.map { "${it}x" },
            SPEEDS.indexOf(viewModel.playbackSpeed),
        ) { which ->
            viewModel.selectSpeed(SPEEDS[which])
        }
    }

    private fun observeViewModel() {
        val title = find<TextView>(AppR.id.video_name)
        val skipButton = find<TextView>(AppR.id.btn_skip_segment)

        scope.launch {
            viewModel.uiState.collect { state ->
                title.text = state.currentItemTitle

                val segment = state.currentSegment
                skipButton.isVisible = segment != null
                if (segment != null) {
                    skipButton.text = host.getString(state.currentSkipButtonStringRes)
                    skipButton.setOnClickListener { viewModel.skipSegment(segment) }
                }
            }
        }

        scope.launch {
            viewModel.eventsChannelFlow.collect { event ->
                if (event is PlayerEvents.NavigateBack) host.close()
            }
        }

        // Report progress to the server, like the phone player does.
        scope.launch {
            while (true) {
                viewModel.updatePlaybackProgress()
                delay(PROGRESS_INTERVAL_MS)
            }
        }

        if (
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton) ||
                appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        ) {
            scope.launch {
                while (true) {
                    viewModel.updateCurrentSegment()
                    delay(SEGMENT_INTERVAL_MS)
                }
            }
        }
    }

    private companion object {
        const val BACKEND_MPV = "mpv"
        const val PROGRESS_INTERVAL_MS = 5_000L
        const val SEGMENT_INTERVAL_MS = 1_000L
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}
