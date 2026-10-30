package com.gdreducacional.totemapp

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gdreducacional.totemapp.config.ServerConfig
import com.gdreducacional.totemapp.device.DeviceInfoProvider
import com.gdreducacional.totemapp.data.EncodingRepository
import com.gdreducacional.totemapp.services.ApiService
import com.gdreducacional.totemapp.services.PairingService
import com.gdreducacional.totemapp.services.FaceEmbeddingModel
import com.gdreducacional.totemapp.services.ModelProvider
import com.gdreducacional.totemapp.services.RecognitionEngine
import com.gdreducacional.totemapp.views.RecognitionCandidate
import com.gdreducacional.totemapp.storage.RecognitionLogEntry
import com.gdreducacional.totemapp.storage.RecognitionLogStore
import com.gdreducacional.totemapp.pickup.OperationMode
import com.gdreducacional.totemapp.pickup.PickupClock
import com.gdreducacional.totemapp.pickup.PickupExitQueue
import com.gdreducacional.totemapp.pickup.PickupMatch
import com.gdreducacional.totemapp.pickup.PickupSnapshot
import com.gdreducacional.totemapp.pickup.PickupSnapshotStore
import com.gdreducacional.totemapp.pickup.PickupStudent
import com.gdreducacional.totemapp.pickup.PickupRejectedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pairingService = PairingService(application, ServerConfig.DEFAULT_SERVER_URL)
    private val recognitionLogStore = RecognitionLogStore(application)
    private val encodingRepository = EncodingRepository(application)
    private val embeddingModel = FaceEmbeddingModel(application)
    private val recognitionEngine = RecognitionEngine(embeddingModel, encodingRepository)
    private val pickupRepository = EncodingRepository(application)
    private val pickupEngine = RecognitionEngine(embeddingModel, pickupRepository, privateMode = true)
    private val pickupSnapshots = PickupSnapshotStore(application)
    private val pickupQueue = PickupExitQueue(application)
    private val pickupPrefs = application.getSharedPreferences("pickup_operation", 0)
    @Volatile private var pickupSnapshot: PickupSnapshot? = null
    @Volatile private var pickupUpdating = false
    @Volatile private var pickupAwaitingIds: Set<String> = emptySet()
    @Volatile private var pickupAwaitingNames: Map<String, String> = emptyMap()
    @Volatile private var pickupQueueUnavailable = false
    @Volatile private var lastHeartbeatElapsedMillis: Long? = null
    private val pickupSyncMutex = Mutex()
    private val pickupQueueMutex = Mutex()
    private val modelProvider = ModelProvider(application)
    private val baseSyncMutex = Mutex()
    private var successPlayer: MediaPlayer? = null
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _uiState.update { it.copy(isNetworkAvailable = true) }
        }
        override fun onLost(network: Network) {
            _uiState.update { it.copy(isNetworkAvailable = false) }
        }
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var apiService: ApiService? = null
    private var heartbeatJob: Job? = null
    private var logSyncJob: Job? = null
    private var pickupQueueJob: Job? = null
    private var pickupSyncJob: Job? = null
    private var recognitionResetJob: Job? = null

    fun canAttemptRecognition(): Boolean {
        val state = _uiState.value
        if (state.operationMode == OperationMode.PICKUP &&
            (state.pickupMatch != null || state.pickupMessage != null || state.pickupBusy || pickupUpdating ||
                pickupQueueUnavailable || pickupAwaitingIds.isNotEmpty() || trustedPickupTime() == null)) return false
        return true
    }
    private var modelInitJob: Job? = null
    private val heartbeatIntervalMillis = 60_000L
    private val logSyncIntervalMillis = 20_000L

    init {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        val activeNetwork = connectivityManager.activeNetwork
        val activeCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val initialNetworkAvailable = activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val crypto = pairingService.getCryptoManager()
        val paired = pairingService.isPaired()
        val token = crypto.loadAccessToken()
        val serverUrl = pairingService.getLastServerUrl()
        _uiState.update {
            it.copy(
                isPaired = paired,
                accessToken = token,
                deviceId = DeviceInfoProvider.getStableDeviceId(application),
                scannerEnabled = !paired,
                statusMessage = if (paired) "Dispositivo pareado" else "Aguardando pareamento",
                serverUrl = serverUrl,
                isNetworkAvailable = initialNetworkAvailable,
                operationMode = if (pickupPrefs.getString("mode", "STANDARD") == "PICKUP") OperationMode.PICKUP else OperationMode.STANDARD
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val meta = encodingRepository.loadFromDisk()
            if (meta != null) {
                // Força calibração + logs de threshold no boot
                val thrDiag = encodingRepository.getThresholdDiagnostics()
                Log.i(TAG, "Base local carregada: people=${meta.peopleCount} dim=${meta.embeddingDimension} $thrDiag")
                _uiState.update { state ->
                    state.copy(
                        baseLoaded = encodingRepository.isReady(),
                        baseRosterCount = meta.peopleCount,
                        baseEmbeddingDimension = meta.embeddingDimension,
                        lastBaseSyncEpochSeconds = meta.lastSyncEpochSeconds
                            ?: state.lastBaseSyncEpochSeconds
                    )
                }
            } else {
                Log.w(TAG, "Nenhuma base local de encodings encontrada em disco")
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                pickupSyncMutex.withLock {
                    val snapshot = pickupSnapshots.load()
                    if (snapshot != null) {
                        val loaded = pickupRepository.applySyncDataset(snapshot.payload, 0L, persist = false)
                        if (loaded.success) pickupSnapshot = snapshot
                    }
                }
                pickupAwaitingNames = pickupQueue.pendingOnlineNames()
                pickupAwaitingIds = pickupAwaitingNames.keys
                refreshPickupStatus()
            } catch (error: Exception) {
                Log.e(TAG, "Não foi possível carregar a base da portaria", error)
                _uiState.update { it.copy(pickupStatus = "Não foi possível ler a base da portaria. Sincronize novamente.") }
            }
        }

        modelInitJob = viewModelScope.launch(Dispatchers.IO) {
            initializeModel()
        }

        if (paired && !token.isNullOrBlank()) {
            rebuildApiService(serverUrl, token)
        }
    }

    private suspend fun initializeModel() {
        _uiState.update { it.copy(modelStatus = ModelStatus.Checking) }

        val result = modelProvider.ensure { progress ->
            _uiState.update {
                it.copy(
                    modelStatus = ModelStatus.Downloading,
                    modelDownloadProgress = progress
                )
            }
        }

        result.fold(
            onSuccess = {
                _uiState.update { it.copy(modelStatus = ModelStatus.Initializing) }
                try {
                    embeddingModel.initialize()
                    _uiState.update { it.copy(modelStatus = ModelStatus.Ready) }
                } catch (ex: Exception) {
                    Log.e(TAG, "Falha ao inicializar modelo: ${ex.message}", ex)
                    _uiState.update {
                        it.copy(
                            modelStatus = ModelStatus.Error,
                            modelError = "Falha ao carregar modelo"
                        )
                    }
                }
            },
            onFailure = { ex ->
                Log.e(TAG, "Falha ao obter modelo: ${ex.message}", ex)
                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.Error,
                        modelError = "Falha ao baixar modelo"
                    )
                }
            }
        )
    }

    fun retryModelDownload() {
        modelInitJob?.cancel()
        modelInitJob = viewModelScope.launch(Dispatchers.IO) {
            initializeModel()
        }
    }

    fun toggleSettings() {
        val target = !_uiState.value.showSettings
        _uiState.update { it.copy(showSettings = target) }
        if (target) {
            refreshRecentLogs()
        }
    }

    fun setSettingsVisible(visible: Boolean) {
        val shouldRefresh = visible && !_uiState.value.showSettings
        _uiState.update { it.copy(showSettings = visible) }
        if (shouldRefresh) {
            refreshRecentLogs()
        }
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) { -1 }

    private fun trustedPickupTime() = pickupSnapshot?.let {
        PickupClock.current(it.anchor, SystemClock.elapsedRealtime(), bootCount())
    }

    private fun refreshPickupStatus() {
        val queueStatus = try { pickupQueue.status() } catch (error: Exception) {
            pickupQueueUnavailable = true
            _uiState.update { it.copy(pickupStatus = "Não foi possível ler os registros pendentes da portaria.") }
            return
        }
        pickupQueueUnavailable = false
        val rejectedEvents = try { pickupQueue.rejectedEvents() } catch (_: Exception) {
            pickupQueueUnavailable = true
            _uiState.update { it.copy(pickupStatus = "Não foi possível ler os registros pendentes da portaria.") }
            return
        }
        val awaiting = pickupAwaitingIds
        var settledMessage: String? = null
        if (awaiting.isNotEmpty()) {
            val inspection = try { pickupQueue.inspect(awaiting) } catch (_: Exception) {
                pickupQueueUnavailable = true
                _uiState.update { it.copy(pickupStatus = "Não foi possível ler os registros pendentes da portaria.") }
                return
            }
            if (inspection.pendingIds.isEmpty()) {
                val confirmedNames = (awaiting - inspection.rejectedIds).map { pickupAwaitingNames[it] ?: "Aluno" }
                val rejectedNames = inspection.rejectedIds.map { pickupAwaitingNames[it] ?: "Aluno" }
                settledMessage = if (rejectedNames.isEmpty()) "Retirada confirmada: ${confirmedNames.joinToString(", ")}."
                    else buildString {
                        if (confirmedNames.isNotEmpty()) append("Confirmados: ${confirmedNames.joinToString(", ")}. ")
                        append("Não confirmados: ${rejectedNames.joinToString(", ")}. Não libere esses alunos.")
                    }
                pickupAwaitingIds = emptySet()
                pickupAwaitingNames = emptyMap()
            }
        }
        val snapshot = pickupSnapshot
        val message = when {
            snapshot == null -> "Sincronize a base da portaria."
            trustedPickupTime() == null -> "Base vencida ou horário não verificável. Conecte para sincronizar."
            !pickupRepository.isReady() -> "Nenhuma pessoa com facial cadastrada para a portaria."
            else -> null
        }
        _uiState.update { it.copy(
            pickupStatus = message, pickupPending = queueStatus.pending, pickupRejected = queueStatus.rejected,
            pickupRejectedEvents = rejectedEvents,
            pickupAwaiting = pickupAwaitingIds.isNotEmpty(),
            pickupMessage = settledMessage ?: it.pickupMessage
        ) }
    }

    fun setOperationMode(mode: OperationMode) {
        if (_uiState.value.operationMode == mode) return
        pickupPrefs.edit().putString("mode", mode.name).apply()
        recognitionResetJob?.cancel()
        _uiState.update { it.copy(operationMode = mode, pickupMatch = null, recognitionStatus = RecognitionStatus.Idle, recognitionMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            refreshPickupStatus()
            val service = apiService ?: return@launch
            if (mode == OperationMode.PICKUP) performPickupSync(service) else performBaseSync(service)
        }
    }

    fun unpair() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = try { pickupQueue.status() } catch (_: Exception) {
                _uiState.update { it.copy(statusMessage = "Não foi possível verificar retiradas pendentes.") }
                return@launch
            }
            if (status.pending > 0 || status.rejected > 0) {
                _uiState.update { it.copy(statusMessage = "Sincronize ou resolva as retiradas pendentes antes de desparear.") }
                return@launch
            }
            heartbeatJob?.cancel()
            logSyncJob?.cancel()
            pickupQueueJob?.cancel()
            pickupSyncJob?.cancel()
            apiService = null
            pickupSyncMutex.withLock {
                pickupSnapshots.clear()
                pickupSnapshot = null
                pickupRepository.applySyncDataset("{\"dataset\":[],\"embeddingDimension\":0}", 0L, persist = false)
                pairingService.unpair()
            }
            _uiState.update {
                it.copy(
                    isPaired = false,
                    scannerEnabled = true,
                    showSettings = false,
                    statusMessage = "Despareado",
                    deviceId = null,
                    serverUrl = pairingService.getLastServerUrl(),
                    baseLoaded = false,
                    baseRosterCount = 0,
                    recentLogs = emptyList()
                )
            }
        }
    }

    fun refreshRecentLogs(limit: Int = 25) {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = recognitionLogStore.getRecentLogs(limit)
            _uiState.update { state ->
                state.copy(recentLogs = logs)
            }
        }
    }

    fun onQrDetected(rawValue: String) {
        val current = _uiState.value
        if (current.isPairingInProgress || current.isPaired) return

        _uiState.update {
            it.copy(
                isPairingInProgress = true,
                statusMessage = "Validando QRCode...",
                pairingError = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val outcome = pairingService.pairFromJwt(rawValue)
            _uiState.update { state ->
                when (outcome) {
                    is PairingService.PairingOutcome.Success -> {
                        state.copy(
                            isPaired = true,
                            isPairingInProgress = false,
                            scannerEnabled = false,
                            statusMessage = "Pareado como ${outcome.deviceId}",
                            deviceId = outcome.deviceId,
                            accessToken = outcome.accessToken,
                            serverUrl = outcome.serverUrl,
                            pairingError = null
                        )
                    }
                    is PairingService.PairingOutcome.Failure -> {
                        state.copy(
                            isPairingInProgress = false,
                            scannerEnabled = true,
                            statusMessage = "Falha no pareamento",
                            pairingError = outcome.reason
                        )
                    }
                }
            }

            if (outcome is PairingService.PairingOutcome.Success) {
                outcome.accessToken.let { tokenValue ->
                    rebuildApiService(outcome.serverUrl, tokenValue)
                }
            }
        }
    }

    fun markRecognitionStatus(status: RecognitionStatus, message: String? = null) {
        _uiState.update {
            it.copy(recognitionStatus = status, recognitionMessage = message)
        }
    }

    fun updateLastSync(timestamp: Long) {
        _uiState.update {
            it.copy(lastSyncEpochSeconds = timestamp)
        }
    }

    fun updateAmbientLuminance(luminance: Float) {
        _uiState.update { state ->
            val nextLowLight = when {
                luminance < LOW_LIGHT_ENTER_THRESHOLD -> true
                luminance > LOW_LIGHT_EXIT_THRESHOLD -> false
                else -> state.isLowLight
            }

            if (state.ambientLuminance != null &&
                abs(state.ambientLuminance - luminance) < 2f &&
                state.isLowLight == nextLowLight
            ) {
                state
            } else {
                state.copy(
                    ambientLuminance = luminance,
                    isLowLight = nextLowLight
                )
            }
        }
    }

    fun submitRecognitionCandidate(
        candidate: RecognitionCandidate,
        isSubjectPresent: () -> Boolean = { true },
        onFinished: (Boolean) -> Unit
    ) {
        if (!canAttemptRecognition()) {
            candidate.frames.forEach { it.recycleSafely() }
            onFinished(false)
            return
        }
        if (candidate.frames.isEmpty()) {
            Log.w(TAG, "submitRecognition: frames vazio trackId=${candidate.trackId}")
            onRecognitionError("Falha ao processar imagem", onFinished)
            return
        }

        val mode = _uiState.value.operationMode
        val snapshotAtRecognition = if (mode == OperationMode.PICKUP) pickupSnapshot?.snapshotId else null
        val activeRepository = if (mode == OperationMode.PICKUP) pickupRepository else encodingRepository
        val activeEngine = if (mode == OperationMode.PICKUP) pickupEngine else recognitionEngine
        if (!activeRepository.isReady()) {
            Log.w(
                TAG,
                "submitRecognition: base indisponível people=${activeRepository.getPeopleCount()} " +
                    "modelReady=${embeddingModel.isReady()}"
            )
            candidate.frames.forEach { it.recycleSafely() }
            onRecognitionError("Base local indisponível", onFinished)
            return
        }

        Log.i(
            TAG,
            "submitRecognition: trackId=${candidate.trackId} frames=${candidate.frames.size} " +
                "people=${activeRepository.getPeopleCount()} " +
                "modelReady=${embeddingModel.isReady()} " +
                "frame0=${candidate.frames.firstOrNull()?.let { "${it.width}x${it.height}" }}"
        )

        if (candidate.commitQuality) {
            markRecognitionStatus(RecognitionStatus.Detecting, "Verificando identidade...")
        }

        viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                activeEngine.recognize(
                    RecognitionEngine.RecognitionBatch(candidate.frames, candidate.trackId)
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Erro no reconhecimento: ${ex.message}", ex)
                null
            } finally {
                candidate.frames.forEach { it.recycleSafely() }
            }

            withContext(Dispatchers.Main) {
                if (_uiState.value.operationMode != mode) {
                    onFinished(false)
                    return@withContext
                }
                if (mode == OperationMode.PICKUP && (pickupUpdating || pickupSnapshot?.snapshotId != snapshotAtRecognition)) {
                    onFinished(false)
                    return@withContext
                }
                if (result != null) {
                    if (mode == OperationMode.STANDARD) {
                        Log.i(
                            TAG,
                            "Recognition SUCCESS id=${result.personId} name=${result.displayName} " +
                                "conf=${"%.4f".format(result.confidence)} dist=${"%.4f".format(result.distance)}"
                        )
                    }
                    if (mode == OperationMode.PICKUP) {
                        onFinished(registerPickupRecognition(result))
                    } else {
                        registerRecognitionSuccess(result.personId, result.displayName, result.entityType, result.confidence.toDouble())
                        onFinished(true)
                    }
                } else if (candidate.forceAnnounce && isSubjectPresent()) {
                    Log.i(TAG, "Recognition COMMIT MISS trackId=${candidate.trackId}")
                    onRecognitionFailed("Não reconhecido")
                    onFinished(false)
                } else if (!isSubjectPresent()) {
                    Log.i(TAG, "Recognition MISS sem rosto; idle trackId=${candidate.trackId}")
                    applyRecognitionIdleState()
                    onFinished(false)
                } else {
                    Log.i(
                        TAG,
                        "Recognition silent miss trackId=${candidate.trackId} " +
                            "commit=${candidate.commitQuality} score=${"%.3f".format(candidate.qualityScore)}"
                    )
                    onFinished(false)
                }
            }
        }
    }

    fun resetRecognitionState() {
        recognitionResetJob?.cancel()
        recognitionResetJob = null
        applyRecognitionIdleState()
    }

    private fun playAccessGrantedSound() {
        val app = getApplication<Application>()
        val player = successPlayer ?: MediaPlayer().also { successPlayer = it }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        runCatching {
            app.assets.openFd("audios/acessoautorizado.mp3").use { descriptor ->
                player.reset()
                player.setAudioAttributes(attributes)
                player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                player.prepare()
                player.start()
            }
        }.onFailure { error ->
            Log.w(TAG, "Falha ao reproduzir áudio de acesso autorizado: ${error.message}")
        }
    }

    private fun registerRecognitionSuccess(personId: String?, personName: String?, entityType: String?, confidence: Double) {
        val timestampSeconds = System.currentTimeMillis() / 1000L
        if (!personId.isNullOrBlank()) {
            recognitionLogStore.append(personId, timestampSeconds, entityType)
        }

        _uiState.update {
            it.copy(
                recognitionStatus = RecognitionStatus.Recognized,
                recognitionMessage = when {
                    !personName.isNullOrBlank() -> "Bem-vindo(a)!"
                    else -> "Acesso liberado"
                },
                recognizedPersonId = personId,
                recognizedPersonName = personName,
                recognitionConfidence = if (confidence.isNaN()) null else confidence,
                statusMessage = "Acesso autorizado",
                lastRecognitionEpochSeconds = timestampSeconds
            )
        }

        playAccessGrantedSound()

        if (_uiState.value.showSettings) {
            refreshRecentLogs()
        }

        scheduleRecognitionReset()
    }

    private fun registerPickupRecognition(result: RecognitionEngine.RecognitionResult): Boolean {
        val trusted = trustedPickupTime() ?: run {
            onRecognitionFailed("Base vencida. Conecte para sincronizar.")
            return false
        }
        val snapshot = pickupSnapshot ?: return false
        val info = pickupRepository.getPersonInfo(result.personId)?.extra ?: return false
        val array = info.optJSONArray("students") ?: return false
        val students = ArrayList<PickupStudent>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val validity = item.optString("validity")
            val date = if (item.isNull("validDate")) null else item.optString("validDate")
            if (!PickupClock.allows(validity, date, trusted.civilDate)) continue
            students.add(PickupStudent(
                enrollmentId = item.getString("studentHasBranchId"),
                studentId = item.getString("studentId"),
                name = item.getString("name"),
                className = item.getString("className"),
                photoBase64 = if (item.isNull("photoBase64")) null else item.optString("photoBase64"),
                validity = validity,
                validDate = date
            ))
        }
        if (students.isEmpty()) {
            onRecognitionFailed("Nenhuma retirada autorizada para hoje.")
            return false
        }
        val entityType = result.entityType
        if (entityType != "user" && entityType != "pickup_person") return false
        _uiState.update {
            it.copy(
                recognitionStatus = RecognitionStatus.Recognized,
                recognizedPersonId = result.personId,
                recognizedPersonName = result.displayName,
                recognitionConfidence = result.confidence.toDouble(),
                pickupMessage = null,
                pickupMatch = PickupMatch(
                    personId = result.personId, entityType = entityType,
                    personName = result.displayName ?: "Responsável",
                    confidence = result.confidence.toDouble(), snapshotId = snapshot.snapshotId,
                    students = students
                )
            )
        }
        return true
    }

    fun togglePickupStudent(enrollmentId: String) {
        _uiState.update { state ->
            val match = state.pickupMatch ?: return@update state
            if (state.pickupBusy || match.students.none { it.enrollmentId == enrollmentId }) return@update state
            val selected = match.selectedEnrollmentIds.toMutableSet()
            if (!selected.add(enrollmentId)) selected.remove(enrollmentId)
            state.copy(pickupMatch = match.copy(selectedEnrollmentIds = selected))
        }
    }

    fun cancelPickupMatch() {
        _uiState.update { state ->
            if (state.pickupBusy) state else state.copy(
                pickupMatch = null, recognitionStatus = RecognitionStatus.Idle,
                recognitionMessage = null, recognizedPersonId = null, recognizedPersonName = null
            )
        }
    }

    fun dismissPickupMessage() {
        _uiState.update { it.copy(pickupMessage = null) }
    }

    fun confirmPickup() {
        val state = _uiState.value
        val match = state.pickupMatch ?: return
        if (state.pickupBusy || match.selectedEnrollmentIds.isEmpty()) return
        _uiState.update { it.copy(pickupBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trusted = trustedPickupTime()
                    ?: throw IllegalStateException("A base venceu. Sincronize antes de confirmar.")
                if (pickupSnapshot?.snapshotId != match.snapshotId) {
                    throw IllegalStateException("A base foi atualizada. Faça o reconhecimento novamente.")
                }
                val selected = match.students.filter { it.enrollmentId in match.selectedEnrollmentIds }
                if (selected.size != match.selectedEnrollmentIds.size ||
                    selected.any { !PickupClock.allows(it.validity, it.validDate, trusted.civilDate) }) {
                    throw IllegalStateException("Uma autorização não é mais válida para hoje.")
                }
                val lastHeartbeat = lastHeartbeatElapsedMillis
                val elapsed = SystemClock.elapsedRealtime()
                val online = state.isNetworkAvailable && lastHeartbeat != null && elapsed >= lastHeartbeat &&
                    elapsed - lastHeartbeat <= 90_000L && apiService != null
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(trusted.lowerMillis))
                val events = selected.map { student ->
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("snapshotId", match.snapshotId)
                        .put("personId", match.personId)
                        .put("entityType", match.entityType)
                        .put("studentHasBranchId", student.enrollmentId)
                        .put("confirmedAt", timestamp)
                        .put("confidence", match.confidence)
                        .put("offline", !online)
                }
                val studentNames = events.zip(selected).associate { (event, student) -> event.getString("id") to student.name }
                pickupQueue.append(events, studentNames)
                if (online) {
                    pickupAwaitingNames = studentNames
                    pickupAwaitingIds = studentNames.keys
                }
                var message = "Retirada registrada neste aparelho. Será enviada quando a conexão voltar."
                if (online) {
                    val service = apiService ?: throw IllegalStateException("Conexão indisponível.")
                    message = try {
                        pickupQueueMutex.withLock {
                            val response = service.sendPickupEvents(events)
                            val data = response.decryptedBody?.let { JSONObject(it) }
                            if (response.statusCode != 200 || data?.has("results") != true) {
                                "Confirmação pendente. Não libere o aluno até a resposta do servidor."
                            } else {
                                val results = data.getJSONArray("results")
                                pickupQueue.applyResults(results)
                                val acceptedIds = (0 until results.length()).map { results.getJSONObject(it) }
                                    .filter { it.optString("status") == "accepted" }
                                    .map { it.optString("id") }.toSet()
                                if (acceptedIds.containsAll(studentNames.keys) && results.length() == events.size) {
                                    "Retirada confirmada."
                                } else {
                                    val confirmedNames = studentNames.filterKeys { it in acceptedIds }.values
                                    val unconfirmedNames = studentNames.filterKeys { it !in acceptedIds }.values
                                    buildString {
                                        if (confirmedNames.isNotEmpty()) append("Confirmados: ${confirmedNames.joinToString(", ")}. ")
                                        append("Não confirmados: ${unconfirmedNames.joinToString(", ")}. Não libere esses alunos.")
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        "Confirmação pendente. Não libere o aluno até a resposta do servidor."
                    }
                }
                _uiState.update { it.copy(
                    pickupMatch = null, pickupMessage = message, pickupBusy = false,
                    recognitionStatus = RecognitionStatus.Idle, recognitionMessage = null,
                    recognizedPersonId = null, recognizedPersonName = null
                ) }
                refreshPickupStatus()
            } catch (error: Exception) {
                _uiState.update { it.copy(
                    pickupMatch = null, pickupMessage = "${error.message ?: "Falha ao registrar."} Não libere o aluno.",
                    pickupBusy = false, recognitionStatus = RecognitionStatus.Idle
                ) }
            }
        }
    }

    private fun onRecognitionFailed(message: String) {
        val displayMessage = message.ifBlank { "Acesso negado" }
        _uiState.update {
            it.copy(
                recognitionStatus = RecognitionStatus.Error,
                recognitionMessage = displayMessage,
                statusMessage = "Acesso negado",
                recognizedPersonId = null,
                recognizedPersonName = null,
                recognitionConfidence = null
            )
        }

        scheduleRecognitionReset()
    }

    private fun onRecognitionError(message: String, onFinished: (Boolean) -> Unit) {
        onRecognitionFailed(message)
        onFinished(false)
    }

    private fun scheduleRecognitionReset() {
        recognitionResetJob?.cancel()
        recognitionResetJob = viewModelScope.launch {
            delay(RECOGNITION_RESET_DELAY_MILLIS)
            applyRecognitionIdleState()
            recognitionResetJob = null
        }
    }

    private fun applyRecognitionIdleState() {
        _uiState.update { state ->
            if (state.recognitionStatus == RecognitionStatus.Idle &&
                state.recognitionMessage == null &&
                state.recognizedPersonId == null &&
                state.recognizedPersonName == null
            ) {
                state
            } else {
                state.copy(
                    recognitionStatus = RecognitionStatus.Idle,
                    recognitionMessage = null,
                    recognizedPersonId = null,
                    recognizedPersonName = null,
                    recognitionConfidence = null
                )
            }
        }
    }

    private fun Bitmap.recycleSafely() {
        try {
            if (!isRecycled) recycle()
        } catch (_: Exception) {
        }
    }

    private fun rebuildApiService(serverUrl: String, token: String) {
        apiService = ApiService(pairingService.getCryptoManager(), serverUrl, token)
        startSyncJobs()
        triggerInitialBaseSync()
    }

    private fun startSyncJobs() {
        val service = apiService ?: return

        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val result = service.heartbeat()
                    if (result.statusCode == 200) {
                        val now = System.currentTimeMillis() / 1000L
                        lastHeartbeatElapsedMillis = SystemClock.elapsedRealtime()
                        _uiState.update {
                            it.copy(lastHeartbeatEpochSeconds = now)
                        }
                        handleHeartbeatResponse(service, result)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Heartbeat falhou: ${ex.message}")
                }
                delay(heartbeatIntervalMillis)
            }
        }

        logSyncJob?.cancel()
        logSyncJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val logs = recognitionLogStore.getPendingLogs()
                    if (logs.isNotEmpty()) {
                        val result = service.sendLogs(logs)
                        if (result.statusCode == 200) {
                            recognitionLogStore.clear()
                            val now = System.currentTimeMillis() / 1000L
                            _uiState.update {
                                it.copy(lastSyncEpochSeconds = now)
                            }
                            if (_uiState.value.showSettings) {
                                refreshRecentLogs()
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Envio de logs falhou: ${ex.message}")
                }
                delay(logSyncIntervalMillis)
            }
        }

        pickupQueueJob?.cancel()
        pickupQueueJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    pickupQueueMutex.withLock {
                        val events = pickupQueue.pendingBatch()
                        if (events.isNotEmpty()) {
                            val response = service.sendPickupEvents(events)
                            val data = response.decryptedBody?.let { JSONObject(it) }
                            if (response.statusCode == 200 && data?.has("results") == true) {
                                pickupQueue.applyResults(data.getJSONArray("results"))
                            }
                        }
                    }
                    refreshPickupStatus()
                } catch (error: Exception) {
                    Log.w(TAG, "Falha ao sincronizar retiradas: ${error.message}")
                }
                delay(logSyncIntervalMillis)
            }
        }

        pickupSyncJob?.cancel()
        pickupSyncJob = viewModelScope.launch(Dispatchers.IO) {
            var lastAttempt = 0L
            while (true) {
                if (_uiState.value.operationMode == OperationMode.PICKUP) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastAttempt == 0L || now - lastAttempt >= 5 * 60_000L) {
                        lastAttempt = now
                        performPickupSync(service)
                    }
                    refreshPickupStatus()
                }
                delay(5_000L)
            }
        }
    }

    private fun triggerInitialBaseSync() {
        val service = apiService ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.operationMode == OperationMode.PICKUP) performPickupSync(service) else performBaseSync(service)
        }
    }

    private suspend fun handleHeartbeatResponse(service: ApiService, result: ApiService.ApiResult) {
        val json = result.asJson() ?: return
        val actions = json.optJSONArray("actions") ?: return
        for (index in 0 until actions.length()) {
            val action = actions.optString(index)
            if (action.equals("update_base", ignoreCase = true)) {
                if (_uiState.value.operationMode == OperationMode.PICKUP) performPickupSync(service) else performBaseSync(service)
            }
        }
    }

    private suspend fun performPickupSync(service: ApiService) {
        pickupSyncMutex.withLock {
            if (_uiState.value.operationMode != OperationMode.PICKUP || _uiState.value.pickupMatch != null || _uiState.value.pickupBusy) return@withLock
            try {
                val start = SystemClock.elapsedRealtime()
                val response = service.syncPickupDataset()
                val end = SystemClock.elapsedRealtime()
                if (response.statusCode != 200 || response.decryptedBody.isNullOrBlank()) return@withLock
                if (apiService !== service || !_uiState.value.isPaired || _uiState.value.operationMode != OperationMode.PICKUP ||
                    _uiState.value.pickupMatch != null || _uiState.value.pickupBusy) return@withLock
                val json = JSONObject(response.decryptedBody)
                val dimension = json.getInt("embeddingDimension")
                if (dimension > 0 && embeddingModel.isReady() && dimension != embeddingModel.embeddingDimension()) {
                    throw IllegalStateException("Modelo facial incompatível com a base da portaria.")
                }
                val snapshot = pickupSnapshots.save(response.decryptedBody, start, end, bootCount())
                pickupUpdating = true
                try {
                    val result = pickupRepository.applySyncDataset(snapshot.payload, 0L, persist = false)
                    if (!result.success) throw IllegalStateException("Base da portaria inválida.")
                    pickupSnapshot = snapshot
                } finally {
                    pickupUpdating = false
                }
                // Only acknowledge after the roster has been stored and loaded.
                // Failure is harmless: the next sync will retry with a new base.
                try {
                    service.acknowledgePickupDataset(snapshot.snapshotId)
                } catch (error: Exception) {
                    Log.w(TAG, "Não foi possível confirmar a base aplicada: ${error.message}")
                }
                refreshPickupStatus()
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao sincronizar a base da portaria: ${error.message}")
                refreshPickupStatus()
            }
        }
    }

    private suspend fun performBaseSync(service: ApiService) {
        baseSyncMutex.withLock {
            try {
                val response = service.syncEncodings()
                Log.d(TAG, "Iniciando sincronização da base de encodings...")
                Log.d(TAG, "Resposta da sincronização: ${response.statusCode}")
                if (response.statusCode != 200) {
                    Log.w(TAG, "Sincronização de base falhou com status ${response.statusCode}")
                    return@withLock
                }
                val payload = response.decryptedBody ?: response.rawBody
                if (payload.isNullOrBlank()) {
                    Log.w(TAG, "Resposta de sync sem payload utilizável")
                    return@withLock
                }
                val timestamp = System.currentTimeMillis() / 1000L
                val syncResult = encodingRepository.applySyncDataset(payload, timestamp)
                if (!syncResult.success) {
                    Log.w(TAG, "Falha ao aplicar base sincronizada payloadLen=${payload.length}")
                    return@withLock
                }

                val thrDiag = encodingRepository.getThresholdDiagnostics()
                Log.i(
                    TAG,
                    "Base sincronizada OK: people=${syncResult.peopleCount} " +
                        "dim=${syncResult.embeddingDimension} payloadLen=${payload.length} $thrDiag"
                )

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            baseLoaded = encodingRepository.isReady(),
                            baseRosterCount = syncResult.peopleCount,
                            baseEmbeddingDimension = syncResult.embeddingDimension,
                            lastBaseSyncEpochSeconds = syncResult.lastSyncEpochSeconds ?: timestamp,
                            statusMessage = when {
                                syncResult.peopleCount > 0 -> "Base sincronizada (${syncResult.peopleCount})"
                                else -> "Base sincronizada"
                            }
                        )
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Erro durante sync da base: ${ex.message}", ex)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        logSyncJob?.cancel()
        pickupQueueJob?.cancel()
        pickupSyncJob?.cancel()
        recognitionResetJob?.cancel()
        modelInitJob?.cancel()
        successPlayer?.release()
        successPlayer = null
        embeddingModel.close()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val LOW_LIGHT_ENTER_THRESHOLD = 70f
        private const val LOW_LIGHT_EXIT_THRESHOLD = 95f
        private const val RECOGNITION_RESET_DELAY_MILLIS = 1_500L
    }
}

