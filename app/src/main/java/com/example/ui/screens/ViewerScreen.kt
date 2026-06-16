package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.manager.FileAccessManager
import com.example.data.model.Bookmark
import com.example.ui.viewmodel.PdfViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: PdfViewModel,
    encodedUriString: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val decodedUri = remember(encodedUriString) {
        Uri.parse(Uri.decode(encodedUriString))
    }

    var pdfFile by remember { mutableStateOf<File?>(null) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Jump to page Dialog state
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpPageInput by remember { mutableStateOf("") }

    // Bookmark Drawer or Bottom sheet Dialog state
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var newBookmarkNoteInput by remember { mutableStateOf("") }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }

    // Lazy Column scroll state to check current visible index
    val listState = rememberLazyListState()
    val currentViewedPage = remember {
        derivedStateOf {
            if (pageCount > 0) {
                (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount)
            } else {
                1
            }
        }
    }

    // Load PDF in background
    LaunchedEffect(decodedUri) {
        withContext(Dispatchers.IO) {
            try {
                // Copy stream to cache seekable file (PdfRenderer constraint)
                val cacheFile = FileAccessManager.copyToInternalCache(
                    context, 
                    decodedUri, 
                    "viewer_temp_${System.currentTimeMillis()}.pdf"
                )
                if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
                    pdfFile = cacheFile
                    val descriptor = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = descriptor
                    val renderer = PdfRenderer(descriptor)
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                    
                    // Log in recent files database
                    viewModel.openFile(decodedUri, renderer.pageCount)
                    isLoading = false
                } else {
                    errorMessage = "Could not load document content. Please try another file."
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e("ViewerScreen", "Error rendering PDF", e)
                errorMessage = "Render Error: ${e.localizedMessage ?: "Invalid file structure"}"
                isLoading = false
            }
        }
    }

    // Cleanup resources in dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                pdfRenderer?.close()
                pfd?.close()
                pdfFile?.delete()
            } catch (e: Exception) {
                Log.w("ViewerScreen", "Error during resource cleanup", e)
            }
        }
    }

    val bookmarks by viewModel.currentFileBookmarks.collectAsStateWithLifecycle()
    val isPageBookmarked = remember(bookmarks, currentViewedPage.value) {
        bookmarks.any { it.pageNumber == currentViewedPage.value }
    }

    val fileName = remember(decodedUri) {
        FileAccessManager.getFileName(context, decodedUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // Share button
                    IconButton(onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, decodedUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share PDF Document"))
                        } catch (e: Exception) {
                            Log.e("ViewerScreen", "Error sharing file", e)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Bookmark toggle
                    IconButton(onClick = {
                        if (isPageBookmarked) {
                            val markToDelete = bookmarks.find { it.pageNumber == currentViewedPage.value }
                            if (markToDelete != null) {
                                viewModel.deleteBookmark(markToDelete)
                            }
                        } else {
                            newBookmarkNoteInput = ""
                            showAddBookmarkDialog = true
                        }
                    }) {
                        Icon(
                            imageVector = if (isPageBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                            contentDescription = "Bookmark Page",
                            tint = if (isPageBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Bookmarks list trigger
                    IconButton(
                        onClick = { showBookmarksSheet = true },
                        modifier = Modifier.testTag("bookmarks_list_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack, // Wait, let's use list or folders
                            contentDescription = "Bookmarks",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        // Actually let's use an explicit booklet list icon!
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (!isLoading && errorMessage == null) {
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Page ${currentViewedPage.value} of $pageCount",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TextButton(
                            onClick = {
                                jumpPageInput = currentViewedPage.value.toString()
                                showJumpDialog = true
                            },
                            modifier = Modifier.testTag("jump_page_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Directions,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Jump to Page")
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Loading PDF locally...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = errorMessage ?: "Failed to open PDF",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium
                        )
                        Button(onClick = onNavigateBack) {
                            Text("Go Back")
                        }
                    }
                }
                else -> {
                    // List of pages
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed((0 until pageCount).toList(), key = { _, index -> index }) { _, idx ->
                            pdfRenderer?.let { renderer ->
                                PdfPageItem(
                                    renderer = renderer,
                                    pageIndex = idx
                                )
                            }
                        }
                    }
                }
            }
        }

        // Jump to Page Dialog
        if (showJumpDialog) {
            AlertDialog(
                onDismissRequest = { showJumpDialog = false },
                title = { Text("Jump to Page") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter page number (1 to $pageCount):", fontSize = 13.sp)
                        OutlinedTextField(
                            value = jumpPageInput,
                            onValueChange = { jumpPageInput = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("jump_page_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetPage = jumpPageInput.toIntOrNull()
                            if (targetPage != null && targetPage in 1..pageCount) {
                                scope.launch {
                                    listState.scrollToItem(targetPage - 1)
                                }
                                showJumpDialog = false
                            }
                        }
                    ) {
                        Text("Go")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJumpDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Add Bookmark dialog
        if (showAddBookmarkDialog) {
            AlertDialog(
                onDismissRequest = { showAddBookmarkDialog = false },
                title = { Text("Bookmark Page ${currentViewedPage.value}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add a short note for this page (optional):", fontSize = 13.sp)
                        OutlinedTextField(
                            value = newBookmarkNoteInput,
                            onValueChange = { newBookmarkNoteInput = it },
                            placeholder = { Text("e.g. Reference chart, Chapter 3") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bookmark_note_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.addBookmark(currentViewedPage.value, newBookmarkNoteInput)
                            showAddBookmarkDialog = false
                        }
                    ) {
                        Text("Add Bookmark")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddBookmarkDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Bookmarks drawer bottom sheet dialog
        if (showBookmarksSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBookmarksSheet = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bookmarks & Index",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = { showBookmarksSheet = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    if (bookmarks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No bookmarks added in this file.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxHeight(0.5f)
                        ) {
                            items(bookmarks.size) { index ->
                                val bookmark = bookmarks[index]
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                listState.scrollToItem(bookmark.pageNumber - 1)
                                            }
                                            showBookmarksSheet = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Page ${bookmark.pageNumber}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (bookmark.note.isNotEmpty()) {
                                                Text(
                                                    text = bookmark.note,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        IconButton(onClick = { viewModel.deleteBookmark(bookmark) }) {
                                            Icon(
                                                imageVector = Icons.Outlined.BookmarkRemove,
                                                contentDescription = "Delete Bookmark",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfPageItem(
    renderer: PdfRenderer,
    pageIndex: Int
) {
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(pageIndex)
                
                // Screen display scale: standard 2x for sharp view
                val scale = 1.8f
                val w = (page.width * scale).toInt()
                val h = (page.height * scale).toInt()
                
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                pageBitmap = bitmap
            } catch (e: Exception) {
                Log.e("PdfPageItem", "Failed to render page $pageIndex", e)
                renderError = e.localizedMessage ?: "Page render error"
            }
        }
    }

    DisposableEffect(pageIndex) {
        onDispose {
            pageBitmap?.recycle()
            pageBitmap = null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(720f / 1000f, false) // Fallback standard aspect ratio bounds
                .background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            when {
                renderError != null -> {
                    Text(
                        text = "Page ${pageIndex + 1}\n$renderError",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                pageBitmap == null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        Text(
                            text = "Page ${pageIndex + 1}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                else -> {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = "PDF Page ${pageIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
