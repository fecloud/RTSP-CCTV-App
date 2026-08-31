package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the zoom mapping [Camera1Streamer] uses on `INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY`
 * devices, where the camera's `setZoom(Int)` takes a step index rather than a direct
 * multiplier like Camera2's `setZoom(Float)`.
 */
class Camera1ZoomMappingTest {

    private val appMin = AppPreferences.ZOOM_MIN
    private val appMax = AppPreferences.ZOOM_MAX

    @Test
    fun `app minimum maps to index 0`() {
        assertEquals(0, Camera1Streamer.mapZoomLevelToCamera1Index(appMin, appMin, appMax, 10))
    }

    @Test
    fun `app maximum maps to the last index`() {
        assertEquals(10, Camera1Streamer.mapZoomLevelToCamera1Index(appMax, appMin, appMax, 10))
    }

    @Test
    fun `midpoint maps proportionally`() {
        val midpoint = (appMin + appMax) / 2f
        assertEquals(5, Camera1Streamer.mapZoomLevelToCamera1Index(midpoint, appMin, appMax, 10))
    }

    @Test
    fun `a device reporting no zoom steps always maps to 0`() {
        assertEquals(0, Camera1Streamer.mapZoomLevelToCamera1Index(appMax, appMin, appMax, 0))
        assertEquals(0, Camera1Streamer.mapZoomLevelToCamera1Index(appMin, appMin, appMax, 0))
    }

    @Test
    fun `out of range input clamps instead of over- or under-shooting`() {
        assertEquals(0, Camera1Streamer.mapZoomLevelToCamera1Index(appMin - 5f, appMin, appMax, 10))
        assertEquals(10, Camera1Streamer.mapZoomLevelToCamera1Index(appMax + 5f, appMin, appMax, 10))
    }
}
