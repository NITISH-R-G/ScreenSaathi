package com.screensaathi.circle

import android.graphics.Bitmap
import android.util.Log
import com.screensaathi.ScreenReaderService
import java.io.File
import java.io.FileOutputStream

/**
 * Drives one circle interaction: capture, resolve, remember.
 *
 * Deliberately separate from [CircleSelectionView] (which only draws and
 * reports a path) and from the overlay service (which only owns windows), so
 * the decision-making here can be reasoned about on its own. It holds the
 * live [CircleContext] so a follow-up question resolves against the same
 * selection.
 *
 * This class does not talk to the planner, speak, or highlight — the caller
 * routes an agentic intent into the existing SessionController rather than a
 * second agent loop being grown here.
 */
class CircleSession(
    private val capture: ScreenCaptureProvider,
    private val vision: VisionProvider = NoOpVisionProvider,
    private val readerProvider: () -> ScreenReaderService? = { ScreenReaderService.instance },
    private val cacheDirProvider: () -> File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    var context: CircleContext? = null
        private set

    /**
     * Bumped on every new selection. A capture that lands after the user has
     * already drawn something else must not overwrite the newer context —
     * the same generation discipline the voice path uses for in-flight plans.
     */
    @Volatile
    private var generation = 0

    /**
     * Resolve a completed selection.
     *
     * The accessibility snapshot is taken *first* and synchronously, before
     * the screenshot: it is the primary signal, it is cheap, and taking it
     * after an async capture would resolve against a screen that may already
     * have changed. Pixels are then attached when (and if) they arrive.
     */
    fun onSelectionDrawn(
        path: List<SelectionPoint>,
        shape: SelectionShape,
        languageCode: String,
        onResolved: (CircleContext) -> Unit,
    ) {
        val myGeneration = ++generation

        val reader = readerProvider()
        val snapshot = reader?.snapshot()
        if (snapshot == null) {
            Log.w(TAG, "no accessibility snapshot; selection cannot be resolved")
        }

        val selection = ScreenSelection.fromPath(
            shape = shape,
            path = path,
            packageName = snapshot?.packageName.orEmpty(),
            screenSignature = snapshot?.signature().orEmpty(),
            capturedAtMs = clock(),
        )

        val target = if (snapshot != null) {
            SelectionResolver.resolve(selection, snapshot)
        } else {
            EMPTY_TARGET
        }

        val initial = CircleContext(
            selection = selection,
            target = target,
            frame = null,
            languageCode = languageCode,
        )
        context = initial
        onResolved(initial)

        // Pixels are optional and always second. They exist so a vision
        // provider can be added later; nothing in the current flow blocks on
        // them, and a failure here degrades to accessibility-only rather than
        // failing the interaction.
        if (!capture.isAvailable) return

        capture.captureCurrentScreen { result ->
            if (myGeneration != generation) {
                result.getOrNull()?.bitmap?.recycle()
                return@captureCurrentScreen
            }

            val frame = result.getOrNull()
            if (frame == null) {
                Log.i(TAG, "capture unavailable: ${result.exceptionOrNull()?.message}")
                return@captureCurrentScreen
            }

            val cropPath = writeCrop(frame.bitmap, selection)
            val withPixels = (context ?: initial).copy(
                selection = selection.copy(cropPath = cropPath),
                frame = frame,
            )
            if (myGeneration == generation) {
                context = withPixels
                onResolved(withPixels)
            }
        }
    }

    /** Record a turn against the live selection. */
    fun addTurn(request: String, intent: CircleIntent, response: String) {
        context = context?.withTurn(request, intent, response, clock())
    }

    fun setActiveTask(taskId: String?) {
        context = context?.withTask(taskId)
    }

    /**
     * Ask the vision provider about the selection.
     *
     * Only meaningful when the accessibility tree could not explain it. With
     * [NoOpVisionProvider] this always reports unavailable — which the caller
     * turns into an honest sentence rather than a guess.
     */
    fun analyzeVisually(userPrompt: String, onResult: (VisionResult) -> Unit) {
        val ctx = context
        val frame = ctx?.frame
        if (ctx == null || frame == null) {
            onResult(VisionResult.Unavailable(VisionResult.Unavailable.Reason.NO_PIXELS))
            return
        }
        vision.analyzeSelection(frame, ctx.selection, userPrompt, onResult)
    }

    /**
     * Drop the selection.
     *
     * Also recycles the captured bitmap: a full-screen ARGB_8888 frame is
     * ~10MB on this device, and holding several across repeated interactions
     * is the obvious way to leak.
     */
    fun clear() {
        generation++
        context?.frame?.bitmap?.recycle()
        context?.selection?.cropPath?.let { runCatching { File(it).delete() } }
        context = null
    }

    /**
     * Crop the selection out of the frame and cache it.
     *
     * Written to cacheDir and deleted in [clear], matching how the voice path
     * treats recorded audio — nothing about the user's screen persists beyond
     * the interaction that needed it.
     */
    private fun writeCrop(bitmap: Bitmap?, selection: ScreenSelection): String? {
        if (bitmap == null) return null
        val dir = cacheDirProvider() ?: return null

        return try {
            val box = selection.bounds
            val left = box.left.coerceIn(0, bitmap.width)
            val top = box.top.coerceIn(0, bitmap.height)
            val right = box.right.coerceIn(left, bitmap.width)
            val bottom = box.bottom.coerceIn(top, bitmap.height)
            if (right - left <= 0 || bottom - top <= 0) return null

            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val file = File(dir, "circle_selection_${selection.capturedAtMs}.png")
            FileOutputStream(file).use { out ->
                crop.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            if (crop !== bitmap) crop.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "could not write selection crop", e)
            null
        }
    }

    companion object {
        private const val TAG = "CircleSession"

        private val EMPTY_TARGET = SelectionResolver.SelectedTarget(
            element = null,
            confidence = 0,
            alternatives = emptyList(),
            selectedText = "",
            possibleActions = emptySet(),
            surroundingContext = "",
            ambiguous = false,
            reason = "the accessibility service was not available",
        )
    }
}
