package com.example.accios.util

import java.util.Locale

internal fun ByteArray.toHexString(): String {
    if (isEmpty()) return ""
    val result = StringBuilder(size * 2)
    for (b in this) {
        result.append(String.format(Locale.US, "%02x", b))
    }
    return result.toString()
}

internal fun String.hexToByteArray(): ByteArray {
    val clean = trim().lowercase(Locale.US)
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    val data = ByteArray(clean.length / 2)
    var i = 0
    while (i < clean.length) {
        val byte = clean.substring(i, i + 2).toInt(16)
        data[i / 2] = byte.toByte()
        i += 2
    }
    return data
}
