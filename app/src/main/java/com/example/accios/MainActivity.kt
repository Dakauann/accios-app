package com.example.accios

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.accios.ui.theme.AcciosTheme
import com.example.accios.views.CameraView
import com.example.accios.views.QrScannerView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.delay
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed")
        } else {
            Log.d(TAG, "OpenCV initialization successful")
        }

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

    LaunchedEffect(Unit) {
        if (!permissionState.status.isGranted) {
            permissionState.launchPermissionRequest()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(liquidBackground)
            .padding(16.dp)
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

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            SettingsButton(
                onToggle = { mainViewModel.toggleSettings() }
            )

            SettingsDropdown(
                isVisible = state.showSettings,
                onDismiss = { mainViewModel.setSettingsVisible(false) },
                state = state
            )
        }

        StatusPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight(),
            state = state
        )
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
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Configurações",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun SettingsDropdown(isVisible: Boolean, onDismiss: () -> Unit, state: MainUiState) {
    var expanded by remember { mutableStateOf(isVisible) }

    LaunchedEffect(isVisible) {
        expanded = isVisible
    }

    val tabletStatus = if (state.isPaired) "Pareado" else "Não pareado"
    val deviceIdLabel = state.deviceId ?: "-"
    val serverLabel = state.serverUrl
    val lastSyncLabel = state.lastSyncEpochSeconds?.let { formatTimestamp(it) } ?: "-"
    val baseStatusLabel = if (state.baseLoaded) "Carregada (${state.baseRosterCount})" else "Não carregada"
    val baseSyncLabel = state.lastBaseSyncEpochSeconds?.let { formatTimestamp(it) } ?: "-"
    val baseDimLabel = state.baseEmbeddingDimension?.toString() ?: "-"
    val lastHeartbeatLabel = state.lastHeartbeatEpochSeconds?.let { formatTimestamp(it) } ?: "-"

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            expanded = false
            onDismiss()
        }
    ) {
        DropdownMenuItem(
            text = { Text("Status do tablet: $tabletStatus") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Device ID: $deviceIdLabel") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Servidor: $serverLabel") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Base local: $baseStatusLabel") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Sync da base: $baseSyncLabel") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Dimensão embedding: $baseDimLabel") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Sync de logs: $lastSyncLabel") },
            onClick = { expanded = false; onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Último heartbeat: $lastHeartbeatLabel") },
            onClick = { expanded = false; onDismiss() }
        )
    }
}

@Composable
private fun StatusPanel(modifier: Modifier, state: MainUiState) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = state.statusMessage ?: if (state.isPaired) "Pronto para reconhecimento" else "Aguardando pareamento",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                state.recognitionMessage?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = when (state.recognitionStatus) {
                    RecognitionStatus.Recognized -> Color(0xFF81C784)
                    RecognitionStatus.Error -> Color(0xFFFF8A80)
                    RecognitionStatus.Detecting -> Color(0xFFFFF59D)
                    else -> Color(0xFFB39DDB)
                }
            ) {
                Text(
                    text = when (state.recognitionStatus) {
                        RecognitionStatus.Recognized -> "Reconhecido"
                        RecognitionStatus.Detecting -> "Detectando"
                        RecognitionStatus.Error -> "Erro"
                        else -> "Standby"
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.Black,
                    style = MaterialTheme.typography.bodyMedium
                )
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
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val previewView = remember { PreviewView(context) }
    var frameBounds by remember { mutableStateOf(IntSize.Zero) }
    var alertHeight by remember { mutableStateOf(0.dp) }
    var detectionActive by remember { mutableStateOf(false) }

    LaunchedEffect(state.isPaired) {
        if (state.isPaired) {
            viewModel.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                context = context,
                onFacesDetected = { faces -> detectionActive = faces.isNotEmpty() },
                onRecognitionCandidate = { bitmap ->
                    mainViewModel.submitRecognitionCandidate(bitmap) { success ->
                        viewModel.onRecognitionProcessed(success)
                    }
                }
            )
        } else {
            viewModel.unbindCamera(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindCamera(context)
        }
    }

    LaunchedEffect(detectionActive, state.recognitionStatus) {
        if (state.recognitionStatus == RecognitionStatus.Recognized || state.recognitionStatus == RecognitionStatus.Error) return@LaunchedEffect
        if (detectionActive) {
            onRecognitionStatus(RecognitionStatus.Detecting, "Detectando rosto")
        } else {
            onRecognitionStatus(RecognitionStatus.Idle, null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(32.dp))
                .blur(2.dp)
        )

        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.7f)
                .aspectRatio(0.75f)
                .onGloballyPositioned { frameBounds = it.size }
        ) {
            val gradient = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.05f)),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
            drawRoundRect(
                brush = gradient,
                size = Size(size.width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(60f, 60f),
                style = Stroke(width = 8f)
            )
        }

        if (frameBounds != IntSize.Zero) {
            val frameHeightDp = with(density) { frameBounds.height.toDp() }
            val screenHeight = configuration.screenHeightDp.dp
            val desiredOffset = frameHeightDp / 2 + 24.dp
            val maxOffset = screenHeight / 2 - alertHeight / 2 - 24.dp
            val alertOffset = if (desiredOffset > maxOffset) maxOffset else desiredOffset

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -frameHeightDp / 2 - 48.dp)
            ) {
                ClockPill()
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = alertOffset)
                    .onGloballyPositioned { alertHeight = with(density) { it.size.height.toDp() } }
            ) {
                AlertContainer(detectionActive)
            }
        }

        viewModel.detectedFaces.forEach { rect ->
            val widthDp = with(density) { rect.width().toDp() }
            val heightDp = with(density) { rect.height().toDp() }
            Surface(
                modifier = Modifier
                    .offset { IntOffset(rect.left, rect.top) }
                    .size(widthDp, heightDp)
                    .zIndex(1f),
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(20.dp)
            ) {}
        }
    }
}

@Composable
private fun ClockPill() {
    var currentTime by remember { mutableStateOf("--:--") }
    var formattedDate by remember { mutableStateOf("--") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            formattedDate = SimpleDateFormat("EEEE, dd 'de' MMMM", Locale("pt", "BR"))
                .format(now)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
            delay(60_000)
        }
    }

    Surface(
        shape = RoundedCornerShape(40.dp),
        color = Color.White.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(text = currentTime, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(text = formattedDate, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AlertContainer(isFaceDetected: Boolean) {
    val background = if (isFaceDetected) Color(0xFF2E7D32).copy(alpha = 0.9f) else Color(0xFFEF5350).copy(alpha = 0.9f)
    val message = if (isFaceDetected) "Rosto detectado" else "Posicione seu rosto em frente à câmera"
    Surface(
        color = background,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 4.dp
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

private fun formatTimestamp(epochSeconds: Long): String {
    return try {
        val date = Date(epochSeconds * 1000)
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(date)
    } catch (_: Exception) {
        "-"
    }
}