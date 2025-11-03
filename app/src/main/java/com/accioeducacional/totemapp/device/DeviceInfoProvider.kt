package com.accioeducacional.totemapp.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.Locale
import java.util.UUID

object DeviceInfoProvider {

    private const val PREFS_NAME = "device_info"
    private const val KEY_DEVICE_ID = "device_id"

    fun getMacAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filterNot { it.isLoopback || it.hardwareAddress == null || it.hardwareAddress.isEmpty() }
                .sortedBy { intf ->
                    when {
                        intf.name.startsWith("wlan", true) -> 0
                        intf.name.startsWith("eth", true) -> 1
                        else -> 2
                    }
                }

            interfaces.firstOrNull()?.hardwareAddress?.joinToString(":") { b ->
                String.format(Locale.US, "%02X", b)
            }
        } catch (ex: SocketException) {
            Log.w(TAG, "Unable to read MAC address: ${ex.message}")
            null
        }
    }

    fun getSerialNumber(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (ex: SecurityException) {
            Log.w(TAG, "Serial number requires READ_PHONE_STATE permission")
            null
        } catch (ex: Exception) {
            Log.w(TAG, "Unable to read serial number: ${ex.message}")
            null
        }?.takeIf { it.isNotBlank() }
            ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun getHostname(): String {
        return try {
            Build.DEVICE ?: Build.MODEL ?: "android-device"
        } catch (ex: Exception) {
            "android-device"
        }
    }

    fun getStableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val mac = getMacAddress()?.replace(":", "")?.replace("-", "")?.uppercase(Locale.US)
        val base = if (!mac.isNullOrBlank()) {
            "TOTEM-$mac"
        } else {
            "TOTEM-${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US).take(12)}"
        }

        prefs.edit().putString(KEY_DEVICE_ID, base).apply()
        return base
    }

    fun getDeviceInfo(context: Context): Map<String, String?> {
        return mapOf(
            "mac_address" to getMacAddress(),
            "serial_number" to getSerialNumber(context),
            "hostname" to getHostname()
        )
    }

    private const val TAG = "DeviceInfoProvider"
}
