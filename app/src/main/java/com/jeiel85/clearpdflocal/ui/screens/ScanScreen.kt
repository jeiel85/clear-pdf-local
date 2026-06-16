package com.jeiel85.clearpdflocal.ui.screens

import android.Manifest
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.jeiel85.clearpdflocal.ui.viewmodel.PdfViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: PdfViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe local list of scanned items
    val scannedFiles by viewModel.scannedImageFiles.collectAsState()

    // Accompanist check state for camera permissions
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    var previewUseCase by remember { mutableStateOf<androidx.camera.core.Preview?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    // Dialog configuration states
    var showSaveDialog by remember { mutableStateOf(false) }
    var documentNameInput by remember { mutableStateOf("Scanned_Doc_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}") }
    var isCompiling by remember { mutableStateOf(false) }

    // Filter focus popup state
    var selectedThumbnailIdx by remember { mutableStateOf<Int?>(null) }
    var showFilterOptionsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Document Scan", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearScannedFiles()
                        onNavigateBack()
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = Color.Black, // Camera view screens prefer high contrast black background
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        if (cameraPermissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // CameraX viewport preview
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            previewUseCase = androidx.camera.core.Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    previewUseCase,
                                    imageCapture
                                )
                            } catch (e: Exception) {
                                Log.e("ScanScreen", "CameraX Binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    }
                )

                // Layout framing grid mockup overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                )

                // Bottom operations tray panel
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Previews of captures row
                    if (scannedFiles.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Scanned Frames (${scannedFiles.size})",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Tap frame to apply B&W/Sharpen filters",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                        ) {
                            itemsIndexed(scannedFiles) { index, file ->
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(2.dp, Color.White.copy(alpha = if (selectedThumbnailIdx == index) 0.9f else 0.2f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedThumbnailIdx = index
                                            showFilterOptionsSheet = true
                                        }
                                ) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Page NumberBadge
                                    Box(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .size(16.dp)
                                            .align(Alignment.TopStart)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Main Capture Trigger row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Dismiss list button
                        IconButton(
                            onClick = {
                                viewModel.clearScannedFiles()
                            },
                            enabled = scannedFiles.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear All Scanned",
                                tint = if (scannedFiles.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Big main camera shuttle button
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable {
                                    val tempPhoto = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(tempPhoto).build()
                                    
                                    imageCapture.takePicture(
                                        outputOptions,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                viewModel.addScannedFile(tempPhoto)
                                            }
                                            override fun onError(exception: ImageCaptureException) {
                                                Log.e("ScanScreen", "Fail capture picture", exception)
                                            }
                                        }
                                    )
                                }
                                .testTag("camera_shutter_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(66.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, Color.Black, CircleShape)
                                    .background(Color.White)
                            )
                        }

                        // Save Document compiling trigger
                        IconButton(
                            onClick = { showSaveDialog = true },
                            enabled = scannedFiles.isNotEmpty(),
                            modifier = Modifier.testTag("compile_scanned_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = "Save document",
                                tint = if (scannedFiles.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // Permission explaining fallback view
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Camera permission is required to capture documents locally.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = Color.White
                    )
                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        }

        // Filter modal bottom sheet dialog
        if (showFilterOptionsSheet && selectedThumbnailIdx != null) {
            val idx = selectedThumbnailIdx!!
            ModalBottomSheet(
                onDismissRequest = { showFilterOptionsSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Manage Page ${idx + 1}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.applyFilterToScannedImage(idx, "BLACK_WHITE")
                                showFilterOptionsSheet = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("B&W Filter")
                        }

                        Button(
                            onClick = {
                                viewModel.applyFilterToScannedImage(idx, "SHARPEN")
                                showFilterOptionsSheet = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sharpen")
                        }
                    }

                    // Delete current frame
                    Button(
                        onClick = {
                            viewModel.removeScannedFile(idx)
                            showFilterOptionsSheet = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Page frame")
                    }
                }
            }
        }

        // Save scanned Document Dialog
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save Scanned PDF") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("All file components will be compiled securely and stored in Downloads locally.", fontSize = 13.sp)
                        OutlinedTextField(
                            value = documentNameInput,
                            onValueChange = { documentNameInput = it },
                            label = { Text("Document Title") },
                            singleLine = true,
                            trailingIcon = { Text(".pdf", modifier = Modifier.padding(end = 12.dp)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("scan_save_name_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isCompiling = true
                            viewModel.generatePdfFromScanned(documentNameInput) { _ ->
                                showSaveDialog = false
                                isCompiling = false
                                viewModel.clearScannedFiles()
                                onNavigateBack()
                            }
                        },
                        enabled = !isCompiling
                    ) {
                        if (isCompiling) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Text("Compile & Save")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
