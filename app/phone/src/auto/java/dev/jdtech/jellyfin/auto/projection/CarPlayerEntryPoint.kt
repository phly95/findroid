package dev.jdtech.jellyfin.auto.projection

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * Gives the legacy car UI, which is not an Activity and so cannot use Hilt's `@AndroidEntryPoint`
 * or `hiltViewModel()`, access to what the video player view model needs.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarPlayerEntryPoint {
    fun playlistManager(): PlaylistManager

    fun repository(): JellyfinRepository

    fun appPreferences(): AppPreferences
}
