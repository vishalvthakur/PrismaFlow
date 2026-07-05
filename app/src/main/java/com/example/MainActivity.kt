package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }

    // Selected Video State
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf("No video selected") }
    var selectedVideoSize by remember { mutableStateOf("") }
    var selectedStyleMode by remember { mutableStateOf("cartoon") } // "cartoon" or "anime"
    var isCopyingToCache by remember { mutableStateOf(false) }
    var renderedVideoUri by remember { mutableStateOf<Uri?>(null) }

    // WorkManager status observer
    val workInfos by workManager.getWorkInfosForUniqueWorkFlow("video_cartoonize_job")
        .collectAsState(initial = emptyList())

    val activeWorkInfo = workInfos.firstOrNull()
    val isProcessing = activeWorkInfo != null && (
        activeWorkInfo.state == WorkInfo.State.RUNNING ||
        activeWorkInfo.state == WorkInfo.State.ENQUEUED ||
        activeWorkInfo.state == WorkInfo.State.BLOCKED
    )
    val isSucceeded = activeWorkInfo?.state == WorkInfo.State.SUCCEEDED
    val isFailed = activeWorkInfo?.state == WorkInfo.State.FAILED

    val progressValue = activeWorkInfo?.progress?.getInt(VideoProcessorWorker.KEY_PROGRESS, 0) ?: 0
    val statusText = if (activeWorkInfo?.state == WorkInfo.State.ENQUEUED) {
        "Queued for processing..."
    } else {
        activeWorkInfo?.progress?.getString(VideoProcessorWorker.KEY_STATUS) ?: "Standby"
    }
    val outputPath = activeWorkInfo?.outputData?.getString(VideoProcessorWorker.KEY_OUTPUT_PATH)
    val errorMessage = activeWorkInfo?.outputData?.getString(VideoProcessorWorker.KEY_ERROR)
    val showProgressAndOutput = selectedVideoUri != null && selectedVideoUri == renderedVideoUri

    // Sync renderedVideoUri from activeWorkInfo progress or output to restore state automatically
    LaunchedEffect(activeWorkInfo) {
        if (activeWorkInfo != null) {
            val progressUri = activeWorkInfo.progress.getString(VideoProcessorWorker.KEY_INPUT_URI)
            val outputUri = activeWorkInfo.outputData.getString(VideoProcessorWorker.KEY_INPUT_URI)
            val uriStr = progressUri ?: outputUri
            if (uriStr != null) {
                val recoveredUri = Uri.parse(uriStr)
                if (selectedVideoUri == null) {
                    selectedVideoUri = recoveredUri
                    renderedVideoUri = recoveredUri
                    selectedVideoName = "Recovered Video"
                } else if (renderedVideoUri == null) {
                    renderedVideoUri = recoveredUri
                }
            }
        }
    }

    // Gallery Video Picker
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isCopyingToCache = true
            selectedVideoName = "Importing video..."
            selectedVideoSize = ""
            coroutineScope.launch(Dispatchers.IO) {
                val cachedFile = copyUriToCache(context, uri)
                withContext(Dispatchers.Main) {
                    isCopyingToCache = false
                    if (cachedFile != null) {
                        selectedVideoUri = Uri.fromFile(cachedFile)
                        selectedVideoName = getFileName(context, uri) ?: "Selected Video"
                        selectedVideoSize = getFileSizeString(context, uri) ?: ""
                    } else {
                        selectedVideoName = "Failed to load video"
                        Toast.makeText(context, "Failed to import video. Try another file.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SmartDisplay,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PrismaFlow AI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        // AI indicator badge
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFFE8DEF8))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "AI",
                                color = Color(0xFF1D192B),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elegant Split-Screen Visual Hero Banner matching Vibrant Palette mockup
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF1D1B20)),
                contentAlignment = Alignment.Center
            ) {
                // Split Screen visual representation
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Side: Grayscale/Raw original representation
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF2C2A30), Color(0xFF1D1B20))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "RAW SOURCE",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    
                    // Right Side: Beautiful Colorful AI Cartoonized effect
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF311B92), Color(0xFF6750A4))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SmartDisplay,
                                contentDescription = null,
                                tint = Color(0xFFD0BCFF),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (selectedStyleMode == "anime") "AI ANIME" else "AI CARTOON",
                                color = Color(0xFFD0BCFF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                // Scanning Line effect in the middle
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(Color(0xFFD0BCFF))
                )

                // Overlay HUD Left
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "RECORDING: 1080P",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFB3261E).copy(alpha = 0.8f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "STYLIZING",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                // Overlay HUD Right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "12.4 FPS",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "GPU TASK INFERENCE",
                            color = Color(0xFFD0BCFF),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Step 1 Card: Select Video Source (Vibrant Palette Theme)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = Color(0xFFCAC4D0),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = "Video Source",
                            tint = Color(0xFF6750A4)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "1. CHOOSE SOURCE VIDEO",
                            color = Color(0xFF1D1B20),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(16.dp))
                            .clickable(enabled = !isProcessing && !isCopyingToCache) {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            }
                            .testTag("select_video_card"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCopyingToCache) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF6750A4)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Importing and copying video to safe cache...",
                                    color = Color(0xFF49454F),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else if (selectedVideoUri == null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.VideoLibrary,
                                    contentDescription = "Import video icon",
                                    tint = Color(0xFF6750A4),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap to choose video from gallery",
                                    color = Color(0xFF49454F),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = selectedVideoName,
                                    color = Color(0xFF6750A4),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.SdCard,
                                        contentDescription = "Size",
                                        tint = Color(0xFF79747E),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = selectedVideoSize,
                                        color = Color(0xFF79747E),
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap to change video",
                                    color = Color(0xFFFF007F),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            isCopyingToCache = true
                            selectedVideoName = "Preparing sample video..."
                            selectedVideoSize = ""
                            coroutineScope.launch(Dispatchers.IO) {
                                val cachedFile = copyAssetToCache(context, "sample.mp4")
                                withContext(Dispatchers.Main) {
                                    isCopyingToCache = false
                                    if (cachedFile != null) {
                                        selectedVideoUri = Uri.fromFile(cachedFile)
                                        selectedVideoName = "sample.mp4 (Offline Demo)"
                                        selectedVideoSize = "770 KB"
                                        Toast.makeText(context, "Offline sample video loaded successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        selectedVideoName = "Failed to load sample video"
                                        Toast.makeText(context, "Failed to load sample video from assets.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing && !isCopyingToCache,
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF6750A4)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF6750A4)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("load_sample_video_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Sample video",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TRY BUNDLED SAMPLE VIDEO",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardware Stats Grid matching mockup
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Memory Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "MEMORY USAGE",
                            color = Color(0xFF79747E),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isProcessing) "184MB / 1.2GB Pool" else "32MB Idle",
                            color = Color(0xFF1D1B20),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Worker Status Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "WORKER STATUS",
                            color = Color(0xFF79747E),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isProcessing) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isProcessing) "Active Foreground" else "Service Standby",
                                color = Color(0xFF1D1B20),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2: Controls & Progress Section (Vibrant Palette Theme)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = Color(0xFFCAC4D0),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "2. AI STYLIZATION CONTROLS",
                        color = Color(0xFF1D1B20),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "SELECT STYLIZATION STYLE",
                        color = Color(0xFF79747E),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Cartoon Option Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selectedStyleMode == "cartoon") Color(0xFFE8DEF8) else Color.White)
                                .border(
                                    width = if (selectedStyleMode == "cartoon") 2.dp else 1.dp,
                                    color = if (selectedStyleMode == "cartoon") Color(0xFF6750A4) else Color(0xFFCAC4D0),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable(enabled = !isProcessing) { selectedStyleMode = "cartoon" }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Cartoon mode",
                                    tint = if (selectedStyleMode == "cartoon") Color(0xFF6750A4) else Color(0xFF49454F),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Classic Cartoon",
                                    color = if (selectedStyleMode == "cartoon") Color(0xFF1D192B) else Color(0xFF49454F),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Edge lines & shading",
                                    color = if (selectedStyleMode == "cartoon") Color(0xFF1D192B).copy(alpha = 0.7f) else Color(0xFF79747E),
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Anime Option Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selectedStyleMode == "anime") Color(0xFFE8DEF8) else Color.White)
                                .border(
                                    width = if (selectedStyleMode == "anime") 2.dp else 1.dp,
                                    color = if (selectedStyleMode == "anime") Color(0xFF6750A4) else Color(0xFFCAC4D0),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable(enabled = !isProcessing) { selectedStyleMode = "anime" }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Anime mode",
                                    tint = if (selectedStyleMode == "anime") Color(0xFF6750A4) else Color(0xFF49454F),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Vibrant Anime",
                                    color = if (selectedStyleMode == "anime") Color(0xFF1D192B) else Color(0xFF49454F),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Soft bloom, rich color",
                                    color = if (selectedStyleMode == "anime") Color(0xFF1D192B).copy(alpha = 0.7f) else Color(0xFF79747E),
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (showProgressAndOutput && (isProcessing || progressValue > 0)) {
                        // Detailed Mockup processing panel styled inside a beautiful nested Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Custom Circular Progress Ring with Percentage inside
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(72.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { progressValue / 100f },
                                            modifier = Modifier.fillMaxSize(),
                                            color = Color(0xFF6750A4),
                                            strokeWidth = 6.dp,
                                            trackColor = Color(0xFFE6E1E5)
                                        )
                                        Text(
                                            text = "$progressValue%",
                                            color = Color(0xFF6750A4),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (selectedStyleMode == "anime") "Vibrant Anime Stylization" else "Classic Cartoon Stylization",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D1B20),
                                            fontSize = 15.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Engine: AnimeGANv2 TFLite",
                                            color = Color(0xFF79747E),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        // A small pill badge indicating processing state
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFE8DEF8))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isProcessing) "RENDERING VIDEO" else "COMPLETED",
                                                color = Color(0xFF1D192B),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Linear detailed bar
                                val animatedProgress by animateFloatAsState(
                                    targetValue = progressValue / 100f,
                                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
                                )
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape),
                                    color = Color(0xFF6750A4),
                                    trackColor = Color(0xFFE6E1E5)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = statusText,
                                        color = Color(0xFF49454F),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (isProcessing) "Est: converting..." else "Done",
                                        color = Color(0xFF49454F),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Primary Action Button (Pill shape, modern styling)
                    if (!isProcessing) {
                        Button(
                            onClick = {
                                val uri = selectedVideoUri
                                if (uri == null) {
                                    Toast.makeText(context, "Please select a video first", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val inputData = workDataOf(
                                    VideoProcessorWorker.KEY_INPUT_URI to uri.toString(),
                                    VideoProcessorWorker.KEY_STYLE_MODE to selectedStyleMode
                                )
                                val workRequest = OneTimeWorkRequestBuilder<VideoProcessorWorker>()
                                    .setInputData(inputData)
                                    .addTag("video_cartoonize_job")
                                    .build()

                                workManager.enqueueUniqueWork(
                                    "video_cartoonize_job",
                                    ExistingWorkPolicy.REPLACE,
                                    workRequest
                                )
                                renderedVideoUri = uri
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("start_processing_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = CircleShape
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (selectedStyleMode == "anime") "START ANIME RENDER" else "START CARTOON RENDER",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 0.5.sp,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                workManager.cancelUniqueWork("video_cartoonize_job")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("stop_processing_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                            shape = CircleShape
                        ) {
                            Icon(imageVector = Icons.Default.StopCircle, contentDescription = "Stop", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ABORT RENDERING ($progressValue%)",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 0.5.sp,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Fail State View
                    if (isFailed && errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFDE8E8), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFE53935), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Error",
                                    tint = Color(0xFFE53935),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Error: $errorMessage",
                                    color = Color(0xFFC62828),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Step 3: Output Results Section (Vibrant Palette Theme)
            if (showProgressAndOutput && isSucceeded && outputPath != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = Color(0xFFCAC4D0),
                            shape = RoundedCornerShape(28.dp)
                        ),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Succeeded icon",
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "3. STYLIZED VIDEO READY!",
                                color = Color(0xFF1D1B20),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Video Preview Player in nice rounded box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Black)
                                .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(24.dp))
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(outputPath)
                                        setOnPreparedListener { mediaPlayer ->
                                            mediaPlayer.isLooping = true
                                            start()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        var isSaving by remember { mutableStateOf(false) }

                        Button(
                            onClick = {
                                isSaving = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val videoFile = File(outputPath)
                                    val savedUri = saveVideoToGallery(
                                        context = context,
                                        videoFile = videoFile,
                                        displayName = "Cartoonized_${System.currentTimeMillis()}.mp4"
                                    )
                                    withContext(Dispatchers.Main) {
                                        isSaving = false
                                        if (savedUri != null) {
                                            Toast.makeText(
                                                context,
                                                "Successfully saved to Movies/VideoCartoonizer!",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Failed to save video to gallery.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("save_gallery_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = CircleShape
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Save icon",
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SAVE TO DEVICE GALLERY",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper: Copy bundled sample asset video to cache folder so it behaves like a file URI
private fun copyAssetToCache(context: Context, assetName: String): File? {
    try {
        val inputStream = context.assets.open(assetName)
        val tempFile = File(context.cacheDir, "sample_source_${System.currentTimeMillis()}.mp4")
        tempFile.outputStream().use { outputStream ->
            inputStream.use { it.copyTo(outputStream) }
        }
        return tempFile
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to copy asset to cache", e)
        return null
    }
}

// Helper: Copy selected Uri content to a secure, accessible app cache file
private fun copyUriToCache(context: Context, uri: Uri): File? {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "temp_source_${System.currentTimeMillis()}.mp4")
        tempFile.outputStream().use { outputStream ->
            inputStream.use { it.copyTo(outputStream) }
        }
        return tempFile
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to copy URI to cache", e)
        return null
    }
}

// Helper: Query display name of a Uri
private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = it.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}

// Helper: Query file size of a Uri
private fun getFileSizeString(context: Context, uri: Uri): String? {
    var sizeBytes: Long = 0
    if (uri.scheme == "content") {
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    sizeBytes = it.getLong(index)
                }
            }
        }
    }
    if (sizeBytes == 0L) return null
    return when {
        sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024f * 1024f))
        sizeBytes >= 1024 -> String.format("%.1f KB", sizeBytes / 1024f)
        else -> "$sizeBytes Bytes"
    }
}

// Helper: Save video file to system Movies gallery
fun saveVideoToGallery(context: Context, videoFile: File, displayName: String): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoCartoonizer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val uri = resolver.insert(collection, contentValues) ?: return null

    return try {
        resolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(videoFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        uri
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to save video to gallery", e)
        try {
            resolver.delete(uri, null, null)
        } catch (ex: Exception) {
            // ignore
        }
        null
    }
}
