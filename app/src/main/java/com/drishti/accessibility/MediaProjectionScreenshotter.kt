package com.drishti.accessibility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Simplified MediaProjection screenshot path for API 26–29
 * (portal MediaProjectionScreenshotter logic, without WebRTC).
 */
object MediaProjectionScreenshotter {
    private const val TAG = "MediaProjectionShot"

    @Volatile
    private var projection: MediaProjection? = null

    private val pendingCallback = AtomicReference<((String) -> Unit)?>(null)

    fun hasProjection(): Boolean = projection != null

    fun setProjection(mp: MediaProjection?) {
        projection = mp
    }

    fun capture(context: Context, callback: (String) -> Unit) {
        val mp = projection
        if (mp == null) {
            pendingCallback.set(callback)
            val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // Timeout if user cancels
            Handler(Looper.getMainLooper()).postDelayed({
                val cb = pendingCallback.getAndSet(null)
                cb?.invoke("error: MediaProjection permission not granted")
            }, 15_000)
            return
        }
        captureWithProjection(context, mp, callback)
    }

    fun onPermissionResult(context: Context, resultCode: Int, data: Intent?) {
        val cb = pendingCallback.getAndSet(null) ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb("error: MediaProjection permission denied")
            return
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(resultCode, data)
        projection = mp
        if (mp == null) {
            cb("error: Failed to create MediaProjection")
            return
        }
        captureWithProjection(context, mp, cb)
    }

    private fun captureWithProjection(
        context: Context,
        mp: MediaProjection,
        callback: (String) -> Unit,
    ) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            var virtualDisplay: VirtualDisplay? = null
            val handler = Handler(Looper.getMainLooper())

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    cropped.recycle()
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    callback(base64)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                    callback("error: ${e.message}")
                } finally {
                    image.close()
                    virtualDisplay?.release()
                    reader.close()
                }
            }, handler)

            virtualDisplay = mp.createVirtualDisplay(
                "drishti-shot",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection capture error", e)
            callback("error: ${e.message}")
        }
    }
}
