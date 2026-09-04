package com.zektopic.cctvapp.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Queries the back camera's supported video sizes and zoom range via Camera2 -- shared
 * by [MainActivity]'s pickers, [WebServer]'s dashboard, and [CctvServerService]'s
 * max-resolution / clamp logic, so all three agree on what the device can actually do
 * instead of each guessing from a hardcoded list.
 */
object CameraResolutionUtil {
    private const val TAG = "CameraResolutionUtil"

    /** Cap at 4K to avoid encoder failures on sizes the hardware advertises but can't encode. */
    private const val MAX_PIXELS = 3840 * 2160

    private val FALLBACK = listOf(Pair(1920, 1080))

    /** Used only if the device has no camera characteristics to query at all. */
    private val FALLBACK_ZOOM_RANGE = Pair(1.0f, 8.0f)

    /**
     * Prefer the back camera rather than whichever id happens to be first -- on many
     * devices id 0 is not the sensor actually being streamed.
     */
    private fun selectCameraId(cameraManager: CameraManager): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    /**
     * Supported [SurfaceTexture]-compatible sizes for the back camera (or the first
     * camera if none faces back), capped at 4K, deduped and sorted largest-first.
     * Falls back to `[(1920, 1080)]` if querying fails or the device reports nothing.
     */
    fun getSupportedResolutions(context: Context): List<Pair<Int, Int>> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = selectCameraId(cameraManager) ?: return FALLBACK

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

    /** Granularity the zoom range is snapped to -- matches the UI sliders' step size. */
    private const val ZOOM_STEP = 0.1f

    /**
     * The back camera's real digital zoom bounds, read directly from
     * [CameraCharacteristics] -- no open camera session required, unlike
     * `Camera2Base#getZoomRange()`. Prefers `CONTROL_ZOOM_RATIO_RANGE` (API 30+, the
     * accurate lower AND upper bound); falls back to `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`
     * (older API, upper bound only -- lower is always 1.0 for that property) on older
     * devices. Falls back to `(1.0, 8.0)` if querying fails entirely.
     *
     * Sensor-reported bounds are raw floats (e.g. `0.69999999`) that rarely divide evenly
     * by [ZOOM_STEP] -- [MainActivity]'s stepped `Slider` throws if `valueTo - valueFrom`
     * isn't a clean multiple of its step size. Snapped outward (floor the min, ceil the
     * max) so the UI range always covers the full real capability, never clips it.
     */
    fun getZoomRange(context: Context): Pair<Float, Float> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = selectCameraId(cameraManager) ?: return FALLBACK_ZOOM_RANGE
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    ?.let { Pair(it.lower, it.upper) }
            } else null

            val (rawMin, rawMax) = range
                ?: characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.let { Pair(1.0f, it) }
                ?: FALLBACK_ZOOM_RANGE

            val resolved = Pair(
                kotlin.math.floor(rawMin / ZOOM_STEP) * ZOOM_STEP,
                kotlin.math.ceil(rawMax / ZOOM_STEP) * ZOOM_STEP,
            )

            android.util.Log.d(TAG, "Zoom range: ${resolved.first}x-${resolved.second}x (raw $rawMin-$rawMax)")
            resolved
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to query camera zoom range", e)
            FALLBACK_ZOOM_RANGE
        }
    }
}
