package com.zektopic.cctvapp.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.WindowManager
import com.pedro.library.view.OpenGlView

/**
 * Owns the invisible system-overlay window that hosts [glView] -- the single GL surface
 * [RtspServerCamera2][com.pedro.rtspserver.RtspServerCamera2] streams from, dashboard
 * snapshots are captured from, and (optionally) an on-screen preview is rendered to.
 * Pulled out of [CctvServerService] since window/view lifecycle (add/resize/remove) is a
 * distinct concern from the camera/streaming logic that uses the view it manages.
 */
class OverlayWindow(private val context: Context) {
    companion object {
        private const val TAG = "OverlayWindow"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** The shared GL surface -- exposed directly since callers need it for more than layout (e.g. handing it to RtspServerCamera2). */
    val glView: OpenGlView = OpenGlView(context.applicationContext)

    /**
     * Adds [glView] to the window manager as a hidden (1x1) overlay and registers
     * [surfaceCallback] on its `SurfaceHolder`. Always starts hidden -- [setPreviewVisible]
     * is what resizes it once the caller knows whether the preview should actually show.
     */
    @Suppress("DEPRECATION")
    fun attach(surfaceCallback: SurfaceHolder.Callback) {
        val layoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(glView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add view. Permission issue?", e)
        }
        glView.holder.addCallback(surfaceCallback)
        glView.holder.setFixedSize(640, 480)
    }

    /**
     * Resizes the overlay: half the screen width at a fixed 1080p (16:9) ratio, centered
     * and pinned to the top, when [showPreview] is true; a hidden 1x1 window otherwise.
     */
    fun setPreviewVisible(showPreview: Boolean) {
        val layoutParams = glView.layoutParams as? WindowManager.LayoutParams ?: return
        if (showPreview) {
            // Half the screen width, height kept at a 1080p (16:9) ratio, centered
            // horizontally and pinned to the top.
            val screenWidth = context.resources.displayMetrics.widthPixels
            val previewWidth = screenWidth / 2
            layoutParams.width = previewWidth
            layoutParams.height = previewWidth * 1080 / 1920
            layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            layoutParams.width = 1
            layoutParams.height = 1
            layoutParams.gravity = Gravity.TOP or Gravity.START
        }
        windowManager.updateViewLayout(glView, layoutParams)
    }

    /**
     * Flips the raw camera texture before filters are composited -- see
     * [CctvServerService.applyVerticalFlip] for why `setCameraFlip`, and why
     * [horizontal] rather than the vertical parameter, is what actually does the flip
     * the setting name suggests.
     */
    fun setVerticalFlip(horizontal: Boolean) {
        glView.setCameraFlip(horizontal, false)
    }

    /** Removes [glView] from the window manager. Safe to call even if [attach] never ran or failed. */
    fun detach() {
        try {
            windowManager.removeView(glView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
