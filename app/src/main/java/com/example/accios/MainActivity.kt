package com.example.accios

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
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
            .background(
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = currentTime,
            color = Color.White,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp)
        )
        Text(text = formattedDate, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun AlertContainer() {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .background(
                color = Color.Red.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = "Posicione seu rosto a frente da camera",
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

                if (faceShapeSize != IntSize.Zero) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = with(density) { (faceShapeSize.height / 2 - 1300).toDp() })
                    ) {
                        InformationsContainer()
                    }
                }

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

                    drawRoundRect(
                        color = Color.Green,
                        size = Size(width, height),
                        cornerRadius = CornerRadius(
                            x = 60f,
                            y = 60f
                        ),
                        style = Stroke(
                            width = 8f,
                        )
                    )

                    val path = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f,
                                top = 0f,
                                right = width,
                                bottom = height,
                                topLeftCornerRadius = CornerRadius(width * 0.3f, width * 0.2f),
                                topRightCornerRadius = CornerRadius(width * 0.3f, width * 0.2f),
                                bottomLeftCornerRadius = CornerRadius(width * 0.35f, width * 0.4f),
                                bottomRightCornerRadius = CornerRadius(width * 0.35f, width * 0.4f)
                            )
                        )
                    }

                    drawPath(
                        path = path,
                        color = Color.Green,
                        style = Stroke(width = 8f)
                    )
                }

                if (faceShapeSize != IntSize.Zero) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = with(density) { (faceShapeSize.height / 2 + 100).toDp() })
                    ) {
                        AlertContainer()
                    }
                }

                // Draw detected faces
                viewModel.detectedFaces.forEach { face ->
                    Surface(
                        modifier = Modifier
                            .offset { IntOffset(face.left, face.top) }
                            .size(face.width().dp, face.height().dp)
                            .border(4.dp, Color.Green, RoundedCornerShape(16.dp))
                            .zIndex(1f),
                        color = androidx.compose.ui.graphics.Color.Transparent // No fill, just border
                    ) {}
                }
            }
        }
    }
}