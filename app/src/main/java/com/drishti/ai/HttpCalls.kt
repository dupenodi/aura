package com.drishti.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an OkHttp call on IO and cancels the wire call when the coroutine is cancelled,
 * so Stop mid-"Looking at your screen…" actually aborts the request.
 */
internal suspend fun <T> OkHttpClient.cancellableCall(
    request: Request,
    block: (Response) -> T,
): T = withContext(Dispatchers.IO) {
    val call = newCall(request)
    val handle = coroutineContext.job.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    try {
        call.execute().use(block)
    } catch (e: IOException) {
        if (call.isCanceled() || !coroutineContext.job.isActive) {
            throw CancellationException("HTTP call cancelled", e)
        }
        throw e
    } finally {
        handle.dispose()
    }
}
