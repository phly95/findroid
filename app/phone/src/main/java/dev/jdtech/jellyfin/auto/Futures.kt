package dev.jdtech.jellyfin.auto

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block] in this scope and exposes its result as a [ListenableFuture], which is what the
 * Media3 session callbacks expect. Cancelling the future cancels the coroutine.
 */
internal fun <T> CoroutineScope.future(block: suspend () -> T): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    val job = launch {
        try {
            future.set(block())
        } catch (e: CancellationException) {
            future.cancel(false)
            throw e
        } catch (e: Exception) {
            future.setException(e)
        }
    }
    future.addListener(
        { if (future.isCancelled) job.cancel() },
        MoreExecutors.directExecutor(),
    )
    return future
}
