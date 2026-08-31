package com.gdreducacional.totemapp

import android.app.Application
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pairingService = PairingService(application, ServerConfig.DEFAULT_SERVER_URL)
    private val recognitionLogStore = RecognitionLogStore(application)
    private val encodingRepository = EncodingRepository(application)
    private val embeddingModel = FaceEmbeddingModel(application)
    private val recognitionEngine = RecognitionEngine(embeddingModel, encodingRepository)
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
    private var recognitionResetJob: Job? = null
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
                isNetworkAvailable = initialNetworkAvailable
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

    fun unpair() {
        viewModelScope.launch(Dispatchers.IO) {
            pairingService.unpair()
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
        if (candidate.frames.isEmpty()) {
            Log.w(TAG, "submitRecognition: frames vazio trackId=${candidate.trackId}")
            onRecognitionError("Falha ao processar imagem", onFinished)
            return
        }

        if (!encodingRepository.isReady()) {
            Log.w(
                TAG,
                "submitRecognition: base indisponível people=${encodingRepository.getPeopleCount()} " +
                    "modelReady=${embeddingModel.isReady()}"
            )
            candidate.frames.forEach { it.recycleSafely() }
            onRecognitionError("Base local indisponível", onFinished)
            return
        }

        Log.i(
            TAG,
            "submitRecognition: trackId=${candidate.trackId} frames=${candidate.frames.size} " +
                "people=${encodingRepository.getPeopleCount()} " +
                "modelReady=${embeddingModel.isReady()} " +
                "frame0=${candidate.frames.firstOrNull()?.let { "${it.width}x${it.height}" }}"
        )

        if (candidate.commitQuality) {
            markRecognitionStatus(RecognitionStatus.Detecting, "Verificando identidade...")
        }

        viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                recognitionEngine.recognize(
                    RecognitionEngine.RecognitionBatch(candidate.frames, candidate.trackId)
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Erro no reconhecimento: ${ex.message}", ex)
                null
            } finally {
                candidate.frames.forEach { it.recycleSafely() }
            }

            withContext(Dispatchers.Main) {
                if (result != null) {
                    Log.i(
                        TAG,
                        "Recognition SUCCESS id=${result.personId} name=${result.displayName} " +
                            "conf=${"%.4f".format(result.confidence)} dist=${"%.4f".format(result.distance)}"
                    )
                    registerRecognitionSuccess(result.personId, result.displayName, result.entityType, result.confidence.toDouble())
                    onFinished(true)
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
    }

    private fun triggerInitialBaseSync() {
        val service = apiService ?: return
        viewModelScope.launch(Dispatchers.IO) {
            performBaseSync(service)
        }
    }

    private suspend fun handleHeartbeatResponse(service: ApiService, result: ApiService.ApiResult) {
        val json = result.asJson() ?: return
        val actions = json.optJSONArray("actions") ?: return
        for (index in 0 until actions.length()) {
            val action = actions.optString(index)
            if (action.equals("update_base", ignoreCase = true)) {
                performBaseSync(service)
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
    val isNetworkAvailable: Boolean = false
)
