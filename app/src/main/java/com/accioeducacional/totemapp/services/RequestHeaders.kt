package com.accioeducacional.totemapp.services

import okhttp3.Request

object RequestHeaders {

    private const val USER_AGENT = "AccioTotem/1.0.0 1.0.0"
    private const val CONTENT_TYPE_JSON = "application/json"
    private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"

    fun Request.Builder.applyJsonHeaders(token: String? = null): Request.Builder {
        header("Content-Type", CONTENT_TYPE_JSON)
        header("Accept", CONTENT_TYPE_JSON)
        header("User-Agent", USER_AGENT)
        token?.takeIf { it.isNotBlank() }?.let {
            header("Authorization", "Bearer $it")
        }
        return this
    }

    fun Request.Builder.applyApkDownloadHeaders(): Request.Builder {
        header("Accept", CONTENT_TYPE_APK)
        header("User-Agent", USER_AGENT)
        return this
    }

    fun Request.Builder.applyDefaultHeaders(): Request.Builder {
        header("User-Agent", USER_AGENT)
        return this
    }
}
