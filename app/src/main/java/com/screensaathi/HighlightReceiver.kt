package com.screensaathi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Triggers a highlight without disturbing whatever is on screen.
 *
 * An Activity-based trigger cannot work for this: starting an Activity brings
 * it to the foreground, so by the time the resolver reads the accessibility
 * tree the target app is no longer in front and it resolves against the wrong
 * screen. A broadcast changes nothing about the foreground task.
 *
 *   adb shell am broadcast -a com.screensaathi.HIGHLIGHT --es query "Wi-Fi"
 *
 * This is the same resolver and overlay the voice path uses — only speech
 * recognition is skipped — so exercising it is a real test of the pipeline.
 */
class HighlightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HIGHLIGHT -> {
                val query = intent.getStringExtra("query").orEmpty()
                if (query.isBlank()) return
                Log.d(TAG, "HIGHLIGHT_REQUEST query='$query'")
                OverlayService.highlight(context, query)
            }
            // get_current_screen(): dump the live ScreenModel exactly as the
            // resolver sees it. This is the tool the agent will call, and it is
            // also the only honest way to tell "the node is missing from our
            // snapshot" apart from "the node is there but scored too low".
            ACTION_DUMP -> {
                val snap = ScreenReaderService.instance?.snapshot()
                if (snap == null) {
                    Log.w(TAG, "SCREEN_DUMP unavailable: accessibility service not bound")
                    return
                }
                Log.w(TAG, "SCREEN_DUMP pkg=${snap.packageName} settled=${snap.settled} " +
                    "elements=${snap.elements.size}")
                val filter = intent.getStringExtra("filter")?.lowercase().orEmpty()
                snap.elements.forEachIndexed { i, e ->
                    val line = "[$i] id='${e.resourceId}' text='${e.text}' cls=${e.className} " +
                        "click=${e.clickable} edit=${e.editable} bounds=${e.bounds.toShortString()}"
                    if (filter.isEmpty() || line.lowercase().contains(filter)) Log.w(TAG, "  $line")
                }
            }
            ACTION_CLEAR -> {
                Log.d(TAG, "HIGHLIGHT_CLEAR")
                OverlayService.clearHighlight(context)
            }
        }
    }

    companion object {
        const val ACTION_HIGHLIGHT = "com.screensaathi.HIGHLIGHT"
        const val ACTION_CLEAR = "com.screensaathi.CLEAR_HIGHLIGHT"
        const val ACTION_DUMP = "com.screensaathi.DUMP_SCREEN"
        private const val TAG = "HighlightReceiver"
    }
}
