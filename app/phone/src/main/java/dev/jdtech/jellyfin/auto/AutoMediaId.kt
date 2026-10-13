package dev.jdtech.jellyfin.auto

import android.net.Uri
import androidx.core.net.toUri
import java.util.UUID

/**
 * Identifiers of the nodes exposed to Android Auto (and other media browsers).
 *
 * The [value] is what travels over the media session as `MediaItem.mediaId`, so it has to stay
 * stable and parsable: `<kind>[:<uuid>[:<uuid>]]`.
 */
sealed class AutoMediaId(val value: String) {
    data object Root : AutoMediaId("root")

    data object Recent : AutoMediaId("recent")

    data object ContinueWatching : AutoMediaId("continue_watching")

    data object NextUp : AutoMediaId("next_up")

    data object Libraries : AutoMediaId("libraries")

    data object Favorites : AutoMediaId("favorites")

    data object Downloads : AutoMediaId("downloads")

    data class Library(val id: UUID) : AutoMediaId("library:$id")

    data class Folder(val id: UUID) : AutoMediaId("folder:$id")

    data class Show(val id: UUID) : AutoMediaId("show:$id")

    data class Season(val seriesId: UUID, val id: UUID) : AutoMediaId("season:$seriesId:$id")

    data class Movie(val id: UUID) : AutoMediaId("movie:$id")

    data class Episode(val id: UUID) : AutoMediaId("episode:$id")

    /** The Jellyfin item that is directly playable, or null for browse-only nodes. */
    val playableItemId: UUID?
        get() =
            when (this) {
                is Movie -> id
                is Episode -> id
                else -> null
            }

    /** The Jellyfin item behind this node, or null for the static nodes of the tree. */
    val jellyfinItemId: UUID?
        get() =
            when (this) {
                is Library -> id
                is Folder -> id
                is Show -> id
                is Season -> id
                is Movie -> id
                is Episode -> id
                else -> null
            }

    companion object {
        fun parse(value: String): AutoMediaId? {
            val parts = value.split(':')
            return try {
                when (parts[0]) {
                    Root.value -> Root
                    Recent.value -> Recent
                    ContinueWatching.value -> ContinueWatching
                    NextUp.value -> NextUp
                    Libraries.value -> Libraries
                    Favorites.value -> Favorites
                    Downloads.value -> Downloads
                    "library" -> Library(UUID.fromString(parts[1]))
                    "folder" -> Folder(UUID.fromString(parts[1]))
                    "show" -> Show(UUID.fromString(parts[1]))
                    "season" -> Season(UUID.fromString(parts[1]), UUID.fromString(parts[2]))
                    "movie" -> Movie(UUID.fromString(parts[1]))
                    "episode" -> Episode(UUID.fromString(parts[1]))
                    else -> null
                }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }
    }
}

/**
 * Playable items in the media session carry a placeholder URI instead of the real stream URL.
 * Resolving a stream URL needs a (cached) round trip to the server, so it is done lazily by the
 * player's data source right before the item is loaded rather than for a whole season at once.
 */
object PlaybackUri {
    private const val SCHEME = "findroid"
    private const val AUTHORITY = "item"

    fun forItem(itemId: UUID): Uri = "$SCHEME://$AUTHORITY/$itemId".toUri()

    fun itemId(uri: Uri): UUID? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) return null
        return try {
            uri.lastPathSegment?.let(UUID::fromString)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
