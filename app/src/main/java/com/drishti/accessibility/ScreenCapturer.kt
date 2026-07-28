package com.drishti.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import com.drishti.debug.RingBufferLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Captures screenshots via [AccessibilityService.takeScreenshot] (no MediaProjection).
 * Downscales longest side to 1024, JPEG q=65, returns base64. Throttled to 400ms.
 */
class ScreenCapturer(
    private val serviceProvider: () -> DrishtiAccessibilityService?,
) {
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "drishti-screenshot").apply { isDaemon = true }
    }
    private val encodeMutex = Mutex()
    private val lastCaptureAt = AtomicLong(0L)
    private val cachedBase64 = AtomicReference<String?>(null)

    data class CaptureResult(
        val bitmap: Bitmap?,
        val base64Jpeg: String?,
    )

    suspend fun capture(): CaptureResult {
        val now = System.currentTimeMillis()
        val last = lastCaptureAt.get()
        if (now - last < THROTTLE_MS) {
            val cached = cachedBase64.get()
            RingBufferLogger.tool("ScreenCapturer.capture", "throttled cached=${cached != null}")
            return CaptureResult(bitmap = null, base64Jpeg = cached)
        }

        val service = serviceProvider()
        if (service == null) {
            DrishtiAccessibilityService.showEnableToast()
            RingBufferLogger.tool("ScreenCapturer.capture", "service=null")
            return CaptureResult(null, null)
        }

        val hardwareResult = takeScreenshotSafe(service) ?: run {
            RingBufferLogger.tool("ScreenCapturer.capture", "null (secure or failed)")
            return CaptureResult(null, null)
        }

        return encodeMutex.withLock {
            withContext(Dispatchers.Default) {
                var hwBitmap: Bitmap? = null
                var software: Bitmap? = null
                var scaled: Bitmap? = null
                var scaledIsCopy = false
                try {
                    hwBitmap = Bitmap.wrapHardwareBuffer(
                        hardwareResult.hardwareBuffer,
                        hardwareResult.colorSpace,
                    )
                    if (hwBitmap == null) {
                        RingBufferLogger.tool("ScreenCapturer.capture", "wrapHardwareBuffer=null")
                        return@withContext CaptureResult(null, null)
                    }

                    software = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (software == null) {
                        RingBufferLogger.tool("ScreenCapturer.capture", "copy=null")
                        return@withContext CaptureResult(null, null)
                    }

                    val down = downscale(software, MAX_SIDE)
                    scaled = down.bitmap
                    scaledIsCopy = down.isNew
                    val encodeSrc = scaled ?: software
                    val b64 = toJpegBase64(encodeSrc, JPEG_QUALITY)
                    lastCaptureAt.set(System.currentTimeMillis())
                    cachedBase64.set(b64)
                    RingBufferLogger.tool(
                        "ScreenCapturer.capture",
                        "ok ${encodeSrc.width}x${encodeSrc.height} b64Len=${b64.length}",
                    )
                    // Return a copy the caller owns; recycle our working bitmaps below.
                    val outBitmap = encodeSrc.copy(Bitmap.Config.ARGB_8888, false)
                    CaptureResult(outBitmap, b64)
                } catch (e: Exception) {
                    RingBufferLogger.tool("ScreenCapturer.capture", "error=${e.message}")
                    CaptureResult(null, null)
                } finally {
                    runCatching { hardwareResult.hardwareBuffer.close() }
                    recycleQuietly(hwBitmap)
                    recycleQuietly(software)
                    if (scaledIsCopy) recycleQuietly(scaled)
                }
            }
        }
    }

    suspend fun captureBase64(): String? = capture().base64Jpeg

    private suspend fun takeScreenshotSafe(
        service: DrishtiAccessibilityService,
    ): AccessibilityService.ScreenshotResult? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    cont.resume(null)
                    return@runCatching
                }
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            runCatching {
                                if (cont.isActive) cont.resume(screenshot)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            runCatching {
                                // FLAG_SECURE and other failures → null, never throw
                                RingBufferLogger.log(
                                    "ScreenCapturer",
                                    "takeScreenshot failure code=$errorCode",
                                )
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    },
                )
            }.onFailure { e ->
                RingBufferLogger.log("ScreenCapturer", "takeScreenshot threw ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    private data class DownscaleResult(val bitmap: Bitmap?, val isNew: Boolean)

    private fun downscale(source: Bitmap, maxSide: Int): DownscaleResult {
        val w = source.width
        val h = source.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return DownscaleResult(null, false)
        val scale = maxSide.toFloat() / longest.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return DownscaleResult(Bitmap.createScaledBitmap(source, nw, nh, true), true)
    }

    private fun toJpegBase64(bitmap: Bitmap, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        runCatching {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        private const val THROTTLE_MS = 400L
        private const val MAX_SIDE = 1024
        private const val JPEG_QUALITY = 65
    }
}
