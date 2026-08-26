package com.gdreducacional.totemapp

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.gdreducacional.totemapp.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdreducacional.totemapp.storage.RecognitionLogEntry
import com.gdreducacional.totemapp.ui.theme.AcciosTheme
import com.gdreducacional.totemapp.ui.theme.AcciosColors
import com.gdreducacional.totemapp.views.CameraView
import com.gdreducacional.totemapp.views.FaceCaptureStatus
import com.gdreducacional.totemapp.views.QrScannerView
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
            colors = listOf(AcciosColors.gradientStart, AcciosColors.gradientEnd)
        )
    }

    AnimatedVisibility(
        visible = state.showSettings,
        enter = fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320)),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(240))
    ) {
        SettingsScreen(
            state = state,
            onDismiss = { mainViewModel.setSettingsVisible(false) },
            onRefreshLogs = { mainViewModel.refreshRecentLogs() },
            onUnpair = { mainViewModel.unpair() },
            background = liquidBackground
        )
    }

    if (!state.showSettings) {
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
                } else if (state.modelStatus != ModelStatus.Ready) {
                    ModelStatusOverlay(
                        status = state.modelStatus,
                        progress = state.modelDownloadProgress,
                        error = state.modelError,
                        onRetry = { mainViewModel.retryModelDownload() }
                    )
                } else {
                    FaceRecognitionView(
                        state = state,
                        onRecognitionStatus = mainViewModel::markRecognitionStatus,
                        mainViewModel = mainViewModel
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectivityIndicator(state = state)
                SettingsButton(
                    onToggle = { mainViewModel.toggleSettings() }
                )
            }

            LowLightGlowOverlay(
                isActive = state.isLowLight && permissionState.status.isGranted,
                modifier = Modifier.fillMaxSize()
            )

            if (!state.scannerEnabled && permissionState.status.isGranted && state.modelStatus == ModelStatus.Ready) {
                Text(
                    text = "GDREdu",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 32.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = AcciosColors.textPrimary
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
            .background(AcciosColors.glassElevated.copy(alpha = 0.4f))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (shouldExplain) "Precisamos da câmera para escanear o QRCode." else "Solicitando acesso à câmera...",
            style = MaterialTheme.typography.titleMedium,
            color = AcciosColors.textPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AcciosColors.buttonGlass,
                contentColor = AcciosColors.textPrimary
            )
        ) {
            Text(
                text = "Permitir câmera",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge
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
                .background(AcciosColors.glassElevated, RoundedCornerShape(32.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Escaneie o QRCode",
                style = MaterialTheme.typography.headlineMedium,
                color = AcciosColors.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.statusMessage ?: "Aponte a câmera para o QRCode fornecido",
                style = MaterialTheme.typography.bodyLarge,
                color = AcciosColors.textSecondary
            )
            if (state.isPairingInProgress) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Processando...",
                    color = AcciosColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.pairingError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    color = AcciosColors.errorLight,
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
        color = AcciosColors.buttonGlass,
        shape = CircleShape
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Configurações",
                tint = AcciosColors.textPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ConnectivityIndicator(state: MainUiState) {
    val now = System.currentTimeMillis() / 1000L
    val syncRecent = state.lastSyncEpochSeconds?.let { (now - it) < 40 } == true

    val dotColor = when {
        !state.isNetworkAvailable -> AcciosColors.error
        syncRecent -> AcciosColors.success
        else -> AcciosColors.detecting
    }

    val infiniteTransition = rememberInfiniteTransition(label = "connectivityPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val effectiveAlpha = if (state.isNetworkAvailable && syncRecent) 1f else pulseAlpha

    Surface(
        color = AcciosColors.glassElevated,
        shape = CircleShape
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .padding(14.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = effectiveAlpha))
        )
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onDismiss: () -> Unit,
    onRefreshLogs: () -> Unit,
    onUnpair: () -> Unit,
    background: Brush
) {
    val logsState = rememberLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AcciosColors.surfaceDeep)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Configurações",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AcciosColors.textPrimary
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Fechar",
                        tint = AcciosColors.textPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (!state.statusMessage.isNullOrBlank()) {
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AcciosColors.textTertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    item {
                        Text(
                            text = "Informações",
                            style = MaterialTheme.typography.titleMedium,
                            color = AcciosColors.textPrimary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    item { SettingsInfoSection(state) }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Últimos registros",
                            style = MaterialTheme.typography.titleMedium,
                            color = AcciosColors.textPrimary
                        )
                        Button(
                            onClick = onRefreshLogs,
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AcciosColors.buttonGlass,
                                contentColor = AcciosColors.textPrimary
                            )
                        ) {
                            Text(
                                text = "Atualizar",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = logsState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        if (state.recentLogs.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Rounded.Face,
                                            contentDescription = null,
                                            tint = AcciosColors.textDisabled,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Nenhum registro",
                                            color = AcciosColors.textTertiary,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "Reconhecimentos aparecerão aqui",
                                            color = AcciosColors.textDim,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        } else {
                            items(state.recentLogs) { entry ->
                                LogEntryRow(entry)
                            }
                        }
                    }
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
        if (BuildConfig.DEBUG) {
            SettingsInfoRow("Servidor", serverLabel)
        }
        SettingsInfoRow("Base local", baseStatusLabel)
        SettingsInfoRow("Sync da base", baseSyncLabel)
        SettingsInfoRow("Sync de logs", lastSyncLabel)
        SettingsInfoRow("Último heartbeat", lastHeartbeatLabel)
        if (BuildConfig.DEBUG) {
            SettingsInfoRow("Nível de iluminação", luminanceLabel)
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AcciosColors.glassCard,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AcciosColors.glassCardBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = AcciosColors.textDim
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = AcciosColors.textPrimary
            )
        }
    }
}

@Composable
private fun LogEntryRow(entry: RecognitionLogEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AcciosColors.glassCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AcciosColors.glassCardBorder)
    ) {
        val personLabel = entry.personId.ifBlank { "-" }
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = "ID $personLabel",
                style = MaterialTheme.typography.bodyLarge,
                color = AcciosColors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatIsoTimestamp(entry.timestampIso),
                style = MaterialTheme.typography.bodyMedium,
                color = AcciosColors.textSecondary
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
        color = AcciosColors.glass,
        border = BorderStroke(1.dp, AcciosColors.glassBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentTime,
                color = AcciosColors.textPrimary,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text = formattedDate,
                color = AcciosColors.textSecondary,
                style = MaterialTheme.typography.titleLarge
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
                                AcciosColors.glowWarmFade.copy(alpha = 0f),
                                AcciosColors.glowWarm.copy(alpha = 0.7f)
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
                                AcciosColors.glowWarm.copy(alpha = 0.7f),
                                AcciosColors.glowWarmFade.copy(alpha = 0f)
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
    var accentColor = AcciosColors.textSecondary
    var backgroundColor = AcciosColors.glass
    var icon = Icons.Rounded.Face

    when (status) {
        RecognitionStatus.Recognized -> {
            accentColor = AcciosColors.success
            backgroundColor = AcciosColors.success.copy(alpha = 0.22f)
            primaryText = state.recognizedPersonName?.takeIf { it.isNotBlank() } ?: "Identidade confirmada"
            secondaryText = state.recognitionMessage ?: "Acesso liberado"
            icon = Icons.Rounded.CheckCircle
        }

        RecognitionStatus.Error -> {
            accentColor = AcciosColors.error
            backgroundColor = AcciosColors.error.copy(alpha = 0.22f)
            primaryText = state.recognitionMessage ?: "Acesso negado"
            secondaryText = "Tente novamente ou procure apoio"
            icon = Icons.Rounded.Close
        }

        RecognitionStatus.Detecting -> {
            accentColor = AcciosColors.detecting
            backgroundColor = AcciosColors.glass
            primaryText = state.recognitionMessage ?: "Olhe para a câmera"
            secondaryText = when (state.recognitionMessage) {
                PROMPT_FACE_CLIPPED -> "O rosto precisa aparecer por completo"
                PROMPT_FACE_TOO_SMALL -> "Chegue um pouco mais perto do totem"
                PROMPT_VERIFYING -> "Aguarde um instante"
                else -> "Aguarde um instante"
            }
            icon = Icons.Rounded.Face
        }

        else -> Unit
    }

    val iconScale by animateFloatAsState(
        targetValue = if (status == RecognitionStatus.Recognized) 1.1f else 1f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "iconScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "detectPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val effectiveIconScale = if (status == RecognitionStatus.Detecting) pulseScale else iconScale

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
            border = BorderStroke(1.dp, AcciosColors.glassBorder.copy(alpha = 0.32f))
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
                        .scale(effectiveIconScale)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = when (status) {
                            RecognitionStatus.Recognized -> "Reconhecido"
                            RecognitionStatus.Error -> "Erro de reconhecimento"
                            RecognitionStatus.Detecting -> "Detectando rosto"
                            else -> null
                        },
                        tint = accentColor,
                        modifier = Modifier.size(48.dp)
                    )
                }

                if (primaryText.isNotBlank()) {
                    Text(
                        text = primaryText,
                        color = AcciosColors.textPrimary,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                secondaryText?.let {
                    Text(
                        text = it,
                        color = AcciosColors.textSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (status == RecognitionStatus.Recognized) {
                    state.recognitionConfidence?.let { confidence ->
                        val percent = (confidence * 100).coerceIn(0.0, 100.0)
                        Text(
                            text = "Confiança: ${"%.1f".format(percent)}%",
                            color = AcciosColors.textTertiary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusOverlay(
    status: ModelStatus,
    progress: Float,
    error: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(AcciosColors.glassElevated, RoundedCornerShape(32.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp)
        ) {
            when (status) {
                ModelStatus.Checking -> {
                    CircularProgressIndicator(
                        color = AcciosColors.textPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Verificando modelo...",
                        color = AcciosColors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                ModelStatus.Downloading -> {
                    Text(
                        text = "Baixando modelo",
                        color = AcciosColors.textPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = AcciosColors.textPrimary,
                        trackColor = AcciosColors.divider
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = AcciosColors.textTertiary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ModelStatus.Initializing -> {
                    CircularProgressIndicator(
                        color = AcciosColors.textPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Preparando modelo...",
                        color = AcciosColors.textPrimary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                ModelStatus.Error -> {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Erro ao carregar modelo",
                        tint = AcciosColors.errorLight,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error ?: "Erro desconhecido",
                        color = AcciosColors.errorLight,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AcciosColors.buttonGlass,
                            contentColor = AcciosColors.textPrimary
                        )
                    ) {
                        Text(
                            text = "Tentar novamente",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                ModelStatus.Ready -> {}
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
    var captureStatus by remember { mutableStateOf(FaceCaptureStatus.NONE) }

    LaunchedEffect(state.isPaired) {
        if (state.isPaired) {
            viewModel.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                context = context,
                onCaptureStatus = { status -> captureStatus = status },
                onRecognitionCandidate = { candidate ->
                    mainViewModel.submitRecognitionCandidate(
                        candidate = candidate,
                        isSubjectPresent = { viewModel.hasLivePrimaryFace() }
                    ) { success ->
                        viewModel.onRecognitionProcessed(success)
                    }
                },
                onAmbientLuminance = { luminance ->
                    mainViewModel.updateAmbientLuminance(luminance)
                }
            )
        } else {
            captureStatus = FaceCaptureStatus.NONE
            mainViewModel.updateAmbientLuminance(LOW_LIGHT_RESET_VALUE)
            viewModel.unbindCamera(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindCamera(context)
        }
    }

    LaunchedEffect(captureStatus, state.recognitionStatus, state.recognitionMessage) {
        val atTotem = captureStatus == FaceCaptureStatus.READY || captureStatus == FaceCaptureStatus.CLIPPED
        if (!atTotem) {
            delay(PERSON_EXIT_RESET_DELAY_MILLIS)
            val stillAway = captureStatus != FaceCaptureStatus.READY &&
                captureStatus != FaceCaptureStatus.CLIPPED
            if (stillAway) {
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

        if (state.recognitionMessage == PROMPT_VERIFYING) {
            return@LaunchedEffect
        }

        if (captureStatus == FaceCaptureStatus.CLIPPED) {
            delay(CLIPPED_PROMPT_DELAY_MILLIS)
            if (captureStatus == FaceCaptureStatus.CLIPPED &&
                state.recognitionMessage != PROMPT_VERIFYING &&
                state.recognitionStatus != RecognitionStatus.Recognized &&
                state.recognitionStatus != RecognitionStatus.Error &&
                state.recognitionMessage != PROMPT_FACE_CLIPPED
            ) {
                onRecognitionStatus(RecognitionStatus.Detecting, PROMPT_FACE_CLIPPED)
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private const val LOW_LIGHT_RESET_VALUE = 120f
private const val PERSON_EXIT_RESET_DELAY_MILLIS = 500L
private const val CLIPPED_PROMPT_DELAY_MILLIS = 350L
private const val PROMPT_FACE_CLIPPED = "Mostre o rosto inteiro"
private const val PROMPT_FACE_TOO_SMALL = "Aproxime-se"
private const val PROMPT_VERIFYING = "Verificando identidade..."
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
