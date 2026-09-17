package com.faceswap.bulk

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

enum class SwapStatus { PENDING, RUNNING, DONE, NO_FACE_FOUND, FAILED }

data class TargetItem(
    val uri: Uri,
    val status: SwapStatus = SwapStatus.PENDING,
    val resultUri: Uri? = null,
)

data class BulkSwapUiState(
    val sourceUri: Uri? = null,
    val targets: List<TargetItem> = emptyList(),
    val isProcessing: Boolean = false,
    val completedCount: Int = 0,
    val errorMessage: String? = null,
)

class BulkSwapViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(BulkSwapUiState())
    val uiState: StateFlow<BulkSwapUiState> = _uiState

    private val detector = FaceLandmarkDetector()

    fun setSource(uri: Uri) {
        _uiState.update { it.copy(sourceUri = uri, errorMessage = null) }
    }

    fun setTargets(uris: List<Uri>) {
        _uiState.update { it.copy(targets = uris.map { u -> TargetItem(u) }, errorMessage = null) }
    }

    fun runBulkSwap() {
        val state = _uiState.value
        val sourceUri = state.sourceUri
        if (sourceUri == null) {
            _uiState.update { it.copy(errorMessage = "Pick a source face photo first.") }
            return
        }
        if (state.targets.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Pick at least one target photo.") }
            return
        }
        if (state.isProcessing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, completedCount = 0, errorMessage = null) }

            val sourceBitmap = withContext(Dispatchers.IO) { loadBitmap(sourceUri) }
            val sourceFace = withContext(Dispatchers.Default) {
                sourceBitmap?.let { detector.detectSingleFace(it) }
            }
            if (sourceBitmap == null || sourceFace == null) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "No single clear face detected in the source photo."
                    )
                }
                return@launch
            }

            val targets = _uiState.value.targets
            for ((index, item) in targets.withIndex()) {
                markStatus(index, SwapStatus.RUNNING)
                val result = withContext(Dispatchers.Default) {
                    processOne(sourceBitmap, sourceFace, item.uri)
                }
                when (result) {
                    is OneResult.Success -> {
                        val savedUri = withContext(Dispatchers.IO) { saveToGallery(result.bitmap) }
                        updateTarget(index, SwapStatus.DONE, savedUri)
                    }
                    OneResult.NoFace -> updateTarget(index, SwapStatus.NO_FACE_FOUND, null)
                    OneResult.Error -> updateTarget(index, SwapStatus.FAILED, null)
                }
                _uiState.update { it.copy(completedCount = index + 1) }
            }

            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    private sealed class OneResult {
        data class Success(val bitmap: Bitmap) : OneResult()
        object NoFace : OneResult()
        object Error : OneResult()
    }

    private fun processOne(sourceBitmap: Bitmap, sourceFace: DetectedFace, targetUri: Uri): OneResult {
        return try {
            val targetBitmap = loadBitmap(targetUri) ?: return OneResult.Error
            val targetFace = detector.detectSingleFace(targetBitmap) ?: return OneResult.NoFace
            val result = FaceSwapEngine.swap(sourceBitmap, sourceFace, targetBitmap, targetFace)
                ?: return OneResult.Error
            OneResult.Success(result)
        } catch (t: Throwable) {
            OneResult.Error
        }
    }

    private fun markStatus(index: Int, status: SwapStatus) {
        _uiState.update { state ->
            val list = state.targets.toMutableList()
            list[index] = list[index].copy(status = status)
            state.copy(targets = list)
        }
    }

    private fun updateTarget(index: Int, status: SwapStatus, resultUri: Uri?) {
        _uiState.update { state ->
            val list = state.targets.toMutableList()
            list[index] = list[index].copy(status = status, resultUri = resultUri)
            state.copy(targets = list)
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val context = getApplication<Application>()
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null
        // Respect EXIF orientation so landmark coordinates line up with what the user sees.
        val rotationDegrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (rotationDegrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val context = getApplication<Application>()
        val filename = "faceswap_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BulkFaceSwap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: return null
        val out: OutputStream = context.contentResolver.openOutputStream(uri) ?: return null
        out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    override fun onCleared() {
        super.onCleared()
        detector.close()
    }
}
