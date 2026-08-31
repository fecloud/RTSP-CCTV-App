package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the kernel thermal-zone reading used to display CPU temperature.
 *
 * The bug: "tsens_tz_sensorN" zones report decidegrees on some Qualcomm SoCs
 * (Snapdragon 820/821) but plain whole-degree Celsius on others (Snapdragon
 * 410-class, e.g. "cancro"/MI 4LTE) under the identical zone name -- dividing the
 * latter by 10 displayed ~6 C instead of the real ~60 C.
 */
class CpuTemperatureParsingTest {

    @Test
    fun `identifies cpu and tsens zones, case-insensitively`() {
        assertTrue(CctvServerService.isCpuThermalZone("cpu0"))
        assertTrue(CctvServerService.isCpuThermalZone("CPU-THERMAL"))
        assertTrue(CctvServerService.isCpuThermalZone("tsens_tz_sensor5"))
    }

    @Test
    fun `rejects non-cpu zones and static trip thresholds`() {
        assertFalse(CctvServerService.isCpuThermalZone("battery"))
        assertFalse(CctvServerService.isCpuThermalZone("pm8941_tz"))
        assertFalse(CctvServerService.isCpuThermalZone("cpu-hw-trip-0"))
    }

    @Test
    fun `snapdragon 820-style tsens zones are decidegrees`() {
        assertEquals(50.2f, CctvServerService.scaleThermalZoneCelsius("tsens_tz_sensor0", 502f), 0.01f)
    }

    @Test
    fun `cancro-style tsens zones are already whole-degree celsius`() {
        // Real reading pulled from an MI 4LTE ("cancro") device: raw 61 while the
        // system ThermalEngine logcat reported the identical sensor at 60000 mC.
        assertEquals(61f, CctvServerService.scaleThermalZoneCelsius("tsens_tz_sensor5", 61f), 0.01f)
    }

    @Test
    fun `non-tsens cpu zones remain millidegrees`() {
        assertEquals(48.5f, CctvServerService.scaleThermalZoneCelsius("cpu0-thermal", 48500f), 0.01f)
    }
}
