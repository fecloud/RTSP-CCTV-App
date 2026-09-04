package com.zektopic.cctvapp.device

import java.util.Locale

object ThermalZoneUtil {


    fun isCpuThermalZone(type: String): Boolean {
        val normalized = type.lowercase(Locale.ROOT)
        return ("cpu" in normalized || "tsens" in normalized) && "trip" !in normalized
    }

    fun scaleThermalZoneCelsius(type: String, raw: Float): Float {
        if ("tsens" !in type.lowercase(Locale.ROOT)) return raw / 1000f
        val asDeciCelsius = raw / 10f
        return if (asDeciCelsius < 15f && raw in 15f..120f) raw else asDeciCelsius
    }
}
