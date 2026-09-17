package com.faceswap.bulk.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.faceswap.bulk.BulkSwapViewModel
import com.faceswap.bulk.SwapStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkSwapScreen(viewModel: BulkSwapViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    val pickSource = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.setSource(it) } }

    val pickTargets = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) viewModel.setTargets(uris) }

    Scaffold(topBar = { TopAppBar(title = { Text("Bulk Face Swap") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("1. Source face", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(72.dp)) {
                    if (state.sourceUri != null) {
                        Image(
                            painter = rememberAsyncImagePainter(state.sourceUri),
                            contentDescription = "Source face",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Button(onClick = {
                    pickSource.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (state.sourceUri == null) "Choose photo" else "Change photo") }
            }

            HorizontalDivider()

            Text("2. Target photos (bulk)", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                pickTargets.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (state.targets.isEmpty()) "Choose photos" else "Change photos (${state.targets.size})") }

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { viewModel.runBulkSwap() },
                enabled = !state.isProcessing && state.sourceUri != null && state.targets.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.isProcessing)
                        "Processing ${state.completedCount}/${state.targets.size}…"
                    else "Run bulk swap"
                )
            }

            if (state.isProcessing) {
                LinearProgressIndicator(
                    progress = {
                        if (state.targets.isEmpty()) 0f
                        else state.completedCount / state.targets.size.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.targets) { target ->
                    ListItem(
                        leadingContent = {
                            Image(
                                painter = rememberAsyncImagePainter(target.resultUri ?: target.uri),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp)
                            )
                        },
                        headlineContent = { Text(statusLabel(target.status)) },
                        supportingContent = target.failReason?.let { reason -> { Text(reason) } }
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: SwapStatus): String = when (status) {
    SwapStatus.PENDING -> "Waiting…"
    SwapStatus.RUNNING -> "Swapping…"
    SwapStatus.DONE -> "Done — saved to Pictures/BulkFaceSwap"
    SwapStatus.NO_FACE_FOUND -> "Skipped — no single clear face found"
    SwapStatus.FAILED -> "Failed"
}
