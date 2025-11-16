package com.accioeducacional.totemapp

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accioeducacional.totemapp.storage.RecognitionLogEntry
import com.accioeducacional.totemapp.ui.theme.AcciosTheme
import com.accioeducacional.totemapp.views.CameraView
import com.accioeducacional.totemapp.views.QrScannerView
import com.accioeducacional.totemapp.services.UpdatesService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(TAG, "Inicializando UpdatesService via onCreate")
        UpdatesService.start(this)

        setContent {
            AcciosTheme {
                val mainViewModel: MainViewModel = viewModel()
                SmartPresenceScreen(mainViewModel)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SmartPresenceScreen(mainViewModel: MainViewModel) {
    val state by mainViewModel.uiState.collectAsState()
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(state.isLowLight) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val params = activity.window.attributes
        params.screenBrightness = if (state.isLowLight) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
    }

    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity ?: return@onDispose
            val params = activity.window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = params
        }
    }

    val liquidBackground = remember {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1F1C2C),
                Color(0xFF928DAB)
            )
        )
    }

    if (state.showSettings) {
        SettingsScreen(
            state = state,
            onDismiss = { mainViewModel.setSettingsVisible(false) },
            onRefreshLogs = { mainViewModel.refreshRecentLogs() },
            background = liquidBackground
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(liquidBackground)
        ) {
            if (!permissionState.status.isGranted) {
                CameraPermissionInfo(permissionState.status.shouldShowRationale) {
                    permissionState.launchPermissionRequest()
                }
            } else {
                if (state.scannerEnabled) {
                    PairingScannerSection(
                        state = state,
                        onQrDetected = mainViewModel::onQrDetected,
                        onScannerError = { mainViewModel.markRecognitionStatus(RecognitionStatus.Error, it) }
                    )
                } else {
                    FaceRecognitionView(
                        state = state,
                        onRecognitionStatus = mainViewModel::markRecognitionStatus,
                        mainViewModel = mainViewModel
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 28.dp)
            ) {
                SettingsButton(
                    onToggle = { mainViewModel.toggleSettings() }
                )
            }

            LowLightGlowOverlay(
                isActive = state.isLowLight && permissionState.status.isGranted,
                modifier = Modifier.fillMaxSize()
            )

            if (!state.scannerEnabled && permissionState.status.isGranted) {
                Text(
                    text = "Accio Edu",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 32.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                GlassDateTime(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 88.dp)
                )

                RecognitionGlass(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp),
                    state = state
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionInfo(shouldExplain: Boolean, onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (shouldExplain) "Precisamos da câmera para escanear o QRCode." else "Solicitando acesso à câmera...",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRequestPermission,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Permitir câmera",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun PairingScannerSection(
    state: MainUiState,
    onQrDetected: (String) -> Unit,
    onScannerError: (String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        QrScannerView(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(32.dp))
                .blur(6.dp),
            isEnabled = !state.isPairingInProgress,
            onQrDetected = onQrDetected,
            onScannerError = onScannerError
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(32.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Escaneie o QRCode",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.statusMessage ?: "Aponte a câmera para o QRCode fornecido",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f)
            )
            if (state.isPairingInProgress) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Processando...",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.pairingError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SettingsButton(modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.15f),
        shape = CircleShape
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Configurações",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onDismiss: () -> Unit,
    onRefreshLogs: () -> Unit,
    background: Brush
) {
    val logsState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 36.dp),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF1B1A29).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Configurações",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.statusMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fechar",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                SettingsInfoSection(state)

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Últimos registros",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = logsState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    if (state.recentLogs.isEmpty()) {
                        item {
                            Text(
                                text = "Nenhum log disponível",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        items(state.recentLogs) { entry ->
                            LogEntryRow(entry)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRefreshLogs,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White
                    )
                ) {
                    Text("Atualizar registros")
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoSection(state: MainUiState) {
    val tabletStatus = if (state.isPaired) "Pareado" else "Não pareado"
    val baseStatusLabel = if (state.baseLoaded) "Carregada (${state.baseRosterCount})" else "Não carregada"
    val deviceIdLabel = state.deviceId ?: "-"
    val serverLabel = state.serverUrl
    val lastSyncLabel = state.lastSyncEpochSeconds?.let { formatTimestamp(it) } ?: "-"
    val baseSyncLabel = state.lastBaseSyncEpochSeconds?.let { formatTimestamp(it) } ?: "-"
    val baseDimLabel = state.baseEmbeddingDimension?.toString() ?: "-"
    val lastHeartbeatLabel = state.lastHeartbeatEpochSeconds?.let { formatTimestamp(it) } ?: "-"
    val luminanceLabel = state.ambientLuminance?.let { "${"%.0f".format(it)} / 255" } ?: "-"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsInfoRow("Status do tablet", tabletStatus)
        SettingsInfoRow("Device ID", deviceIdLabel)
        SettingsInfoRow("Servidor", serverLabel)
        SettingsInfoRow("Base local", baseStatusLabel)
        SettingsInfoRow("Sync da base", baseSyncLabel)
        SettingsInfoRow("Sync de logs", lastSyncLabel)
        SettingsInfoRow("Último heartbeat", lastHeartbeatLabel)
        SettingsInfoRow("Nível de iluminação", luminanceLabel)
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LogEntryRow(entry: RecognitionLogEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        val personLabel = entry.personId.ifBlank { "-" }
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = "ID $personLabel",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatIsoTimestamp(entry.timestampIso),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun GlassDateTime(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf("--:--:--") }
    var formattedDate by remember { mutableStateOf("--") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
            formattedDate = SimpleDateFormat("EEEE, dd 'de' MMMM", PT_BR_LOCALE)
                .format(now)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(PT_BR_LOCALE) else it.toString() }
            delay(1_000)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = Color.White.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentTime,
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = formattedDate,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LowLightGlowOverlay(isActive: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(animationSpec = tween(260)),
        exit = fadeOut(animationSpec = tween(220)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(140.dp)
                    .align(Alignment.CenterStart)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFFF8E1).copy(alpha = 0f),
                                Color(0xFFFFECB3).copy(alpha = 0.7f)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(140.dp)
                    .align(Alignment.CenterEnd)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFFECB3).copy(alpha = 0.7f),
                                Color(0xFFFFF8E1).copy(alpha = 0f)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun RecognitionGlass(modifier: Modifier = Modifier, state: MainUiState) {
    val status = state.recognitionStatus
    val isVisible = status != RecognitionStatus.Idle

    var primaryText = ""
    var secondaryText: String? = null
    var accentColor = Color.White.copy(alpha = 0.8f)
    var backgroundColor = Color.White.copy(alpha = 0.18f)
    var icon = Icons.Rounded.Face

    when (status) {
        RecognitionStatus.Recognized -> {
            accentColor = Color(0xFF43A047)
            backgroundColor = accentColor.copy(alpha = 0.22f)
            primaryText = state.recognizedPersonName?.takeIf { it.isNotBlank() } ?: "Identidade confirmada"
            secondaryText = state.recognitionMessage ?: "Acesso liberado"
            icon = Icons.Rounded.CheckCircle
        }

        RecognitionStatus.Error -> {
            accentColor = Color(0xFFE53935)
            backgroundColor = accentColor.copy(alpha = 0.22f)
            primaryText = state.recognitionMessage ?: "Acesso negado"
            secondaryText = "Tente novamente ou procure apoio"
            icon = Icons.Rounded.Close
        }

        RecognitionStatus.Detecting -> {
            accentColor = Color(0xFF42A5F5)
            backgroundColor = Color.White.copy(alpha = 0.18f)
            primaryText = state.recognitionMessage ?: "Centralize o rosto"
            secondaryText = "Aguarde alguns instantes para calibrar"
            icon = Icons.Rounded.Face
        }

        else -> Unit
    }

    val iconScale by animateFloatAsState(
        targetValue = if (status == RecognitionStatus.Recognized) 1.1f else 1f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "iconScale"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(320)),
        exit = fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(40.dp),
            color = backgroundColor,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.32f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(iconScale)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(48.dp)
                    )
                }

                if (primaryText.isNotBlank()) {
                    Text(
                        text = primaryText,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                secondaryText?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (status == RecognitionStatus.Recognized) {
                    state.recognitionConfidence?.let { confidence ->
                        val percent = (confidence * 100).coerceIn(0.0, 100.0)
                        Text(
                            text = "Confiança: ${"%.1f".format(percent)}%",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceRecognitionView(
    state: MainUiState,
    onRecognitionStatus: (RecognitionStatus, String?) -> Unit,
    mainViewModel: MainViewModel,
    viewModel: CameraView = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var detectionActive by remember { mutableStateOf(false) }

    LaunchedEffect(state.isPaired) {
        if (state.isPaired) {
            viewModel.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                context = context,
                onFacesDetected = { faces -> detectionActive = faces.any { it.isFrontFacing } },
                onRecognitionCandidate = { candidate ->
                    mainViewModel.submitRecognitionCandidate(candidate) { success ->
                        viewModel.onRecognitionProcessed(success)
                    }
                },
                onAmbientLuminance = { luminance ->
                    mainViewModel.updateAmbientLuminance(luminance)
                }
            )
        } else {
            mainViewModel.updateAmbientLuminance(LOW_LIGHT_RESET_VALUE)
            viewModel.unbindCamera(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindCamera(context)
        }
    }

    LaunchedEffect(detectionActive, state.recognitionStatus, state.recognitionMessage) {
        if (!detectionActive) {
            delay(PERSON_EXIT_RESET_DELAY_MILLIS)
            if (!detectionActive) {
                when (state.recognitionStatus) {
                    RecognitionStatus.Recognized, RecognitionStatus.Error -> {
                        mainViewModel.resetRecognitionState()
                    }
                    else -> onRecognitionStatus(RecognitionStatus.Idle, null)
                }
            }
            return@LaunchedEffect
        }

        if (state.recognitionStatus == RecognitionStatus.Recognized || state.recognitionStatus == RecognitionStatus.Error) {
            return@LaunchedEffect
        }

        val shouldUpdatePrompt = state.recognitionStatus != RecognitionStatus.Detecting ||
            state.recognitionMessage.isNullOrBlank() ||
            state.recognitionMessage == "Centralize o rosto"

        if (shouldUpdatePrompt) {
            onRecognitionStatus(RecognitionStatus.Detecting, "Centralize o rosto")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private const val LOW_LIGHT_RESET_VALUE = 120f
private const val PERSON_EXIT_RESET_DELAY_MILLIS = 500L
private val PT_BR_LOCALE = Locale.forLanguageTag("pt-BR")

private fun formatTimestamp(epochSeconds: Long): String {
    return try {
        val date = Date(epochSeconds * 1000)
    SimpleDateFormat("dd/MM/yyyy HH:mm", PT_BR_LOCALE).format(date)
    } catch (_: Exception) {
        "-"
    }
}

private fun formatIsoTimestamp(value: String): String {
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", PT_BR_LOCALE)
        val date = parser.parse(value)
        if (date != null) formatter.format(date) else "-"
    }.getOrElse { "-" }
}
