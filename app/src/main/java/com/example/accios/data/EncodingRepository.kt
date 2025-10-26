package com.example.accios.data

import android.content.Context
import android.util.Log
import com.example.accios.data.npz.NpyReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

class EncodingRepository(context: Context) {

    data class SyncResult(
        val success: Boolean,
        val modelUpdated: Boolean,
        val peopleCount: Int,
        val embeddingDimension: Int,
        val lastSyncEpochSeconds: Long?
    )

    data class DatasetMeta(
        val peopleCount: Int,
        val embeddingDimension: Int,
        val lastSyncEpochSeconds: Long?
    )

    data class MatchCandidate(
        val personId: String,
        val displayName: String?,
        val distance: Float
    )

    private data class Dataset(
        val embeddings: List<FloatArray>,
        val ids: List<String>,
        val roster: Map<String, PersonInfo>,
        val dimension: Int
    )

    data class PersonInfo(
        val displayName: String?,
        val extra: JSONObject?
    )

    private val appContext = context.applicationContext
    private val encodingsDir = File(appContext.filesDir, ENCODINGS_DIR_NAME)
    private val datasetFile = File(encodingsDir, DATASET_FILE_NAME)
    private val rosterFile = File(encodingsDir, ROSTER_FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var datasetRef: Dataset = Dataset(emptyList(), emptyList(), emptyMap(), 0)

    suspend fun loadFromDisk(): DatasetMeta? = withContext(Dispatchers.IO) {
        val loaded = loadDatasetFromDirectory(encodingsDir) ?: return@withContext null
        datasetRef = loaded
        DatasetMeta(loaded.ids.size, loaded.dimension, getLastSyncEpochSeconds())
    }

    suspend fun applySyncPayload(zipBytes: ByteArray, timestampSeconds: Long): SyncResult = withContext(Dispatchers.IO) {
        if (zipBytes.isEmpty()) {
            return@withContext SyncResult(false, false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        val tempDir = File(appContext.cacheDir, "encodings_sync_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            Log.e(TAG, "Unable to create temp dir ${tempDir.absolutePath}")
            return@withContext SyncResult(false, false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        var modelUpdated = false
        try {
            ByteArrayInputStream(zipBytes).use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry: ZipEntry? = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val normalizedName = entry.name.substringAfterLast('/')
                            val targetName = when {
                                normalizedName.equals(DATASET_FILE_NAME, ignoreCase = true) -> DATASET_FILE_NAME
                                normalizedName.equals(ROSTER_FILE_NAME, ignoreCase = true) -> ROSTER_FILE_NAME
                                normalizedName.endsWith(".tflite", ignoreCase = true) -> {
                                    modelUpdated = true
                                    EMBEDDING_MODEL_FILENAME
                                }
                                else -> normalizedName
                            }
                            val targetFile = secureResolve(tempDir, targetName)
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { out ->
                                zipStream.copyTo(out)
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }

            val newDataset = loadDatasetFromDirectory(tempDir)
            if (newDataset == null) {
                Log.e(TAG, "Failed to parse dataset from sync payload")
                return@withContext SyncResult(false, modelUpdated, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
            }

            if (!encodingsDir.exists() && !encodingsDir.mkdirs()) {
                Log.e(TAG, "Unable to create encodings dir ${encodingsDir.absolutePath}")
                return@withContext SyncResult(false, modelUpdated, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
            }

            moveFile(File(tempDir, DATASET_FILE_NAME), datasetFile)
            moveFile(File(tempDir, ROSTER_FILE_NAME), rosterFile)
            if (modelUpdated) {
                val modelSource = findFirstModelFile(tempDir)
                if (modelSource != null) {
                    moveFile(modelSource, File(encodingsDir, EMBEDDING_MODEL_FILENAME))
                } else {
                    modelUpdated = false
                }
            }

            datasetRef = newDataset
            setLastSyncEpochSeconds(timestampSeconds)
            SyncResult(true, modelUpdated, newDataset.ids.size, newDataset.dimension, timestampSeconds)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to apply sync payload: ${ex.message}", ex)
            SyncResult(false, modelUpdated, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun isReady(): Boolean = datasetRef.embeddings.isNotEmpty()

    fun getLastSyncEpochSeconds(): Long? {
        val stored = prefs.getLong(PREF_LAST_SYNC, -1L)
        return if (stored > 0) stored else null
    }

    fun getEmbeddingDimension(): Int = datasetRef.dimension

    fun getPeopleCount(): Int = datasetRef.ids.size

    fun findNearest(embedding: FloatArray): MatchCandidate? {
        val current = datasetRef
        if (current.embeddings.isEmpty()) {
            return null
        }
        if (embedding.size != current.dimension) {
            Log.w(TAG, "Embedding dimension mismatch: expected ${current.dimension}, got ${embedding.size}")
            return null
        }
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        current.embeddings.forEachIndexed { index, stored ->
            val distance = l2Distance(embedding, stored)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (bestIndex < 0) return null
        val personId = current.ids[bestIndex]
        val info = current.roster[personId]
        return MatchCandidate(personId, info?.displayName, bestDistance)
    }

    private fun loadDatasetFromDirectory(directory: File): Dataset? {
        if (!directory.exists()) return null
        val datasetPath = File(directory, DATASET_FILE_NAME)
        val rosterPath = File(directory, ROSTER_FILE_NAME)
        if (!datasetPath.exists() || !rosterPath.exists()) {
            return null
        }
        return try {
            val embeddings = ArrayList<FloatArray>()
            val ids = ArrayList<String>()
            var dimension = 0
            FileInputStream(datasetPath).use { fis ->
                ZipInputStream(fis).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val normalizedName = entry.name.substringAfterLast('/')
                        when (normalizedName) {
                            "embeddings.npy" -> {
                                val matrix = NpyReader.readFloatMatrix(zipStream)
                                matrix.rows.forEach { row ->
                                    normalizeVector(row)
                                    embeddings.add(row)
                                }
                                dimension = if (matrix.shape.size >= 2) matrix.shape.last() else matrix.rows.firstOrNull()?.size ?: 0
                            }
                            "ids.npy" -> {
                                val stringArray = NpyReader.readStringArray(zipStream)
                                ids.addAll(stringArray.values.map { it.trim() })
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }

            if (embeddings.isEmpty() || ids.isEmpty() || embeddings.size != ids.size) {
                Log.e(TAG, "Inconsistent dataset: embeddings=${embeddings.size}, ids=${ids.size}")
                return null
            }

            val roster = parseRoster(rosterPath)
            Dataset(embeddings, ids, roster, dimension)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to load dataset: ${ex.message}", ex)
            null
        }
    }

    private fun parseRoster(file: File): Map<String, PersonInfo> {
        return try {
            val json = JSONObject(file.readText())
            val students = json.optJSONArray("students") ?: JSONArray()
            buildMap {
                for (i in 0 until students.length()) {
                    val item = students.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isNullOrBlank()) continue
                    val displayName = item.optString("display_name").takeIf { it.isNotBlank() }
                    put(id, PersonInfo(displayName, item))
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to parse roster: ${ex.message}")
            emptyMap()
        }
    }

    private fun moveFile(source: File, target: File) {
        if (!source.exists()) return
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun findFirstModelFile(directory: File): File? {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val nested = findFirstModelFile(file)
                if (nested != null) return nested
            } else if (file.name.endsWith(".tflite", ignoreCase = true)) {
                return file
            }
        }
        return null
    }

    private fun secureResolve(baseDir: File, entryName: String): File {
        val target = File(baseDir, entryName)
        val canonical = target.canonicalPath
        val baseCanonical = baseDir.canonicalPath
        if (!canonical.startsWith(baseCanonical)) {
            throw IOException("Zip entry path traversal blocked: $entryName")
        }
        return target
    }

    private fun normalizeVector(vector: FloatArray) {
        var sum = 0f
        for (value in vector) {
            sum += value * value
        }
        val norm = sqrt(sum.toDouble()).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }

    private fun l2Distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum.toDouble()).toFloat()
    }

    private fun setLastSyncEpochSeconds(value: Long) {
        if (value > 0) {
            prefs.edit().putLong(PREF_LAST_SYNC, value).apply()
        } else {
            prefs.edit().remove(PREF_LAST_SYNC).apply()
        }
    }

    companion object {
        private const val TAG = "EncodingRepository"
        private const val PREFS_NAME = "encoding_repository"
        private const val PREF_LAST_SYNC = "last_sync_epoch"
        private const val ENCODINGS_DIR_NAME = "encodings"
        private const val DATASET_FILE_NAME = "dataset.npz"
        private const val ROSTER_FILE_NAME = "roster.json"
        const val EMBEDDING_MODEL_FILENAME = "embedding_model.tflite"
    }
}
