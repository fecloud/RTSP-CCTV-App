package com.zektopic.cctvapp

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Queries the back camera's supported video sizes via Camera2 -- shared by
 * [MainActivity]'s resolution picker, [WebServer]'s dashboard, and
 * [CctvServerService]'s max-resolution / clamp logic, so all three agree on what
 * the device can actually do instead of each guessing from a hardcoded list.
 */
object CameraResolutionUtil {
    private const val TAG = "CameraResolutionUtil"

    /** Cap at 4K to avoid encoder failures on sizes the hardware advertises but can't encode. */
    private const val MAX_PIXELS = 3840 * 2160

    private val FALLBACK = listOf(Pair(1920, 1080))

    /**
     * Supported [SurfaceTexture]-compatible sizes for the back camera (or the first
     * camera if none faces back), capped at 4K, deduped and sorted largest-first.
     * Falls back to `[(1920, 1080)]` if querying fails or the device reports nothing.
     */
    fun getSupportedResolutions(context: Context): List<Pair<Int, Int>> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Prefer the back camera rather than whichever id happens to be first --
            // on many devices id 0 is not the sensor actually being streamed.
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return FALLBACK

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Use SurfaceTexture sizes -- these are video-encoder compatible.
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return FALLBACK

            val validSizes = sizes.filter { it.width * it.height <= MAX_PIXELS }
            val chosen = (if (validSizes.isNotEmpty()) validSizes else sizes.toList())
                .map { Pair(it.width, it.height) }
                .distinct()
                .sortedByDescending { it.first.toLong() * it.second }

            if (chosen.isEmpty()) return FALLBACK

            android.util.Log.d(TAG, "Supported resolutions: " + chosen.joinToString { "${it.first}x${it.second}" })
            chosen
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to query camera resolutions", e)
            FALLBACK
        }
    }
}
