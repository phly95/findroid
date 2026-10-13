package dev.jdtech.jellyfin

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

/**
 * The full app on the car display, for watching video while parked.
 *
 * Android Auto lists activities with the `CAR_LAUNCHER` category (see the manifest) and only shows
 * them while the car is parked. This is the regular app UI, in its own task so that it never
 * collides with the phone's task.
 */
@AndroidEntryPoint class CarMainActivity : MainActivity()

/**
 * The video player when started from [CarMainActivity]. A separate class (and task) from
 * [PlayerActivity], because that one is `singleTask` and would otherwise be matched with the
 * phone's task and end up on the wrong display.
 */
@AndroidEntryPoint
class CarPlayerActivity : PlayerActivity() {
    // Picture-in-picture makes no sense on the car display.
    override val isPipSupported: Boolean = false
}

/** An intent for the video player that opens on the same display as this context's activity. */
fun Context.playerIntent(): Intent {
    val playerClass = if (isInCar()) CarPlayerActivity::class.java else PlayerActivity::class.java
    return Intent(this, playerClass)
}

private fun Context.isInCar(): Boolean =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .any { it is CarMainActivity || it is CarPlayerActivity }
