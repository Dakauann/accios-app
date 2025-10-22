package com.example.accios

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.accios.views.CameraView
import com.example.accios.ui.theme.AcciosTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

fun startServer() {
    try {
        val server = ServerSocket(7719).apply { reuseAddress = true }
        Log.d("Server", "Server listening on port 7719 for route /api/v1/status...")

        try {
            while (true) {
                val client: Socket = server.accept()
                Log.d("Server", "New connection from ${client.inetAddress.hostAddress}")

                thread {
                    try {
                        val input = client.getInputStream().bufferedReader()
                        val request = input.readLine()
                        Log.d("Server", "Received API call: $request")

                        val targetRoute = "/api/v1/status"
                        val response = when {
                            request == null -> "No data received"
                            request.trim() == targetRoute -> "Success: MAC Address ${getMacAddress()}"
                            request.contains("GET /info") -> "Response: Server info"
                            request.contains("GET /mac") -> "Response: MAC Address ${getMacAddress()}"
                            else -> "Unknown request: $request"
                        }

                        val output = client.getOutputStream().bufferedWriter()
                        output.write("$response\n")
                        output.flush()

                        client.close()
                        Log.d("Server", "Client connection closed")
                    } catch (e: Exception) {
                        Log.e("Server", "Error handling client: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Server", "Server error: ${e.message}")
        } finally {
            server.close()
            Log.d("Server", "Server closed")
        }
    } catch (e: Exception) {
        Log.e("Server", "Socket error: ${e.message}")
    }
}

private fun getMacAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            val mac = ni.hardwareAddress
            if (mac != null && ni.name.startsWith("wlan")) {
                return mac.joinToString(":") { String.format("%02X", it) }
            }
        }
    } catch (e: Exception) {
        Log.e("Server", "Error getting MAC: ${e.message}")
    }
    return "Unknown"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            android.util.Log.e("MainActivity", "OpenCV initialization failed!")
        } else {
            android.util.Log.d("MainActivity", "OpenCV initialization successful!")
        }
        setContent {
            AcciosTheme {
                MainScreenWithDrawer()
            }
        }

        thread {
            startServer()
        }
    }
}

@Composable
fun MainScreenWithDrawer() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(240.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Menu",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Home") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() } }
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            CameraPreviewScreen(modifier = Modifier.fillMaxSize())

            TopNavbar(
                onMenuClick = {
                    scope.launch { drawerState.open() }
                }
            )
        }
    }
}

@Composable
fun TopNavbar(onMenuClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                color = Color.Black.copy(alpha = 0.4f)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.width(48.dp))

            Text(
                text = "Accio Edu",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )

            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun InformationsContainer() {
    var currentTime by remember { mutableStateOf("") }
    var formattedDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val currentDate = Date()
            val monthFormat = SimpleDateFormat("MMMM", Locale("pt", "BR"))
            val dayFormat = SimpleDateFormat("dd 'de'", Locale("pt", "BR"))
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            val monthName = monthFormat.format(currentDate).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
            formattedDate = "${dayFormat.format(currentDate)} $monthName"
            currentTime = timeFormat.format(currentDate)

            delay(1000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = currentTime,
            color = Color.White,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp)
        )
        Text(text = formattedDate, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun AlertContainer(isFaceDetected: Boolean = false) {
    val bgColor = if (isFaceDetected) Color(0xFF2E7D32).copy(alpha = 0.95f) else Color.Red.copy(alpha = 0.9f)
    val text = if (isFaceDetected) "Rosto detectado" else "Posicione seu rosto a frente da camera"
    Box(
        modifier = Modifier
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .fillMaxWidth(0.8f)
            .background(
                color = bgColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPreviewScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraView = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var hasCameraPermission by remember { mutableStateOf(false) }
    val previewView = remember { androidx.camera.view.PreviewView(context) }
    var faceShapeSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    var alertHeightDp by remember { mutableStateOf(0.dp) }

    LaunchedEffect(permissionState.status.isGranted) {
        hasCameraPermission = permissionState.status.isGranted
        if (hasCameraPermission) {
            viewModel.bindCamera(lifecycleOwner, previewView, context)
        } else {
            viewModel.unbindCamera(context)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasCameraPermission) {
            when {
                permissionState.status.shouldShowRationale -> {
                    Text("Camera permission is needed for preview.")
                }
                else -> {
                    LaunchedEffect(Unit) {
                        permissionState.launchPermissionRequest()
                    }
                    Text("Requesting camera permission...")
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.8f)
                        .aspectRatio(0.75f)
                        .onGloballyPositioned { coordinates ->
                            faceShapeSize = coordinates.size
                        }
                ) {
                    val width = size.width
                    val height = size.height
                    val strokePx = 6f
                    val hasFace = viewModel.detectedFaces.isNotEmpty()

                    // fill change when face is present (slightly more visible)
                    val fillColor = if (hasFace) Color(0xFF1976D2).copy(alpha = 0.08f) else Color.Green.copy(alpha = 0.06f)
                    drawOval(
                        color = fillColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                        size = Size(width, height)
                    )

                    // stroke gradient changes when face detected
                    val strokeBrush = if (hasFace) Brush.linearGradient(listOf(Color(0xFF64B5F6), Color(0xFF1976D2)))
                    else Brush.linearGradient(listOf(Color(0xFF66BB6A), Color(0xFF2E7D32)))

                    drawOval(
                        brush = strokeBrush,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                        size = Size(width, height),
                        style = Stroke(width = strokePx)
                    )
                }

                // Render InformationsContainer and AlertContainer after the Canvas so they always appear on top
                if (faceShapeSize != IntSize.Zero) {
                    val faceHeightDp = with(density) { faceShapeSize.height.toDp() }
                    val configuration = LocalConfiguration.current
                    val screenHeightDp = configuration.screenHeightDp.dp

                    // desired vertical offset for the alert below the face
                    val desiredAlertOffset = faceHeightDp / 2 + 30.dp

                    // clamp the alert offset so it stays visible above bottom system bars and above the bottom edge
                    val maxAllowedOffset = screenHeightDp / 2 - alertHeightDp / 2 - 35.dp
                    val alertOffset = if (desiredAlertOffset > maxAllowedOffset) maxAllowedOffset else desiredAlertOffset

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = -faceHeightDp / 2 - 56.dp)
                            .zIndex(2f)
                    ) {
                        InformationsContainer()
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = alertOffset)
                            .onGloballyPositioned { coords ->
                                alertHeightDp = with(density) { coords.size.height.toDp() }
                            }
                            .zIndex(2f)
                    ) {
                        // Passa estado de detecção para o container de alerta
                        AlertContainer(isFaceDetected = viewModel.detectedFaces.isNotEmpty())
                    }
                }

                // Draw detected faces (soft highlight)
                viewModel.detectedFaces.forEach { face ->
                    Surface(
                        modifier = Modifier
                            .offset { IntOffset(face.left, face.top) }
                            .size(face.width().dp, face.height().dp)
                            .zIndex(1f),
                        color = Color.Green.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(12.dp)
                    ) {}
                }
            }
        }
    }
}