package com.example.accios

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.accios.ui.theme.AcciosTheme
import com.example.accios.viewmodels.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcciosTheme {
                Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    CameraPreviewScreen(modifier = Modifier.fillMaxSize())

                    TopNavbar()
                }
            }
        }
    }
}

@Composable
fun TopNavbar() {
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
                text = "Accios Prisma",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )

            IconButton(onClick = { /* Handle menu click */ }) {
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
    viewModel: CameraViewModel = viewModel()
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

                // Face shape overlay
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

                // Alert Container - 100px below face shape
                if (faceShapeSize != IntSize.Zero) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = with(density) { (faceShapeSize.height / 2 + 100).toDp() })
                    ) {
                        AlertContainer()
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraPreviewScreenPreview() {
    AcciosTheme {
        CameraPreviewScreen()
    }
}