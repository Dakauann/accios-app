package com.example.accios.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RecognitionLogStore(context: Context) {

    private val logFile: File = File(context.filesDir, "logs_rec.json")
    private val lock = ReentrantLock()
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun append(personId: String, timestampSeconds: Long) {
        lock.withLock {
            val logs = readInternal().toMutableList()
            val timestampIso = isoFormatter.format(Date(timestampSeconds * 1000L))
            logs.add(
                JSONObject().apply {
                    put("id", personId)
                    put("timestamp", timestampIso)
                }
            )
            writeInternal(logs)
        }
    }

    fun getPendingLogs(): List<Map<String, Any>> {
        return lock.withLock {
            readInternal().mapNotNull { obj ->
                val id = obj.optString("id")
                val timestamp = obj.optString("timestamp")
                if (id.isNullOrBlank() || timestamp.isNullOrBlank()) {
                    null
                } else {
                    mapOf(
                        "id" to id,
                        "timestamp" to timestamp
                    )
                }
            }
        }
    }

    fun clear() {
        lock.withLock {
            if (logFile.exists()) {
                if (!logFile.delete()) {
                    Log.w(TAG, "Não foi possível remover arquivo de logs")
                }
            }
        }
    }

    private fun readInternal(): List<JSONObject> {
        if (!logFile.exists()) {
            return emptyList()
        }
        return try {
            val content = logFile.readText()
            if (content.isBlank()) return emptyList()
            val jsonArray = JSONArray(content)
            List(jsonArray.length()) { idx -> jsonArray.getJSONObject(idx) }
        } catch (ex: Exception) {
            Log.e(TAG, "Erro ao ler logs: ${ex.message}")
            backupCorruptedFile()
            emptyList()
        }
    }

    private fun writeInternal(logs: List<JSONObject>) {
        try {
            logFile.parentFile?.mkdirs()
            val jsonArray = JSONArray().apply {
                logs.forEach { put(it) }
            }
            logFile.writeText(jsonArray.toString())
        } catch (ex: IOException) {
            Log.e(TAG, "Erro ao salvar logs: ${ex.message}")
        }
    }

    private fun backupCorruptedFile() {
        try {
            if (!logFile.exists()) return
            val backupName = "${logFile.name}.${System.currentTimeMillis()}.bak"
            val backup = File(logFile.parentFile, backupName)
            logFile.copyTo(backup, overwrite = true)
            logFile.delete()
            Log.w(TAG, "Arquivo de logs corrompido movido para ${backup.absolutePath}")
        } catch (ex: Exception) {
            Log.e(TAG, "Erro ao criar backup de logs: ${ex.message}")
        }
    }

    companion object {
        private const val TAG = "RecognitionLogStore"
    }
}
