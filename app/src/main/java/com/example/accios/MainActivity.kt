package com.example.accios

import android.Manifest
import android.os.Bundle
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
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

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