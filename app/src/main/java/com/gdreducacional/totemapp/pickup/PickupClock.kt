package com.gdreducacional.totemapp.pickup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Server time is anchored to Android's monotonic clock. A reboot invalidates
 * the anchor; changing the device's wall clock cannot extend the base.
 */
data class PickupTimeAnchor(
    val serverTimeMillis: Long,
    val receivedElapsedMillis: Long,
    val bootCount: Int,
    val roundTripMillis: Long,
    val validUntilMillis: Long
)

data class PickupTrustedTime(val lowerMillis: Long, val upperMillis: Long, val civilDate: String)

object PickupClock {
    fun current(anchor: PickupTimeAnchor, elapsedMillis: Long, bootCount: Int): PickupTrustedTime? {
        if (anchor.bootCount < 0 || anchor.bootCount != bootCount ||
            anchor.roundTripMillis < 0 || anchor.roundTripMillis > 120_000 ||
            elapsedMillis < anchor.receivedElapsedMillis ||
            anchor.serverTimeMillis <= 0 || anchor.validUntilMillis <= anchor.serverTimeMillis
        ) return null
        val elapsed = elapsedMillis - anchor.receivedElapsedMillis
        if (elapsed > 24 * 60 * 60 * 1000L) return null
        val lower = anchor.serverTimeMillis + elapsed
        val upper = lower + anchor.roundTripMillis
        if (upper > anchor.validUntilMillis) return null
        val lowerDate = civilDate(lower)
        if (lowerDate != civilDate(upper)) return null
        return PickupTrustedTime(lower, upper, lowerDate)
    }

    fun allows(validity: String, validDate: String?, civilDate: String): Boolean {
        if (!isValidDate(civilDate)) return false
        return when (validity) {
            "UNLIMITED" -> validDate == null
            "UNTIL_DATE" -> validDate != null && isValidDate(validDate) && validDate >= civilDate
            "ON_DATE" -> validDate != null && isValidDate(validDate) && validDate == civilDate
            else -> false
        }
    }

    fun isValidDate(value: String): Boolean {
        if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
        }
        return try { format.format(format.parse(value)!!) == value } catch (_: Exception) { false }
    }

    private fun civilDate(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
        }.format(Date(timeMillis))
    }
}
