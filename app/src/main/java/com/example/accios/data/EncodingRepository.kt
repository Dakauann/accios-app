package com.example.accios.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

class EncodingRepository(context: Context) {

    data class SyncResult(
        val success: Boolean,
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
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var datasetRef: Dataset = Dataset(emptyList(), emptyList(), emptyMap(), 0)

    suspend fun loadFromDisk(): DatasetMeta? = withContext(Dispatchers.IO) {
        val loaded = loadDatasetFromDisk(encodingsDir) ?: return@withContext null
        datasetRef = loaded
        DatasetMeta(loaded.ids.size, loaded.dimension, getLastSyncEpochSeconds())
    }

    suspend fun applySyncDataset(payload: String, timestampSeconds: Long): SyncResult = withContext(Dispatchers.IO) {
        if (payload.isBlank()) {
            Log.w(TAG, "Sync payload vazio")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        val parsed = try {
            JSONObject(payload)
        } catch (ex: Exception) {
            Log.e(TAG, "Falha ao interpretar JSON do payload: ${ex.message}")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        if (parsed.has("error")) {
            Log.w(TAG, "Servidor retornou erro no sync: ${parsed.optString("error")}")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        val datasetArray = parsed.optJSONArray("dataset") ?: run {
            Log.w(TAG, "Payload sem campo 'dataset'")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        val dimension = parsed.optInt("embeddingDimension", inferDimension(datasetArray))
        if (dimension <= 0) {
            Log.w(TAG, "Dimensão de embedding inválida ($dimension)")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        val (embeddings, ids, roster) = buildDatasetLists(datasetArray, dimension)
        if (embeddings.isEmpty() || ids.isEmpty() || embeddings.size != ids.size) {
            Log.w(TAG, "Dataset inválido após parsing: embeddings=${embeddings.size}, ids=${ids.size}")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        if (!encodingsDir.exists() && !encodingsDir.mkdirs()) {
            Log.e(TAG, "Não foi possível criar diretório ${encodingsDir.absolutePath}")
            return@withContext SyncResult(false, datasetRef.ids.size, datasetRef.dimension, getLastSyncEpochSeconds())
        }

        val persistable = JSONObject().apply {
            put("embeddingDimension", dimension)
            put("dataset", JSONArray().also { array ->
                ids.forEachIndexed { index, personId ->
                    val embedding = embeddings[index]
                    val info = roster[personId]
                    array.put(JSONObject().apply {
                        put("id", personId)
                        info?.displayName?.let { put("name", it) }
                        put("embedding", JSONArray(embedding.map { it.toDouble() }))
                    })
                }
            })
            put("lastSyncEpochSeconds", timestampSeconds)
        }

        runCatching { datasetFile.writeText(persistable.toString()) }
            .onFailure { Log.e(TAG, "Falha ao persistir dataset local: ${it.message}") }

        datasetRef = Dataset(embeddings, ids, roster, dimension)
        setLastSyncEpochSeconds(timestampSeconds)
        SyncResult(true, ids.size, dimension, timestampSeconds)
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

    private fun loadDatasetFromDisk(directory: File): Dataset? {
        val target = File(directory, DATASET_FILE_NAME)
        if (!target.exists()) return null
        return try {
            val raw = target.readText()
            val json = JSONObject(raw)
            val datasetArray = json.optJSONArray("dataset") ?: JSONArray()
            val dimension = json.optInt("embeddingDimension", inferDimension(datasetArray))
            val (embeddings, ids, roster) = buildDatasetLists(datasetArray, dimension)
            if (embeddings.isEmpty() || ids.isEmpty() || embeddings.size != ids.size) {
                Log.e(TAG, "Dataset persistido inválido: embeddings=${embeddings.size}, ids=${ids.size}")
                return null
            }
            Dataset(embeddings, ids, roster, dimension)
        } catch (ex: Exception) {
            Log.e(TAG, "Falha ao carregar dataset local: ${ex.message}")
            null
        }
    }

    private fun buildDatasetLists(
        datasetArray: JSONArray,
        dimension: Int
    ): Triple<List<FloatArray>, List<String>, Map<String, PersonInfo>> {
        val embeddings = ArrayList<FloatArray>(datasetArray.length())
        val ids = ArrayList<String>(datasetArray.length())
        val roster = HashMap<String, PersonInfo>(datasetArray.length())

        for (index in 0 until datasetArray.length()) {
            val item = datasetArray.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").takeIf { it.isNotBlank() }
            val embeddingArray = item.optJSONArray("embedding") ?: continue

            if (embeddingArray.length() != dimension) {
                Log.w(TAG, "Embedding de tamanho inesperado (${embeddingArray.length()}) para id=$id")
                continue
            }

            val embedding = FloatArray(dimension)
            for (i in 0 until dimension) {
                embedding[i] = embeddingArray.optDouble(i, 0.0).toFloat()
            }
            normalizeVector(embedding)

            embeddings.add(embedding)
            ids.add(id)
            roster[id] = PersonInfo(name, item)
        }

        return Triple(embeddings, ids, roster)
    }

    private fun inferDimension(datasetArray: JSONArray): Int {
        for (index in 0 until datasetArray.length()) {
            val item = datasetArray.optJSONObject(index) ?: continue
            val embedding = item.optJSONArray("embedding") ?: continue
            if (embedding.length() > 0) {
                return embedding.length()
            }
        }
        return 0
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
        private const val DATASET_FILE_NAME = "dataset.json"
        const val EMBEDDING_MODEL_FILENAME = "buffalo_s.onnx"
    }
}
