package com.zektopic.cctvapp.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.StatFs
import android.util.Log
import java.io.File
import java.net.Inet4Address

object DeviceStatsUtil {
    private const val TAG = "DeviceStatsUtil"

    /**
     * Battery percentage, or -1 if unavailable.
     *
     * Uses BatteryManager directly rather than a sticky-broadcast registration: /status
     * is polled every few seconds by every connected dashboard, and registering a
     * receiver per request is needless work.
     */
    fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (capacity in 0..100) capacity else -1
    }

    /**
     * Battery temperature in Celsius, or null if unavailable.
     *
     * Unlike CPU temperature, this is a proper documented API: `EXTRA_TEMPERATURE` on
     * the `ACTION_BATTERY_CHANGED` sticky broadcast, in tenths of a degree. Passing a
     * null receiver to `registerReceiver` just reads the last sticky broadcast instead
     * of actually registering a listener, so there's nothing to unregister later.
     */
    fun getBatteryTemperatureCelsius(context: Context): Float? {
        val stickyIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    /**
     * Best-effort peak CPU core temperature in Celsius, or null when unavailable.
     *
     * There is no permission-free public API for this: `HardwarePropertiesManager`'s
     * `getDeviceTemperatures()` still requires the system-signature `DEVICE_POWER`
     * permission despite being nominally "open" since API 29, and `PowerManager` only
     * exposes a coarse thermal-status enum, not a number. This reads the kernel's
     * thermal-zone sysfs nodes directly instead -- no Android permission needed, but
     * whether a zone is even readable, and which zone (if any) is actually the CPU, is
     * entirely up to the device's kernel/SELinux policy, so this fails silently (and
     * unpredictably device-to-device) rather than being a reliable cross-device API.
     */
    fun getCpuTemperatureCelsius(): Float? {
        val zones = java.io.File("/sys/class/thermal").listFiles { f ->
            f.name.startsWith("thermal_zone")
        } ?: return null

        val readings = zones.mapNotNull { zone ->
            try {
                val type = File(zone, "type").readText().trim()
                if (!ThermalZoneUtil.isCpuThermalZone(type)) return@mapNotNull null
                val raw = File(zone, "temp").readText().trim().toFloatOrNull()
                    ?: return@mapNotNull null
                ThermalZoneUtil.scaleThermalZoneCelsius(type, raw).takeIf { it in -20f..120f }
            } catch (_: Exception) {
                null
            }
        }

        if (readings.isEmpty()) {
            Log.d(
                TAG,
                "No plausible CPU thermal zone reading; zones seen: " + zones.joinToString {
                    runCatching { java.io.File(it, "type").readText().trim() }.getOrDefault(it.name)
                }
            )
        }
        return readings.maxOrNull()
    }

    /**
     * Wi-Fi signal as a percentage, or -1 when it cannot be determined.
     *
     * `WifiManager.connectionInfo` is deprecated and, on modern Android, returns a
     * placeholder RSSI to apps without location permission. Reporting -1 (rendered as
     * a dash) is honest; reporting a number derived from -127 is not.
     */
    @Suppress("DEPRECATION")
    fun getWifiStrength(context: Context): Int {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return -1
            val rssi = wifiManager.connectionInfo?.rssi ?: return -1
            if (rssi == Int.MIN_VALUE || rssi <= -127) return -1
            WifiManager.calculateSignalLevel(rssi, 100).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi strength unavailable", e)
            -1
        }
    }

    fun getIpAddress(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ipv4Address = linkProperties?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        }?.address?.hostAddress
        return ipv4Address ?: "0.0.0.0"
    }

    fun usedPercent(statFs: StatFs): Int {
        val total = statFs.totalBytes
        if (total <= 0L) return 0
        return (((total - statFs.availableBytes) * 100) / total).toInt()
    }
}
