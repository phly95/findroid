package dev.jdtech.jellyfin.auto.projection

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService
import timber.log.Timber

/**
 * Entry point of the Fermata-style Android Auto projection (legacy Android Auto SDK). Android Auto
 * binds this service for apps declaring `CATEGORY_PROJECTION` and shows [ProjectionCarActivity] on
 * the car display.
 */
class ProjectionCarService : CarActivityService() {
    override fun onCreate() {
        // If this never shows up in logcat, Android Auto is not binding the service (not listed).
        Timber.i("Creating ProjectionCarService")
        super.onCreate()
    }

    override fun onDestroy() {
        Timber.i("Destroying ProjectionCarService")
        super.onDestroy()
    }

    override fun getCarActivity(): Class<out CarActivity> = ProjectionCarActivity::class.java
}
