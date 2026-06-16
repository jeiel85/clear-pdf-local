package com.example.ui.viewmodel

import android.app.Application
import android.graphics.*
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.manager.FileAccessManager
import com.example.data.manager.SettingsManager
import com.example.data.model.Bookmark
import com.example.data.model.RecentFile
import com.example.data.repository.BookmarkRepository
import com.example.data.repository.RecentFileRepository
import com.example.domain.usecase.ImageToPdfUseCase
import com.example.domain.usecase.MergePdfUseCase
import com.example.domain.usecase.SplitPdfUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    // Scanning screen local state
    private val _scannedImageFiles = MutableStateFlow<List<File>>(emptyList())
    val scannedImageFiles: StateFlow<List<File>> = _scannedImageFiles.asStateFlow()

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

    // Scanned image operations
    fun addScannedFile(file: File) {
        _scannedImageFiles.value = _scannedImageFiles.value + file
    }

    fun removeScannedFile(index: Int) {
        val current = _scannedImageFiles.value.toMutableList()
        if (index in current.indices) {
            val f = current.removeAt(index)
            f.delete()
            _scannedImageFiles.value = current
        }
    }

    fun clearScannedFiles() {
        _scannedImageFiles.value.forEach { it.delete() }
        _scannedImageFiles.value = emptyList()
    }

    /**
     * Applies filter style to a scanned frame file.
     * Filter styles: "ORIGINAL", "BLACK_WHITE", "SHARPEN"
     */
    fun applyFilterToScannedImage(index: Int, filterStyle: String) {
        val list = _scannedImageFiles.value
        if (index !in list.indices) return
        val targetFile = list[index]
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val originalBitmap = BitmapFactory.decodeFile(targetFile.absolutePath) ?: return@launch
                
                val filteredBitmap = when (filterStyle) {
                    "BLACK_WHITE" -> {
                        val bmp = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        val paint = Paint()
                        val cm = ColorMatrix()
                        cm.setSaturation(0f) // Saturation 0 is Black and White
                        
                        // Increase contrast for document readability
                        val scale = 1.4f
                        val translate = -15f
                        val contrastMatrix = ColorMatrix(floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        cm.postConcat(contrastMatrix)
                        
                        paint.colorFilter = ColorMatrixColorFilter(cm)
                        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
                        bmp
                    }
                    "SHARPEN" -> {
                        // Enhance brightness and contrast
                        val bmp = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        val paint = Paint()
                        val cm = ColorMatrix(floatArrayOf(
                            1.2f, 0f, 0f, 0f, 10f,
                            0f, 1.2f, 0f, 0f, 10f,
                            0f, 0f, 1.2f, 0f, 10f,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        paint.colorFilter = ColorMatrixColorFilter(cm)
                        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
                        bmp
                    }
                    else -> originalBitmap // ORIGINAL (no matrix)
                }

                FileOutputStream(targetFile).use { out ->
                    filteredBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                
                originalBitmap.recycle()
                if (filteredBitmap != originalBitmap) {
                    filteredBitmap.recycle()
                }

                // Force refresh flow by updating the list reference
                _scannedImageFiles.value = _scannedImageFiles.value.toList()
                _operationMessage.emit("Filter applied.")
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Error applying filter", e)
            }
        }
    }

    fun generatePdfFromScanned(outputName: String, onFinished: (File) -> Unit) {
        val files = _scannedImageFiles.value
        if (files.isEmpty()) return
        val uris = files.map { Uri.fromFile(it) }
        viewModelScope.launch {
            try {
                val cleanName = if (outputName.endsWith(".pdf", ignoreCase = true)) outputName else "$outputName.pdf"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, cleanName)
                
                val result = ImageToPdfUseCase.execute(context, uris, outputFile)
                if (result.isSuccess) {
                    _operationMessage.emit("Scan PDF saved: $cleanName")
                    clearScannedFiles()
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
