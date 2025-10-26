package com.example.accios

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.accios.RecognitionStatus
import com.example.accios.device.DeviceInfoProvider
import com.example.accios.data.EncodingRepository
import com.example.accios.services.ApiService
import com.example.accios.services.PairingService
import com.example.accios.services.FaceEmbeddingModel
import com.example.accios.services.RecognitionEngine
import com.example.accios.storage.RecognitionLogStore
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pairingService = PairingService(application, BuildConfig.DEFAULT_SERVER_URL)
    private val recognitionLogStore = RecognitionLogStore(application)
    private val encodingRepository = EncodingRepository(application)
    private val embeddingModel = FaceEmbeddingModel(application)
    private val recognitionEngine = RecognitionEngine(embeddingModel, encodingRepository)
    private val baseSyncMutex = Mutex()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var apiService: ApiService? = null
    private var heartbeatJob: Job? = null
    private var logSyncJob: Job? = null
    private val heartbeatIntervalMillis = 60_000L
    private val logSyncIntervalMillis = 120_000L

    init {
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
                serverUrl = serverUrl
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val meta = encodingRepository.loadFromDisk()
            if (meta != null) {
                _uiState.update { state ->
                    state.copy(
                        baseLoaded = encodingRepository.isReady(),
                        baseRosterCount = meta.peopleCount,
                        baseEmbeddingDimension = meta.embeddingDimension,
                        lastBaseSyncEpochSeconds = meta.lastSyncEpochSeconds
                            ?: state.lastBaseSyncEpochSeconds
                    )
                }
            }
        }

        if (paired && !token.isNullOrBlank()) {
            rebuildApiService(serverUrl, token)
        }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun setSettingsVisible(visible: Boolean) {
        _uiState.update { it.copy(showSettings = visible) }
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

    fun submitRecognitionCandidate(bitmap: Bitmap, onFinished: (Boolean) -> Unit) {
        val faceCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycleSafely()

        if (faceCopy == null) {
            onRecognitionError("Falha ao processar imagem", onFinished)
            return
        }

        if (!encodingRepository.isReady()) {
            faceCopy.recycleSafely()
            onRecognitionError("Base local indisponível", onFinished)
            return
        }

        markRecognitionStatus(RecognitionStatus.Detecting, "Processando reconhecimento...")

        viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                recognitionEngine.recognize(faceCopy)
            } catch (ex: Exception) {
                Log.e(TAG, "Erro no reconhecimento: ${ex.message}", ex)
                null
            }

            withContext(Dispatchers.Main) {
                faceCopy.recycleSafely()
                if (result != null) {
                    registerRecognitionSuccess(result.personId, result.displayName, result.confidence.toDouble())
                    onFinished(true)
                } else {
                    onRecognitionFailed("Não reconhecido")
                    onFinished(false)
                }
            }
        }
    }

    private fun registerRecognitionSuccess(personId: String?, personName: String?, confidence: Double) {
        val timestampSeconds = System.currentTimeMillis() / 1000L
        if (!personId.isNullOrBlank()) {
            recognitionLogStore.append(personId, timestampSeconds)
        }

        _uiState.update {
            it.copy(
                recognitionStatus = RecognitionStatus.Recognized,
                recognitionMessage = when {
                    !personName.isNullOrBlank() -> "Bem-vindo(a), $personName"
                    else -> "Identificação confirmada"
                },
                recognizedPersonId = personId,
                recognizedPersonName = personName,
                recognitionConfidence = if (confidence.isNaN()) null else confidence,
                statusMessage = "Reconhecimento realizado",
                lastRecognitionEpochSeconds = timestampSeconds
            )
        }

        viewModelScope.launch {
            delay(5_000)
            _uiState.update {
                it.copy(
                    recognitionStatus = RecognitionStatus.Idle,
                    recognitionMessage = null
                )
            }
        }
    }

    private fun onRecognitionFailed(message: String) {
        _uiState.update {
            it.copy(
                recognitionStatus = RecognitionStatus.Error,
                recognitionMessage = message,
                statusMessage = "Reconhecimento não confirmado"
            )
        }

        viewModelScope.launch {
            delay(4_000)
            _uiState.update {
                it.copy(
                    recognitionStatus = RecognitionStatus.Idle,
                    recognitionMessage = null
                )
            }
        }
    }

    private fun onRecognitionError(message: String, onFinished: (Boolean) -> Unit) {
        onRecognitionFailed(message)
        onFinished(false)
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
                if (response.statusCode != 200) {
                    Log.w(TAG, "Sincronização de base falhou com status ${response.statusCode}")
                    return@withLock
                }
                val payload = response.rawBytes ?: response.decryptedBody?.let { decodeBase64(it) }
                if (payload == null || payload.isEmpty()) {
                    Log.w(TAG, "Resposta de sync sem payload utilizável")
                    return@withLock
                }
                val timestamp = System.currentTimeMillis() / 1000L
                val syncResult = encodingRepository.applySyncPayload(payload, timestamp)
                if (!syncResult.success) {
                    Log.w(TAG, "Falha ao aplicar base sincronizada")
                    return@withLock
                }

                if (syncResult.modelUpdated) {
                    try {
                        embeddingModel.reloadInterpreter()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Erro ao recarregar modelo de embedding: ${ex.message}", ex)
                    }
                }

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

    private fun decodeBase64(value: String): ByteArray? {
        return try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        logSyncJob?.cancel()
        embeddingModel.close()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}

enum class RecognitionStatus {
    Idle,
    Detecting,
    Recognized,
    Error
}

data class MainUiState(
    val isPaired: Boolean = false,
    val isPairingInProgress: Boolean = false,
    val scannerEnabled: Boolean = true,
    val showSettings: Boolean = false,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
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
    val lastHeartbeatEpochSeconds: Long? = null
)
