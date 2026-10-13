package dev.jdtech.jellyfin.auto.projection

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.google.android.apps.auto.sdk.CarActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.EntryPointAccessors
import dev.jdtech.jellyfin.R as AppR
import dev.jdtech.jellyfin.auto.AutoMediaId
import dev.jdtech.jellyfin.auto.AutoMediaLibraryService
import dev.jdtech.jellyfin.auto.AutoMediaTree
import dev.jdtech.jellyfin.core.R as CoreR
import java.io.File
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Fermata-style car UI: a legacy Android Auto SDK [CarActivity] that is projected onto the car
 * display. It is not an `android.app.Activity` (no Compose, Hilt or ViewModels), so this is a small
 * View-based browser and video player.
 *
 * Browsing goes through [AutoMediaLibraryService] (the same tree Android Auto's media UI uses).
 * Video is played by [ProjectionPlayer], the phone player's view model with the mpv backend.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ProjectionCarActivity : CarActivity() {
    private class Level(val parentId: String, val title: String)

    private val uiContext by lazy { ContextThemeWrapper(this, AppR.style.Theme_Findroid_Car) }
    private val callbackExecutor by lazy { ContextCompat.getMainExecutor(applicationContext) }

    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private val levels = ArrayDeque<Level>()

    private lateinit var backButton: TextView
    private lateinit var offlineButton: TextView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var browseLayout: View
    private lateinit var playerLayout: View
    private lateinit var playerView: PlayerView
    private lateinit var choiceLayout: View
    private lateinit var choiceTitle: TextView
    private lateinit var choiceList: LinearLayout
    private var projectionPlayer: ProjectionPlayer? = null

    private val playerHost =
        object : ProjectionPlayer.Host {
            override fun getString(resId: Int) = this@ProjectionCarActivity.getString(resId)

            override fun close() = closePlayer()

            override fun showChoices(
                title: String,
                options: List<String>,
                selected: Int,
                onSelect: (Int) -> Unit,
            ) = this@ProjectionCarActivity.showChoices(title, options, selected, onSelect)
        }
    private val adapter = ItemAdapter { item -> onItemClicked(item) }

    private val appPreferences by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CarPlayerEntryPoint::class.java)
            .appPreferences()
    }

    private val density
        get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.i("Creating ProjectionCarActivity")
        super.onCreate(savedInstanceState)
        setIgnoreConfigChanges(0xFFFFFFFF.toInt())
        carUiController.statusBarController.hideAppHeader()
        carUiController.menuController.hideMenuButton()

        setContentView(buildUi())
        connect()
    }

    override fun onPause() {
        super.onPause()
        projectionPlayer?.onHostPause()
    }

    override fun onResume() {
        super.onResume()
        projectionPlayer?.onHostResume()
    }

    override fun onDestroy() {
        projectionPlayer?.release()
        projectionPlayer = null
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
        browser = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            choiceLayout.isVisible -> choiceLayout.isVisible = false
            playerLayout.isVisible -> closePlayer()
            levels.size > 1 -> {
                levels.removeLast()
                load(levels.last())
            }
            else -> super.onBackPressed()
        }
    }

    private fun connect() {
        val token =
            SessionToken(
                applicationContext,
                ComponentName(applicationContext, AutoMediaLibraryService::class.java),
            )
        val future = MediaBrowser.Builder(applicationContext, token).buildAsync()
        browserFuture = future
        future.addListener(
            {
                try {
                    val connected = future.get()
                    browser = connected
                    openRoot(connected)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to connect to the media library")
                    showStatus(e.localizedMessage.orEmpty())
                }
            },
            callbackExecutor,
        )
    }

    private fun openRoot(browser: MediaBrowser) {
        browser
            .getLibraryRoot(null)
            .addListener(
                {
                    val rootId = runCatching {
                        browser.getLibraryRoot(null).get().value?.mediaId
                    }
                        .getOrNull()
                    val root =
                        Level(rootId ?: AutoMediaId.Root.value, getString(CoreR.string.app_name))
                    levels.clear()
                    levels.addLast(root)
                    load(root)
                },
                callbackExecutor,
            )
    }

    /**
     * Flips the app's offline mode (the same preference as the phone's settings). Offline mode
     * makes the whole app use the downloads database instead of the server, so start again from the
     * root, which now shows what is available in the new mode.
     */
    private fun toggleOfflineMode() {
        appPreferences.setValue(
            appPreferences.offlineMode,
            !appPreferences.getValue(appPreferences.offlineMode),
        )
        updateOfflineButton()
        browser?.let { openRoot(it) }
    }

    private fun updateOfflineButton() {
        val offline = appPreferences.getValue(appPreferences.offlineMode)
        offlineButton.text =
            getString(
                if (offline) AppR.string.auto_projection_offline_on
                else AppR.string.auto_projection_offline_off
            )
    }

    private fun load(level: Level) {
        val browser = browser ?: return
        titleView.text = level.title
        backButton.isVisible = levels.size > 1
        adapter.submit(emptyList())
        showStatus(getString(AppR.string.auto_projection_loading))

        browser.getChildren(level.parentId, 0, PAGE_SIZE, null).let { future ->
            future.addListener({ onChildren(level, future) }, callbackExecutor)
        }
    }

    private fun onChildren(
        level: Level,
        future: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>,
    ) {
        // Ignore the answer if the user already navigated elsewhere.
        if (levels.lastOrNull() !== level) return

        val result = runCatching { future.get() }.getOrNull()
        val items = result?.value
        when {
            result == null -> showStatus("")
            result.resultCode != LibraryResult.RESULT_SUCCESS ->
                showStatus(result.sessionError?.message.orEmpty())
            items.isNullOrEmpty() -> showStatus(getString(AppR.string.auto_projection_empty))
            else -> {
                statusView.isVisible = false
                adapter.submit(items)
            }
        }
    }

    private fun onItemClicked(item: MediaItem) {
        if (item.mediaMetadata.isBrowsable == true) {
            val level = Level(item.mediaId, item.mediaMetadata.title?.toString().orEmpty())
            levels.addLast(level)
            load(level)
        } else {
            play(item)
        }
    }

    private fun play(item: MediaItem) {
        val (itemId, kind) =
            when (val id = AutoMediaId.parse(item.mediaId)) {
                is AutoMediaId.Movie -> id.id to BaseItemKind.MOVIE
                is AutoMediaId.Episode -> id.id to BaseItemKind.EPISODE
                else -> return
            }
        projectionPlayer?.release()
        projectionPlayer = null

        // A new view for every playback, so mpv always gets a fresh surface, and show it before the
        // player is created: the surface only exists once the view is on screen.
        playerView = createPlayerView()
        (playerLayout as FrameLayout).apply {
            removeAllViews()
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        playerLayout.isVisible = true
        browseLayout.isVisible = false

        projectionPlayer =
            ProjectionPlayer(applicationContext, playerView, playerHost).also {
                it.start(itemId, kind.serialName)
            }
    }

    // The controls (back button, title, tracks, speed...) come from the phone app's layout.
    private fun createPlayerView() =
        PlayerView(uiContext).apply {
            setBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setControllerAnimationEnabled(false)
        }

    private fun closePlayer() {
        val player = projectionPlayer ?: return
        projectionPlayer = null
        choiceLayout.isVisible = false
        playerLayout.isVisible = false
        browseLayout.isVisible = true
        player.release()
    }

    private fun showChoices(
        title: String,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ) {
        choiceTitle.text = title
        choiceList.removeAllViews()
        options.forEachIndexed { index, option ->
            val label = if (index == selected) "\u2713  $option" else "      $option"
            val row =
                actionButton(label) {
                        choiceLayout.isVisible = false
                        onSelect(index)
                    }
                    .apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
            choiceList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
        }
        choiceLayout.isVisible = true
    }

    private fun showStatus(text: String) {
        statusView.text = text
        statusView.isVisible = true
    }

    // region UI

    private fun buildUi(): View {
        val context = uiContext
        val root = FrameLayout(context).apply { setBackgroundColor(BACKGROUND) }

        backButton =
            actionButton(getString(AppR.string.auto_projection_back)) { onBackPressed() }
                .apply { isVisible = false }
        titleView =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }
        offlineButton = actionButton("") { toggleOfflineMode() }
        updateOfflineButton()
        val topBar =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(56)))
                addView(titleView, LinearLayout.LayoutParams(0, dp(56), 1f))
                addView(offlineButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(56)))
            }
        val list =
            RecyclerView(context).apply {
                layoutManager = GridLayoutManager(context, GRID_COLUMNS)
                adapter = this@ProjectionCarActivity.adapter
                clipToPadding = false
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        browseLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        statusView =
            TextView(context).apply {
                setTextColor(Color.LTGRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                isVisible = false
            }

        // Holds the player view of the current playback, see play().
        playerLayout = FrameLayout(context).apply { isVisible = false }

        choiceTitle =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(20), dp(16), dp(20), dp(8))
            }
        choiceList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val choiceCard =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(CARD)
                isClickable = true
                addView(choiceTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(
                    ScrollView(context).apply { addView(choiceList) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
                )
            }
        choiceLayout =
            FrameLayout(context).apply {
                isVisible = false
                setBackgroundColor(SCRIM)
                isClickable = true
                setOnClickListener { isVisible = false }
                addView(
                    choiceCard,
                    FrameLayout.LayoutParams(dp(480), MATCH_PARENT, Gravity.CENTER).apply {
                        setMargins(0, dp(24), 0, dp(24))
                    },
                )
            }

        root.addView(browseLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(statusView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(playerLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(choiceLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    private fun actionButton(text: String, onClick: () -> Unit) =
        TextView(uiContext).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            isFocusable = true
            isClickable = true
            background = focusBackground()
            setOnClickListener { onClick() }
        }

    /** Highlights the focused view, for cars that are controlled with a rotary knob. */
    private fun focusBackground() =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    setStroke(dp(3), ACCENT)
                    cornerRadius = dp(6).toFloat()
                },
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }

    private fun dp(value: Int) = (value * density).toInt()

    private inner class ItemAdapter(private val onClick: (MediaItem) -> Unit) :
        RecyclerView.Adapter<ItemAdapter.Holder>() {
        private var items: List<MediaItem> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<MediaItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(
            val root: LinearLayout,
            val poster: ImageView,
            val title: TextView,
            val subtitle: TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = uiContext
            val poster =
                object : ImageView(context) {
                        // Posters are 2:3.
                        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                            val width = MeasureSpec.getSize(widthMeasureSpec)
                            super.onMeasure(
                                widthMeasureSpec,
                                MeasureSpec.makeMeasureSpec(width * 3 / 2, MeasureSpec.EXACTLY),
                            )
                        }
                    }
                    .apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(PLACEHOLDER)
                    }
            val title =
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            val subtitle =
                TextView(context).apply {
                    setTextColor(Color.GRAY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            val root =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    isFocusable = true
                    isClickable = true
                    background = focusBackground()
                    addView(poster, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    addView(subtitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
            return Holder(root, poster, title, subtitle)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val metadata = item.mediaMetadata
            holder.title.text = metadata.title
            holder.subtitle.text = metadata.subtitle
            holder.subtitle.isVisible = !metadata.subtitle.isNullOrEmpty()
            holder.poster.isVisible = true
            holder.poster.setImageDrawable(null)
            val localArtwork = metadata.extras?.getString(AutoMediaTree.EXTRA_LOCAL_ARTWORK)
            when {
                metadata.artworkUri != null -> holder.poster.load(metadata.artworkUri)
                // Downloaded items: the image is a file in the app's storage.
                localArtwork != null -> holder.poster.load(File(localArtwork))
            }
            holder.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    // endregion

    private companion object {
        const val PAGE_SIZE = 200
        const val GRID_COLUMNS = 4
        val BACKGROUND = Color.parseColor("#101114")
        val PLACEHOLDER = Color.parseColor("#26282E")
        val ACCENT = Color.parseColor("#8AB4F8")
        val CARD = Color.parseColor("#1B1D22")
        val SCRIM = Color.parseColor("#99000000")
    }
}
