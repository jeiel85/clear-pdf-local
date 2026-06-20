package com.jeiel85.clearpdflocal.ui.screens

import android.Manifest
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.jeiel85.clearpdflocal.domain.imaging.DocumentFrameAnalyzer
import com.jeiel85.clearpdflocal.domain.imaging.ScanMode
import com.jeiel85.clearpdflocal.ui.viewmodel.PdfViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: PdfViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe local list of scanned pages + processing state
    val scannedPages by viewModel.scannedPages.collectAsState()
    val isProcessing by viewModel.isProcessingScan.collectAsState()

    // Accompanist check state for camera permissions
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    var previewUseCase by remember { mutableStateOf<androidx.camera.core.Preview?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    // Dialog configuration states
    var showSaveDialog by remember { mutableStateOf(false) }
    var documentNameInput by remember { mutableStateOf("Scanned_Doc_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}") }
    var isCompiling by remember { mutableStateOf(false) }
    var makeSearchable by remember { mutableStateOf(false) }

    // Filter focus popup state
    var selectedThumbnailIdx by remember { mutableStateOf<Int?>(null) }
    var showFilterOptionsSheet by remember { mutableStateOf(false) }

    // Live document detection / auto-capture wiring
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var liveResult by remember { mutableStateOf<DocumentFrameAnalyzer.DetectionResult?>(null) }
    var autoCaptureTick by remember { mutableIntStateOf(0) }
    var autoScanEnabled by remember { mutableStateOf(true) }
    var autoFlattenEnabled by remember { mutableStateOf(false) }
    var lastCaptureAt by remember { mutableLongStateOf(0L) }
    val analyzer = remember {
        DocumentFrameAnalyzer { result ->
            liveResult = result
            if (result.autoCapture) autoCaptureTick++
        }
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
    }

    // Shared capture path used by both the shutter and auto-capture.
    val capturePhoto: () -> Unit = {
        val tempPhoto = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempPhoto).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    viewModel.addCapturedPhoto(tempPhoto, autoFlattenEnabled)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScanScreen", "Fail capture picture", exception)
                }
            }
        )
    }

    // Fire auto-capture when the analyzer reports a stable page (debounced).
    LaunchedEffect(autoCaptureTick) {
        if (autoCaptureTick > 0 && autoScanEnabled && !isProcessing) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCaptureAt > AUTO_CAPTURE_COOLDOWN_MS) {
                lastCaptureAt = now
                capturePhoto()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            imageAnalysis.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    val overlayColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Document Scan", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearScannedPages()
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
                                    imageCapture,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("ScanScreen", "CameraX Binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    }
                )

                // Live document-edge overlay (auto-scan). Falls back to a static guide frame
                // when auto-scan is off or nothing is detected yet.
                val res = liveResult
                if (autoScanEnabled && res?.corners != null && res.srcWidth > 0 && res.srcHeight > 0) {
                    val corners = res.corners!!
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val mapped = mapCornersToCanvas(corners, res.srcWidth, res.srcHeight, size.width, size.height)
                        val path = Path().apply {
                            moveTo(mapped[0].x, mapped[0].y)
                            for (i in 1 until mapped.size) lineTo(mapped[i].x, mapped[i].y)
                            close()
                        }
                        drawPath(path, color = overlayColor, style = Stroke(width = 4.dp.toPx()))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    )
                }

                // Auto-scan / Auto-flatten toggles + contextual hint, pinned to the top.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = autoScanEnabled,
                            onClick = { autoScanEnabled = !autoScanEnabled },
                            label = { Text(if (autoScanEnabled) "Auto-scan ON" else "Auto-scan OFF") },
                            leadingIcon = {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        FilterChip(
                            selected = autoFlattenEnabled,
                            onClick = { autoFlattenEnabled = !autoFlattenEnabled },
                            label = { Text(if (autoFlattenEnabled) "Auto-flatten ON" else "Auto-flatten OFF") },
                            leadingIcon = {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                    val hint = when {
                        autoFlattenEnabled && autoScanEnabled ->
                            "Flip through a book — each page is captured and flattened automatically."
                        autoFlattenEnabled -> "Curved pages are flattened automatically when you capture."
                        autoScanEnabled -> "Hold steady over a page — it captures automatically. Flip for the next."
                        else -> null
                    }
                    if (hint != null) {
                        Text(
                            hint,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

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
                    if (scannedPages.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Scanned Pages (${scannedPages.size})",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Tap a page to change scan mode",
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
                            itemsIndexed(scannedPages) { index, page ->
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
                                        model = File(page.processedPath),
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
                                viewModel.clearScannedPages()
                            },
                            enabled = scannedPages.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear All Scanned",
                                tint = if (scannedPages.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Big main camera shuttle button (manual capture, always available)
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable { capturePhoto() }
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
                            enabled = scannedPages.isNotEmpty(),
                            modifier = Modifier.testTag("compile_scanned_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = "Save document",
                                tint = if (scannedPages.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Processing overlay — blocks re-capture while OpenCV crops/enhances the page.
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Enhancing scan…", color = Color.White, fontSize = 13.sp)
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

                    Text(
                        "Scan mode",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val modes = listOf(
                        ScanMode.AUTO to "Auto",
                        ScanMode.COLOR to "Color",
                        ScanMode.GRAYSCALE to "Gray",
                        ScanMode.BLACK_WHITE to "B&W"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        modes.forEach { (mode, label) ->
                            Button(
                                onClick = {
                                    viewModel.applyScanMode(idx, mode)
                                    showFilterOptionsSheet = false
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }

                    // Neural curved-page flattening (dewarp)
                    val page = scannedPages.getOrNull(idx)
                    Button(
                        onClick = {
                            viewModel.applyDewarp(idx)
                            showFilterOptionsSheet = false
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (page?.dewarped == true) "Re-flatten curved page (AI)" else "Flatten curved page (AI)")
                    }
                    Text(
                        "For photos of curved books — flattens the bent page. Takes a moment.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Best-effort finger removal
                    OutlinedButton(
                        onClick = {
                            viewModel.applyFingerRemoval(idx)
                            showFilterOptionsSheet = false
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.BackHand, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Remove finger")
                    }
                    Text(
                        "Best-effort — erases a finger holding the page edge (can't restore text it covered).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Delete current frame
                    Button(
                        onClick = {
                            viewModel.removeScannedPage(idx)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { makeSearchable = !makeSearchable }
                        ) {
                            Checkbox(checked = makeSearchable, onCheckedChange = { makeSearchable = it })
                            Column {
                                Text("Searchable PDF (OCR)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Recognize text on-device so the PDF is searchable. Slower.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isCompiling = true
                            viewModel.generatePdfFromScanned(documentNameInput, makeSearchable) { _ ->
                                showSaveDialog = false
                                isCompiling = false
                                viewModel.clearScannedPages()
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

/** Maps normalized (0..1) upright corners to canvas pixels using the same FILL_CENTER
 *  cover-scaling the camera preview applies, so the overlay lines up with what's on screen. */
private fun mapCornersToCanvas(
    corners: List<PointF>,
    srcWidth: Int,
    srcHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float
): List<Offset> {
    val scale = max(canvasWidth / srcWidth, canvasHeight / srcHeight)
    val dispW = srcWidth * scale
    val dispH = srcHeight * scale
    val offX = (canvasWidth - dispW) / 2f
    val offY = (canvasHeight - dispH) / 2f
    return corners.map { Offset(offX + it.x * dispW, offY + it.y * dispH) }
}

private const val AUTO_CAPTURE_COOLDOWN_MS = 1200L
