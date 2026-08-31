package com.gdreducacional.totemapp.data

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
        val entityType: String?,
        val distance: Float
    )

    private data class Dataset(
        val embeddings: List<FloatArray>,
        val ids: List<String>,
        val roster: Map<String, PersonInfo>,
        val dimension: Int,
        val generation: Long
    )

    data class PersonInfo(
        val displayName: String?,
        val entityType: String?,
        val extra: JSONObject?
    )

    private val appContext = context.applicationContext
    private val encodingsDir = File(appContext.filesDir, ENCODINGS_DIR_NAME)
    private val datasetFile = File(encodingsDir, DATASET_FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val datasetGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var datasetRef: Dataset = Dataset(emptyList(), emptyList(), emptyMap(), 0, 0L)

    private data class ThresholdCache(val generation: Long, val thresholdL2: Float)

    @Volatile
    private var thresholdCache: ThresholdCache? = null

    suspend fun loadFromDisk(): DatasetMeta? = withContext(Dispatchers.IO) {
        val loaded = loadDatasetFromDisk(encodingsDir) ?: return@withContext null
    thresholdCache = null
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
                        info?.entityType?.let { put("entityType", it) }
                        put("embedding", JSONArray(embedding.map { it.toDouble() }))
                    })
                }
            })
            put("lastSyncEpochSeconds", timestampSeconds)
        }

        runCatching { datasetFile.writeText(persistable.toString()) }
            .onFailure { Log.e(TAG, "Falha ao persistir dataset local: ${it.message}") }

        val generation = datasetGeneration.incrementAndGet()
        datasetRef = Dataset(embeddings, ids, roster, dimension, generation)
        thresholdCache = null
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
        return findTopK(embedding, k = 1).firstOrNull()
    }

    /**
     * Retorna os K vizinhos mais próximos (L2). Usado no match e nos logs de diagnóstico.
     */
    fun findTopK(embedding: FloatArray, k: Int = 3): List<MatchCandidate> {
        val current = datasetRef
        if (current.embeddings.isEmpty()) {
            return emptyList()
        }
        if (embedding.size != current.dimension) {
            Log.w(TAG, "Embedding dimension mismatch: expected ${current.dimension}, got ${embedding.size}")
            return emptyList()
        }
        if (k <= 0) return emptyList()

        // max-heap por distância (mantém os k menores)
        val top = java.util.PriorityQueue<Pair<Int, Float>>(k + 1) { a, b ->
            b.second.compareTo(a.second)
        }

        current.embeddings.forEachIndexed { index, stored ->
            val exitAt = if (top.size < k) Float.MAX_VALUE else top.peek().second
            val sqDist = squaredL2Distance(embedding, stored, exitAt)
            if (top.size < k) {
                top.offer(index to sqDist)
            } else if (sqDist < top.peek().second) {
                top.poll()
                top.offer(index to sqDist)
            }
        }

        return top
            .sortedBy { it.second }
            .map { (index, sqDist) ->
                val personId = current.ids[index]
                val info = current.roster[personId]
                MatchCandidate(
                    personId = personId,
                    displayName = info?.displayName,
                    entityType = info?.entityType,
                    distance = sqrt(sqDist.toDouble()).toFloat()
                )
            }
    }

    fun estimateThresholdL2(targetFmr: Double = DEFAULT_TARGET_FMR, sampleLimit: Int = DEFAULT_THRESHOLD_SAMPLE_LIMIT): Float {
        val current = datasetRef
        val cached = thresholdCache
        if (cached != null && cached.generation == current.generation) {
            return cached.thresholdL2
        }

        if (current.embeddings.size < MIN_CALIBRATION_PEOPLE) {
            Log.i(
                TAG,
                "Threshold default (poucas pessoas): people=${current.embeddings.size} thresholdL2=$DEFAULT_MATCH_THRESHOLD_L2"
            )
            return DEFAULT_MATCH_THRESHOLD_L2
        }

        val stats = computeCalibratedThresholdStats(current, targetFmr, sampleLimit)
        val computed = stats?.thresholdL2 ?: DEFAULT_MATCH_THRESHOLD_L2
        // Piso alto: pares impostores anômalos (embeddings corrompidos/quase iguais)
        // não podem derrubar o threshold e zerar o reconhecimento de toda a escola.
        val sanitized = computed.coerceIn(MIN_THRESHOLD_L2, MAX_THRESHOLD_L2)
        thresholdCache = ThresholdCache(current.generation, sanitized)
        Log.i(
            TAG,
            "Threshold recalibrated: people=${current.embeddings.size} " +
                "pairs=${stats?.pairCount ?: 0} fmr=$targetFmr " +
                "raw=${stats?.thresholdL2 ?: -1f} " +
                "minImpostor=${stats?.minImpostorL2 ?: -1f} " +
                "p01=${stats?.p01L2 ?: -1f} " +
                "p50=${stats?.p50L2 ?: -1f} " +
                "clamped=$sanitized " +
                "default=$DEFAULT_MATCH_THRESHOLD_L2 " +
                "floor=$MIN_THRESHOLD_L2 ceiling=$MAX_THRESHOLD_L2"
        )
        if (stats != null && stats.minImpostorL2 < MIN_THRESHOLD_L2) {
            Log.w(
                TAG,
                "ALERTA: há pares impostores com L2=${"%.4f".format(stats.minImpostorL2)} " +
                    "abaixo do piso $MIN_THRESHOLD_L2. Possíveis embeddings duplicados/corrompidos na base. " +
                    "Threshold forçado para $sanitized para não bloquear o reconhecimento."
            )
        }
        return sanitized
    }

    fun getThresholdDiagnostics(): String {
        val current = datasetRef
        val thr = estimateThresholdL2()
        return "people=${current.ids.size} dim=${current.dimension} gen=${current.generation} thresholdL2=$thr"
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
            val generation = datasetGeneration.incrementAndGet()
            thresholdCache = null
            Dataset(embeddings, ids, roster, dimension, generation)
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
            val entityType = item.optString("entityType").takeIf { it.isNotBlank() }
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
            roster[id] = PersonInfo(name, entityType, item)
        }

        return Triple(embeddings, ids, roster)
    }

    private data class CalibrationStats(
        val thresholdL2: Float,
        val pairCount: Int,
        val minImpostorL2: Float,
        val p01L2: Float,
        val p50L2: Float
    )

    private fun computeCalibratedThresholdStats(
        dataset: Dataset,
        targetFmr: Double,
        sampleLimit: Int
    ): CalibrationStats? {
        val embeddings = dataset.embeddings
        val ids = dataset.ids
        if (embeddings.size < 2) return null

        val impostorDistances = ArrayList<Float>(sampleLimit)
        val people = embeddings.indices
        outer@ for (i in people) {
            for (j in (i + 1) until embeddings.size) {
                if (ids[i] == ids[j]) continue
                val distance = l2Distance(embeddings[i], embeddings[j])
                // Descarta pares absurdamente próximos: quase certamente cadastro
                // duplicado / foto errada. Eles envenenam o FMR e derrubam o threshold.
                if (distance < OUTLIER_IMPOSTOR_L2_FLOOR) {
                    Log.w(
                        TAG,
                        "Par impostor anômalo ignorado na calibração: " +
                            "idA=${ids[i]} idB=${ids[j]} l2=${"%.4f".format(distance)}"
                    )
                    continue
                }
                impostorDistances.add(distance)
                if (impostorDistances.size >= sampleLimit) {
                    break@outer
                }
            }
        }

        if (impostorDistances.size < MIN_CALIBRATION_PAIRS) {
            Log.w(
                TAG,
                "Calibração insuficiente após filtrar outliers: pairs=${impostorDistances.size}, usando default"
            )
            return null
        }

        impostorDistances.sort()
        val sanitizedFmr = targetFmr.coerceIn(MIN_TARGET_FMR, MAX_TARGET_FMR)
        // Usa percentil mais estável: FMR operacional, nunca o 1º/2º menor da lista.
        val index = kotlin.math.max(
            0,
            kotlin.math.min(
                impostorDistances.lastIndex,
                kotlin.math.ceil(sanitizedFmr * impostorDistances.size).toInt()
            )
        )
        val p01Index = kotlin.math.max(
            0,
            kotlin.math.min(
                impostorDistances.lastIndex,
                kotlin.math.ceil(0.01 * impostorDistances.size).toInt()
            )
        )
        val p50Index = impostorDistances.size / 2
        return CalibrationStats(
            thresholdL2 = impostorDistances[index],
            pairCount = impostorDistances.size,
            minImpostorL2 = impostorDistances.first(),
            p01L2 = impostorDistances[p01Index],
            p50L2 = impostorDistances[p50Index]
        )
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
        return sqrt(squaredL2Distance(a, b).toDouble()).toFloat()
    }

    private fun squaredL2Distance(a: FloatArray, b: FloatArray, earlyExitThreshold: Float = Float.MAX_VALUE): Float {
        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
            if (sum >= earlyExitThreshold) return sum
        }
        return sum
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
        const val EMBEDDING_MODEL_FILENAME = "buffalo_m.onnx"
        /**
         * Threshold operacional ArcFace (buffalo_m), vetores L2-normalizados.
         * L2 ≈ sqrt(2 - 2*cos). cos≈0.45 → L2≈1.05.
         */
        private const val DEFAULT_MATCH_THRESHOLD_L2 = 1.05f
        /**
         * Piso do threshold. Antes era 0.5 e a calibração FMR 1e-4 caía em ~0.58
         * quando existiam 1-2 pares impostores anômalos na galeria → zero matches.
         */
        private const val MIN_THRESHOLD_L2 = 0.95f
        private const val MAX_THRESHOLD_L2 = 1.24f
        /**
         * FMR alvo da calibração. 1e-4 pegava o 1º/2º menor impostor e colapsava.
         * 1e-3 (0.1%) é operacional e estável em galerias escolares.
         */
        private const val DEFAULT_TARGET_FMR = 1e-3
        private const val MIN_TARGET_FMR = 1e-4
        private const val MAX_TARGET_FMR = 0.05
        /** Pares com L2 abaixo disso são tratados como cadastro ruim e ignorados. */
        private const val OUTLIER_IMPOSTOR_L2_FLOOR = 0.90f
        private const val DEFAULT_THRESHOLD_SAMPLE_LIMIT = 6000
        private const val MIN_CALIBRATION_PEOPLE = 6
        private const val MIN_CALIBRATION_PAIRS = 20
    }
}
