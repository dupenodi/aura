package com.drishti.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

object Http {
    /**
     * One client for every model and speech call, so they share a connection pool: after the
     * first request each step reuses a warm HTTP/2 connection instead of a fresh TLS handshake.
     */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Runs a call on IO and cancels the wire request when the coroutine is cancelled, so Stop
 * mid-"thinking" actually aborts it. Network failures come back as [LlmException]s.
 */
internal suspend fun <T> OkHttpClient.await(
    request: Request,
    timeoutMs: Long,
    block: (Response) -> T,
): T = withContext(Dispatchers.IO) {
    val call = newCall(request)
    call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    val handle = coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
    try {
        call.execute().use(block)
    } catch (e: IOException) {
        if (call.isCanceled() && !coroutineContext.job.isActive) throw CancellationException("cancelled", e)
        throw when (e) {
            is InterruptedIOException -> LlmException(LlmException.Kind.Timeout, "timed out after ${timeoutMs}ms", cause = e)
            is UnknownHostException -> LlmException(LlmException.Kind.Network, "no network: ${e.message}", cause = e)
            else -> LlmException(LlmException.Kind.Network, e.message ?: "network error", cause = e)
        }
    } finally {
        handle.dispose()
    }
}
