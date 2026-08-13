package com.screensaathi.circle

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import com.screensaathi.ScreenReaderService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen capture through the accessibility service ScreenSaathi already binds.
 *
 * [AccessibilityService.takeScreenshot] (API 30+) needs no MediaProjection
 * consent dialog, no new manifest permission, and no foreground-service type
 * change — the service is already allowed to read this screen, which is the
 * whole reason the user enabled it. Introducing MediaProjection here would add
 * a second, louder consent flow for strictly less capability.
 *
 * Capture is on demand: once per completed selection, never on a timer.
 */
class AccessibilityScreenCapture(
    private val serviceProvider: () -> ScreenReaderService? = { ScreenReaderService.instance },
    private val executor: Executor = DEFAULT_EXECUTOR,
) : ScreenCaptureProvider {

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && serviceProvider() != null

    override fun captureCurrentScreen(onResult: (Result<ScreenFrame>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(fail(CaptureFailure.UNSUPPORTED_API, "takeScreenshot needs API 30, device is ${Build.VERSION.SDK_INT}"))
            return
        }
        val service = serviceProvider()
        if (service == null) {
            onResult(fail(CaptureFailure.SERVICE_UNAVAILABLE, "accessibility service is not bound"))
            return
        }

        val snapshot = runCatching { service.snapshot() }.getOrNull()
        val packageName = snapshot?.packageName.orEmpty()
        val signature = snapshot?.signature().orEmpty()

        // The platform callback is not contractually single-shot; guard so a
        // double delivery cannot double-resume the caller's continuation.
        val delivered = AtomicBoolean(false)
        fun deliver(result: Result<ScreenFrame>) {
            if (delivered.compareAndSet(false, true)) onResult(result)
        }

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        // The hardware buffer must be closed on every path,
                        // including the ones where wrapping it fails — leaking
                        // it starves the compositor after a handful of captures.
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                // A hardware bitmap cannot be read back for
                                // cropping, or by any future vision provider,
                                // so take a software copy while we hold it.
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            Log.w(TAG, "could not wrap screenshot buffer", e)
                            null
                        } finally {
                            buffer.close()
                        }

                        if (bitmap == null) {
                            deliver(fail(CaptureFailure.REFUSED_BY_PLATFORM, "screenshot buffer could not be read"))
                            return
                        }

                        deliver(
                            Result.success(
                                ScreenFrame(
                                    bitmap = bitmap,
                                    widthPx = bitmap.width,
                                    heightPx = bitmap.height,
                                    capturedAtMs = System.currentTimeMillis(),
                                    packageName = packageName,
                                    screenSignature = signature,
                                )
                            )
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        // The common real cause is a FLAG_SECURE window — a
                        // banking app refusing to be captured, which is correct
                        // behaviour, not a bug to route around.
                        deliver(fail(CaptureFailure.REFUSED_BY_PLATFORM, "takeScreenshot failed with code $errorCode"))
                    }
                },
            )
        } catch (e: Exception) {
            deliver(fail(CaptureFailure.UNKNOWN, "takeScreenshot threw: ${e.message}"))
        }
    }

    private fun fail(failure: CaptureFailure, message: String): Result<ScreenFrame> {
        Log.w(TAG, "capture failed [$failure]: $message")
        return Result.failure(CaptureException(failure, message))
    }

    companion object {
        private const val TAG = "CircleCapture"

        /**
         * Single thread, not a pool: captures are one-at-a-time by
         * construction, and this callback does a full-screen bitmap copy that
         * should not fan out across cores mid-gesture.
         */
        private val DEFAULT_EXECUTOR: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "circle-capture").apply { isDaemon = true }
        }
    }
}