enum class RecognitionStatus {
    Idle,
    Detecting,
    Recognized,
    Error
}

enum class ModelStatus {
    Checking,
    Downloading,
    Initializing,
    Ready,
    Error
}

data class MainUiState(
    val isPaired: Boolean = false,
    val isPairingInProgress: Boolean = false,
    val scannerEnabled: Boolean = true,
    val showSettings: Boolean = false,
    val serverUrl: String = ServerConfig.DEFAULT_SERVER_URL,
    val deviceId: String? = null,
    val accessToken: String? = null,
    val statusMessage: String? = null,
    val pairingError: String? = null,
    val recognitionStatus: RecognitionStatus = RecognitionStatus.Idle,
    val recognitionMessage: String? = null,
    val recognizedPersonId: String? = null,
    val recognizedPersonName: String? = null,
    val recognitionConfidence: Double? = null,
    val lastRecognitionEpochSeconds: Long? = null,
    val baseLoaded: Boolean = false,
    val baseRosterCount: Int = 0,
    val baseEmbeddingDimension: Int? = null,
    val lastBaseSyncEpochSeconds: Long? = null,
    val lastSyncEpochSeconds: Long? = null,
    val lastHeartbeatEpochSeconds: Long? = null,
    val ambientLuminance: Float? = null,
    val isLowLight: Boolean = false,
    val recentLogs: List<RecognitionLogEntry> = emptyList(),
    val modelStatus: ModelStatus = ModelStatus.Checking,
    val modelDownloadProgress: Float = 0f,
    val modelError: String? = null,
    val isNetworkAvailable: Boolean = false,
    val operationMode: OperationMode = OperationMode.STANDARD,
    val pickupMatch: PickupMatch? = null,
    val pickupBusy: Boolean = false,
    val pickupStatus: String? = null,
    val pickupMessage: String? = null,
    val pickupPending: Int = 0,
    val pickupRejected: Int = 0,
    val pickupRejectedEvents: List<PickupRejectedEvent> = emptyList(),
    val pickupAwaiting: Boolean = false
)
