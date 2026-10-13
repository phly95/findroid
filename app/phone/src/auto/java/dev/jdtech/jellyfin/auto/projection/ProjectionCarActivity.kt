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
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
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
import dev.jdtech.jellyfin.R as AppR
import dev.jdtech.jellyfin.auto.AutoMediaId
import dev.jdtech.jellyfin.auto.AutoMediaLibraryService
import dev.jdtech.jellyfin.core.R as CoreR
import timber.log.Timber

/**
 * Fermata-style car UI: a legacy Android Auto SDK [CarActivity] that is projected onto the car
 * display. It is not an `android.app.Activity` (no Compose, Hilt or ViewModels), so this is a small
 * View-based browser and video player.
 *
 * Like Fermata's UI it shares its playback engine with the media session: it connects to
 * [AutoMediaLibraryService] (the same browse tree and player Android Auto's media UI uses) and only
 * adds the video output.
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
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var browseLayout: View
    private lateinit var playerLayout: View
    private lateinit var playerView: PlayerView
    private val adapter = ItemAdapter { item -> onItemClicked(item) }

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

    override fun onDestroy() {
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
        browser = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
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
                    playerView.player = connected
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
        val browser = browser ?: return
        // The media session plays audio only (for Android Auto's media UI), turn video on.
        setVideoEnabled(browser, true)
        browser.setMediaItem(item)
        browser.prepare()
        browser.play()
        playerLayout.isVisible = true
        browseLayout.isVisible = false
    }

    private fun closePlayer() {
        browser?.let {
            it.stop()
            it.clearMediaItems()
            setVideoEnabled(it, false)
        }
        playerLayout.isVisible = false
        browseLayout.isVisible = true
    }

    private fun setVideoEnabled(browser: MediaBrowser, enabled: Boolean) {
        browser.trackSelectionParameters =
            browser.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
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
        val topBar =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(56)))
                addView(titleView, LinearLayout.LayoutParams(0, dp(56), 1f))
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

        playerView = PlayerView(context).apply { setBackgroundColor(Color.BLACK) }
        playerLayout =
            FrameLayout(context).apply {
                isVisible = false
                addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(
                    actionButton(getString(AppR.string.auto_projection_close_player)) {
                        closePlayer()
                    },
                    FrameLayout.LayoutParams(WRAP_CONTENT, dp(56), Gravity.TOP or Gravity.START),
                )
            }

        root.addView(browseLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(statusView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(playerLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
            metadata.artworkUri?.let { holder.poster.load(it) }
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
    }
}
