package com.jeiel85.clearpdflocal.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jeiel85.clearpdflocal.data.local.AppDatabase
import com.jeiel85.clearpdflocal.data.manager.FileAccessManager
import com.jeiel85.clearpdflocal.data.manager.SettingsManager
import com.jeiel85.clearpdflocal.data.model.Bookmark
import com.jeiel85.clearpdflocal.data.model.RecentFile
import com.jeiel85.clearpdflocal.data.repository.BookmarkRepository
import com.jeiel85.clearpdflocal.data.repository.RecentFileRepository
import com.jeiel85.clearpdflocal.domain.imaging.BitmapIo
import com.jeiel85.clearpdflocal.domain.imaging.DocumentDetector
import com.jeiel85.clearpdflocal.domain.imaging.DocumentScanProcessor
import com.jeiel85.clearpdflocal.domain.imaging.ScanMode
import com.jeiel85.clearpdflocal.domain.imaging.ScannedPage
import com.jeiel85.clearpdflocal.domain.usecase.ImageToPdfUseCase
import com.jeiel85.clearpdflocal.domain.usecase.MergePdfUseCase
import com.jeiel85.clearpdflocal.domain.usecase.SplitPdfUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // Repositories
    private val database = AppDatabase.getDatabase(context)
    private val recentFileRepository = RecentFileRepository(database.recentFileDao())
    private val bookmarkRepository = BookmarkRepository(database.bookmarkDao())
    private val settingsManager = SettingsManager(context)

    // Flow settings
    val currentTheme = settingsManager.themeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM")
    val maxRecents = settingsManager.maxRecentsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)
    val sortOrder = settingsManager.sortOrderFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DATE_DESC")

    // Sort recent files flow based on DB updates + preference changes
    val recentFiles: StateFlow<List<RecentFile>> = combine(
        recentFileRepository.allRecentFiles,
        sortOrder,
        maxRecents
    ) { files, sort, maxLimit ->
        val sortedList = when (sort) {
            "NAME_ASC" -> files.sortedBy { it.fileName.lowercase() }
            "SIZE_DESC" -> files.sortedByDescending { it.fileSize }
            else -> files.sortedByDescending { it.lastOpened } // DATE_DESC
        }
        sortedList.take(maxLimit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Bookmarks for active reader file
    private val _currentFileUri = MutableStateFlow<String?>(null)
    val currentFileUri: StateFlow<String?> = _currentFileUri.asStateFlow()

    val currentFileBookmarks: StateFlow<List<Bookmark>> = _currentFileUri
        .flatMapLatest { uri ->
            if (uri != null) bookmarkRepository.getBookmarksForFile(uri)
            else flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Image to PDF screen local state
    private val _selectedImagesForPdf = MutableStateFlow<List<Uri>>(emptyList())
    val selectedImagesForPdf: StateFlow<List<Uri>> = _selectedImagesForPdf.asStateFlow()

    // Scanning screen local state — each page keeps its raw photo plus the processed result.
    private val _scannedPages = MutableStateFlow<List<ScannedPage>>(emptyList())
    val scannedPages: StateFlow<List<ScannedPage>> = _scannedPages.asStateFlow()

    // True while a capture or mode change is being processed by OpenCV.
    private val _isProcessingScan = MutableStateFlow(false)
    val isProcessingScan: StateFlow<Boolean> = _isProcessingScan.asStateFlow()

    // Merging screen local state
    private val _selectedPdfsForMerge = MutableStateFlow<List<Uri>>(emptyList())
    val selectedPdfsForMerge: StateFlow<List<Uri>> = _selectedPdfsForMerge.asStateFlow()

    // Operation status alerts
    private val _operationMessage = MutableSharedFlow<String>()
    val operationMessage: SharedFlow<String> = _operationMessage.asSharedFlow()

    // Settings actions
    fun setTheme(theme: String) {
        viewModelScope.launch { settingsManager.setTheme(theme) }
    }

    fun setMaxRecents(max: Int) {
        viewModelScope.launch { settingsManager.setMaxRecents(max) }
    }

    fun setSortOrder(sort: String) {
        viewModelScope.launch { settingsManager.setSortOrder(sort) }
    }

    // Recent File actions
    fun openFile(uri: Uri, pageCount: Int) {
        _currentFileUri.value = uri.toString()
        viewModelScope.launch {
            // Keep permission persistable if SAF URI
            FileAccessManager.takePersistableUriPermission(context, uri)

            val name = FileAccessManager.getFileName(context, uri)
            val size = FileAccessManager.getFileSize(context, uri)
            recentFileRepository.insertRecentFile(
                RecentFile(
                    uri = uri.toString(),
                    fileName = name,
                    lastOpened = System.currentTimeMillis(),
                    pageSize = pageCount,
                    fileSize = size
                )
            )
        }
    }

    fun deleteRecentFile(uri: String) {
        viewModelScope.launch {
            recentFileRepository.deleteRecentFileByUri(uri)
        }
    }

    fun clearRecentFiles() {
        viewModelScope.launch {
            recentFileRepository.clearAllRecentFiles()
        }
    }

    // Bookmark actions
    fun addBookmark(pageNumber: Int, note: String) {
        val fileUri = _currentFileUri.value ?: return
        viewModelScope.launch {
            bookmarkRepository.insertBookmark(
                Bookmark(
                    fileUri = fileUri,
                    pageNumber = pageNumber,
                    note = note
                )
            )
            _operationMessage.emit("Page $pageNumber bookmarked.")
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(bookmark)
        }
    }

    fun deleteBookmarkById(id: Int) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmarkById(id)
        }
    }

    // Image to PDF logic
    fun addImagesForPdf(uris: List<Uri>) {
        _selectedImagesForPdf.value = _selectedImagesForPdf.value + uris
    }

    fun removeImageForPdf(index: Int) {
        val current = _selectedImagesForPdf.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedImagesForPdf.value = current
        }
    }

    fun reorderImagesForPdf(fromIdx: Int, toIdx: Int) {
        val current = _selectedImagesForPdf.value.toMutableList()
        if (fromIdx in current.indices && toIdx in current.indices) {
            val item = current.removeAt(fromIdx)
            current.add(toIdx, item)
            _selectedImagesForPdf.value = current
        }
    }

    fun clearImagesForPdf() {
        _selectedImagesForPdf.value = emptyList()
    }

    fun generatePdfFromImages(outputName: String, onFinished: (File) -> Unit) {
        val uris = _selectedImagesForPdf.value
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val cleanName = if (outputName.endsWith(".pdf", ignoreCase = true)) outputName else "$outputName.pdf"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, cleanName)

                val result = ImageToPdfUseCase.execute(context, uris, outputFile)
                if (result.isSuccess) {
                    _operationMessage.emit("PDF generated in Downloads: $cleanName")
                    clearImagesForPdf()
                    onFinished(result.getOrThrow())
                } else {
                    _operationMessage.emit("Failed to generate PDF: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Error creating PDF from images", e)
                _operationMessage.emit("Error generating PDF: ${e.message}")
            }
        }
    }

    // Scanned page operations ----------------------------------------------------------------

    /**
     * Processes a freshly captured photo: detect the document quad, perspective-correct it,
     * and apply the default [ScanMode.AUTO] enhancement. The raw photo is kept so the user can
     * switch modes later without re-shooting.
     */
    fun addCapturedPhoto(rawFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessingScan.value = true
            try {
                val raw = BitmapIo.decodeScaled(rawFile.absolutePath)
                if (raw == null) {
                    _operationMessage.emit("Could not read the captured photo.")
                    return@launch
                }
                val corners = DocumentDetector.detectCorners(raw)
                val processed = DocumentScanProcessor.process(raw, corners, ScanMode.AUTO)
                raw.recycle()

                val processedFile = File(context.cacheDir, "scan_proc_${System.currentTimeMillis()}.jpg")
                BitmapIo.saveJpeg(processed, processedFile)
                processed.recycle()

                _scannedPages.value = _scannedPages.value + ScannedPage(
                    rawPath = rawFile.absolutePath,
                    processedPath = processedFile.absolutePath,
                    mode = ScanMode.AUTO,
                    corners = corners
                )
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Scan processing failed", e)
                _operationMessage.emit("Scan processing failed: ${e.message}")
            } finally {
                _isProcessingScan.value = false
            }
        }
    }

    /** Re-applies a different enhancement [mode] to an existing page, reprocessing from raw. */
    fun applyScanMode(index: Int, mode: ScanMode) {
        val pages = _scannedPages.value
        if (index !in pages.indices) return
        val page = pages[index]
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessingScan.value = true
            try {
                val raw = BitmapIo.decodeScaled(page.rawPath) ?: return@launch
                val processed = DocumentScanProcessor.process(raw, page.corners, mode)
                raw.recycle()

                // Write to a fresh file so Coil reloads instead of serving the cached image.
                val newProcessed = File(context.cacheDir, "scan_proc_${System.currentTimeMillis()}.jpg")
                BitmapIo.saveJpeg(processed, newProcessed)
                processed.recycle()
                File(page.processedPath).delete()

                _scannedPages.value = _scannedPages.value.toMutableList().also {
                    it[index] = page.copy(processedPath = newProcessed.absolutePath, mode = mode)
                }
                _operationMessage.emit("Applied ${mode.name} mode.")
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Mode change failed", e)
            } finally {
                _isProcessingScan.value = false
            }
        }
    }

    fun removeScannedPage(index: Int) {
        val pages = _scannedPages.value.toMutableList()
        if (index in pages.indices) {
            val page = pages.removeAt(index)
            File(page.rawPath).delete()
            File(page.processedPath).delete()
            _scannedPages.value = pages
        }
    }

    fun clearScannedPages() {
        _scannedPages.value.forEach {
            File(it.rawPath).delete()
            File(it.processedPath).delete()
        }
        _scannedPages.value = emptyList()
    }

    fun generatePdfFromScanned(outputName: String, onFinished: (File) -> Unit) {
        val pages = _scannedPages.value
        if (pages.isEmpty()) return
        val uris = pages.map { Uri.fromFile(File(it.processedPath)) }
        viewModelScope.launch {
            try {
                val cleanName = if (outputName.endsWith(".pdf", ignoreCase = true)) outputName else "$outputName.pdf"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, cleanName)

                val result = ImageToPdfUseCase.execute(context, uris, outputFile)
                if (result.isSuccess) {
                    _operationMessage.emit("Scan PDF saved: $cleanName")
                    clearScannedPages()
                    onFinished(result.getOrThrow())
                } else {
                    _operationMessage.emit("Scan conversion failed.")
                }
            } catch (e: Exception) {
                _operationMessage.emit("Error generating scan PDF: ${e.message}")
            }
        }
    }

    // Merge logic
    fun addPdfForMerge(uri: Uri) {
        _selectedPdfsForMerge.value = _selectedPdfsForMerge.value + uri
    }

    fun removePdfForMerge(index: Int) {
        val current = _selectedPdfsForMerge.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedPdfsForMerge.value = current
        }
    }

    fun reorderPdfsForMerge(fromIdx: Int, toIdx: Int) {
        val current = _selectedPdfsForMerge.value.toMutableList()
        if (fromIdx in current.indices && toIdx in current.indices) {
            val item = current.removeAt(fromIdx)
            current.add(toIdx, item)
            _selectedPdfsForMerge.value = current
        }
    }

    fun executeMerge(outputName: String, onFinished: (File) -> Unit) {
        val uris = _selectedPdfsForMerge.value
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val cleanName = if (outputName.endsWith(".pdf", ignoreCase = true)) outputName else "$outputName.pdf"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, cleanName)

                val result = MergePdfUseCase.execute(context, uris, outputFile)
                if (result.isSuccess) {
                    _operationMessage.emit("Merged PDF saved successfully as $cleanName")
                    _selectedPdfsForMerge.value = emptyList()
                    onFinished(result.getOrThrow())
                } else {
                    _operationMessage.emit("Merge empty or failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                _operationMessage.emit("Error merging files: ${e.message}")
            }
        }
    }

    // Split logic
    fun executeSplit(pdfUri: Uri, pageRange: String, outputName: String, onFinished: (File) -> Unit) {
        viewModelScope.launch {
            try {
                val cleanName = if (outputName.endsWith(".pdf", ignoreCase = true)) outputName else "$outputName.pdf"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, cleanName)

                val result = SplitPdfUseCase.execute(context, pdfUri, pageRange, outputFile)
                if (result.isSuccess) {
                    _operationMessage.emit("Split PDF saved successfully as $cleanName")
                    onFinished(result.getOrThrow())
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Unknown split issue"
                    _operationMessage.emit("Split failed: $errMsg")
                }
            } catch (e: Exception) {
                _operationMessage.emit("Error splitting file: ${e.message}")
            }
        }
    }

    // View file bookmark queries
    suspend fun isPageBookmarked(uriString: String, pageNumber: Int): Boolean {
        return bookmarkRepository.isBookmarked(uriString, pageNumber)
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PdfViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PdfViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
