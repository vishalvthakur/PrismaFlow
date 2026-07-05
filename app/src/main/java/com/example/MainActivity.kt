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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import android.content.SharedPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)
        setContent {
            val systemTheme = isSystemInDarkTheme()
            var themeMode by remember {
                mutableStateOf(prefs.getString("theme_mode", "system") ?: "system")
            }
            val isDarkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemTheme
            }
            MyApplicationTheme(darkTheme = isDarkTheme) {
                MainScreen(
                    isDarkTheme = isDarkTheme,
                    themeMode = themeMode,
                    onThemeChange = { newMode ->
                        themeMode = newMode
                        prefs.edit().putString("theme_mode", newMode).apply()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isDarkTheme: Boolean = false,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }

    // Selected Video State
    var videoQueue by remember { mutableStateOf(QueueManager.getQueue(context)) }
    var activeComparisonVideo by remember { mutableStateOf<QueuedVideo?>(null) }
    var selectedStyleMode by remember { mutableStateOf("pixar3d") } // "pixar3d", "cartoon", or "anime"
    var selectedResolution by remember { mutableStateOf("480p") } // "360p", "480p", "720p", "1080p"
    var selectedFps by remember { mutableStateOf(15) } // 10, 15, 24, 30
    var isCopyingToCache by remember { mutableStateOf(false) }
    var importProgressText by remember { mutableStateOf("") }

    // Theme-aware Color Palettes
    val cardBackground = if (isDarkTheme) Color(0xFF1D1B20) else Color.White
    val cardBorderColor = if (isDarkTheme) Color(0xFF49454F) else Color(0xFFCAC4D0)
    
    val selectedContainerColor = if (isDarkTheme) Color(0xFF4F378B) else Color(0xFFE8DEF8)
    val selectedContentColor = if (isDarkTheme) Color(0xFFE8DEF8) else Color(0xFF1D192B)
    val selectedBorderColor = if (isDarkTheme) Color(0xFFD0BCFF) else Color(0xFF6750A4)
    
    val unselectedContainerColor = if (isDarkTheme) Color(0xFF1D1B20) else Color.White
    val unselectedContentColor = if (isDarkTheme) Color(0xFFCAC4D0) else Color(0xFF49454F)
    val unselectedSubtextColor = if (isDarkTheme) Color(0xFF938F99) else Color(0xFF79747E)
    
    val textPrimaryColor = if (isDarkTheme) Color(0xFFE6E1E5) else Color(0xFF1D1B20)
    val textSecondaryColor = if (isDarkTheme) Color(0xFF938F99) else Color(0xFF79747E)

    // SharedPreferences listener to observe queue in real time
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("video_queue_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "video_queue") {
                videoQueue = QueueManager.getQueue(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    fun resumeQueueProcessing(ctx: Context, wm: WorkManager) {
        val queue = QueueManager.getQueue(ctx)
        val hasActive = queue.any { it.status == "Processing" }
        if (!hasActive) {
            val nextPending = queue.firstOrNull { it.status == "Pending" }
            if (nextPending != null) {
                val inputData = workDataOf(
                    VideoProcessorWorker.KEY_INPUT_URI to nextPending.originalUri,
                    VideoProcessorWorker.KEY_STYLE_MODE to nextPending.styleMode,
                    "queue_id" to nextPending.id,
                    "target_resolution" to nextPending.targetResolution,
                    "target_fps" to nextPending.targetFps
                )
                val workRequest = OneTimeWorkRequestBuilder<VideoProcessorWorker>()
                    .setInputData(inputData)
                    .addTag("video_cartoonize_job")
                    .build()
                wm.enqueueUniqueWork(
                    "video_cartoonize_job",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
                QueueManager.updateVideoStatus(ctx, nextPending.id, "Processing", 0)
            }
        }
    }

    // WorkManager status observer
    val workInfos by workManager.getWorkInfosForUniqueWorkFlow("video_cartoonize_job")
        .collectAsState(initial = emptyList())

    val activeWorkInfo = workInfos.firstOrNull()
    val isProcessing = activeWorkInfo != null && (
        activeWorkInfo.state == WorkInfo.State.RUNNING ||
        activeWorkInfo.state == WorkInfo.State.ENQUEUED ||
        activeWorkInfo.state == WorkInfo.State.BLOCKED
    )

    // Sync WorkManager statuses with queue items
    LaunchedEffect(workInfos) {
        val activeWork = workInfos.firstOrNull()
        val isRunning = activeWork != null && (
            activeWork.state == WorkInfo.State.RUNNING ||
            activeWork.state == WorkInfo.State.ENQUEUED ||
            activeWork.state == WorkInfo.State.BLOCKED
        )

        if (!isRunning) {
            val queue = QueueManager.getQueue(context)
            var changed = false
            queue.forEach { video ->
                if (video.status == "Processing") {
                    QueueManager.updateVideoStatus(context, video.id, "Pending", 0)
                    changed = true
                }
            }
            if (changed) {
                resumeQueueProcessing(context, workManager)
            }
        } else {
            activeWork?.let { work ->
                val progress = work.progress.getInt(VideoProcessorWorker.KEY_PROGRESS, -1)
                val queueId = work.progress.getString("queue_id") ?: work.outputData.getString("queue_id")
                
                if (queueId != null) {
                    val targetVideo = QueueManager.getQueue(context).find { it.id == queueId }
                    if (targetVideo != null) {
                        if (work.state == WorkInfo.State.SUCCEEDED) {
                            val outPath = work.outputData.getString(VideoProcessorWorker.KEY_OUTPUT_PATH)
                            if (targetVideo.status != "Completed" && outPath != null) {
                                QueueManager.updateVideoStatus(context, queueId, "Completed", 100, outputPath = outPath)
                                resumeQueueProcessing(context, workManager)
                            }
                        } else if (work.state == WorkInfo.State.FAILED) {
                            val err = work.outputData.getString(VideoProcessorWorker.KEY_ERROR) ?: "Pipeline error"
                            if (targetVideo.status != "Failed") {
                                QueueManager.updateVideoStatus(context, queueId, "Failed", errorMessage = err)
                                resumeQueueProcessing(context, workManager)
                            }
                        } else if (progress >= 0) {
                            QueueManager.updateVideoStatus(context, queueId, "Processing", progress)
                        }
                    }
                }
            }
        }
    }

    // Gallery Video Picker
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isCopyingToCache = true
            coroutineScope.launch(Dispatchers.IO) {
                uris.forEachIndexed { index, uri ->
                    withContext(Dispatchers.Main) {
                        importProgressText = "Copying video ${index + 1} of ${uris.size}..."
                    }
                    val cachedFile = copyUriToCache(context, uri)
                    if (cachedFile != null) {
                        val name = getFileName(context, uri) ?: "Selected Video"
                        val size = getFileSizeString(context, uri) ?: ""
                        val newVideo = QueuedVideo(
                            id = System.currentTimeMillis().toString() + "_" + (100..999).random(),
                            originalUri = Uri.fromFile(cachedFile).toString(),
                            name = name,
                            size = size,
                            styleMode = selectedStyleMode,
                            status = "Pending",
                            targetResolution = selectedResolution,
                            targetFps = selectedFps
                        )
                        QueueManager.addToQueue(context, newVideo)
                    }
                }
                withContext(Dispatchers.Main) {
                    isCopyingToCache = false
                    resumeQueueProcessing(context, workManager)
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
                        // Theme toggle and AI indicator badge row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            var showThemeMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { showThemeMenu = true },
                                    modifier = Modifier.testTag("theme_toggle_button")
                                ) {
                                    Icon(
                                        imageVector = when (themeMode) {
                                            "light" -> Icons.Default.LightMode
                                            "dark" -> Icons.Default.DarkMode
                                            else -> Icons.Default.BrightnessMedium
                                        },
                                        contentDescription = "Change Theme",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                DropdownMenu(
                                    expanded = showThemeMenu,
                                    onDismissRequest = { showThemeMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Light Theme") },
                                        onClick = {
                                            onThemeChange("light")
                                            showThemeMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.LightMode,
                                                contentDescription = null
                                            )
                                        },
                                        modifier = Modifier.testTag("theme_option_light")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Dark Theme") },
                                        onClick = {
                                            onThemeChange("dark")
                                            showThemeMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.DarkMode,
                                                contentDescription = null
                                            )
                                        },
                                        modifier = Modifier.testTag("theme_option_dark")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("System Default") },
                                        onClick = {
                                            onThemeChange("system")
                                            showThemeMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.BrightnessMedium,
                                                contentDescription = null
                                            )
                                        },
                                        modifier = Modifier.testTag("theme_option_system")
                                    )
                                }
                            }

                            // AI indicator badge
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "AI",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
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
                                text = when (selectedStyleMode) {
                                    "anime" -> "AI ANIME"
                                    "pixar3d" -> "AI 3D PIXAR"
                                    else -> "AI CARTOON"
                                },
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
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = "Video Source",
                            tint = Color(0xFF6750A4)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "1. CHOOSE SOURCE VIDEOS",
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
                            .background(cardBackground)
                            .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                            .clickable(enabled = !isCopyingToCache) {
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
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = importProgressText.ifEmpty { "Importing selected videos..." },
                                    color = textSecondaryColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Queue,
                                    contentDescription = "Import video icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "SELECT VIDEOS TO CARTOONIZE",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Multiple selection supported. Will process in style selected below.",
                                    color = textSecondaryColor,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            isCopyingToCache = true
                            importProgressText = "Preparing sample video..."
                            coroutineScope.launch(Dispatchers.IO) {
                                val cachedFile = copyAssetToCache(context, "sample.mp4")
                                withContext(Dispatchers.Main) {
                                    isCopyingToCache = false
                                    if (cachedFile != null) {
                                        val newVideo = QueuedVideo(
                                            id = System.currentTimeMillis().toString() + "_" + (100..999).random(),
                                            originalUri = Uri.fromFile(cachedFile).toString(),
                                            name = "sample.mp4 (Offline Demo)",
                                            size = "770 KB",
                                            styleMode = selectedStyleMode,
                                            status = "Pending",
                                            targetResolution = selectedResolution,
                                            targetFps = selectedFps
                                        )
                                        QueueManager.addToQueue(context, newVideo)
                                        resumeQueueProcessing(context, workManager)
                                        Toast.makeText(context, "Sample video added to queue!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to load sample video.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isCopyingToCache,
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
                        .background(cardBackground, RoundedCornerShape(16.dp))
                        .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "MEMORY USAGE",
                            color = textSecondaryColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isProcessing) "184MB / 1.2GB Pool" else "32MB Idle",
                            color = textPrimaryColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Worker Status Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(cardBackground, RoundedCornerShape(16.dp))
                        .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "WORKER STATUS",
                            color = textSecondaryColor,
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
                                color = textPrimaryColor,
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
                        color = cardBorderColor,
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                        color = textSecondaryColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 3D Pixar Option Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selectedStyleMode == "pixar3d") selectedContainerColor else unselectedContainerColor)
                                .border(
                                    width = if (selectedStyleMode == "pixar3d") 2.dp else 1.dp,
                                    color = if (selectedStyleMode == "pixar3d") selectedBorderColor else cardBorderColor,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !isProcessing) { selectedStyleMode = "pixar3d" }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = "3D Pixar mode",
                                    tint = if (selectedStyleMode == "pixar3d") selectedBorderColor else unselectedContentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "3D Pixar",
                                    color = if (selectedStyleMode == "pixar3d") selectedContentColor else unselectedContentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Smooth 3D look",
                                    color = if (selectedStyleMode == "pixar3d") selectedContentColor.copy(alpha = 0.7f) else unselectedSubtextColor,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 11.sp
                                )
                            }
                        }

                        // Cartoon Option Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selectedStyleMode == "cartoon") selectedContainerColor else unselectedContainerColor)
                                .border(
                                    width = if (selectedStyleMode == "cartoon") 2.dp else 1.dp,
                                    color = if (selectedStyleMode == "cartoon") selectedBorderColor else cardBorderColor,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !isProcessing) { selectedStyleMode = "cartoon" }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Cartoon mode",
                                    tint = if (selectedStyleMode == "cartoon") selectedBorderColor else unselectedContentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Cartoon",
                                    color = if (selectedStyleMode == "cartoon") selectedContentColor else unselectedContentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Classic outline",
                                    color = if (selectedStyleMode == "cartoon") selectedContentColor.copy(alpha = 0.7f) else unselectedSubtextColor,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 11.sp
                                )
                            }
                        }

                        // Anime Option Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selectedStyleMode == "anime") selectedContainerColor else unselectedContainerColor)
                                .border(
                                    width = if (selectedStyleMode == "anime") 2.dp else 1.dp,
                                    color = if (selectedStyleMode == "anime") selectedBorderColor else cardBorderColor,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !isProcessing) { selectedStyleMode = "anime" }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Anime mode",
                                    tint = if (selectedStyleMode == "anime") selectedBorderColor else unselectedContentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Anime",
                                    color = if (selectedStyleMode == "anime") selectedContentColor else unselectedContentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Vibrant glow",
                                    color = if (selectedStyleMode == "anime") selectedContentColor.copy(alpha = 0.7f) else unselectedSubtextColor,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "EXPORT CONFIGURATION",
                        color = textSecondaryColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Resolution Section
                    Text(
                        text = "Output Resolution",
                        color = textPrimaryColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val resolutions = listOf("360p", "480p", "720p", "1080p")
                        resolutions.forEach { res ->
                            val isSelected = selectedResolution == res
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) selectedContainerColor else unselectedContainerColor)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) selectedBorderColor else cardBorderColor,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable(enabled = !isProcessing) { selectedResolution = res }
                                    .testTag("resolution_$res")
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = res,
                                        color = if (isSelected) selectedContentColor else unselectedContentColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = when (res) {
                                            "360p" -> "Fast"
                                            "480p" -> "Balanced"
                                            "720p" -> "High-Def"
                                            "1080p" -> "Full-Def"
                                            else -> ""
                                        },
                                        color = if (isSelected) selectedContentColor.copy(alpha = 0.7f) else unselectedSubtextColor,
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Frame Rate Section
                    Text(
                        text = "Frame Rate (FPS)",
                        color = textPrimaryColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fpsOptions = listOf(10, 15, 24, 30)
                        fpsOptions.forEach { fpsVal ->
                            val isSelected = selectedFps == fpsVal
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) selectedContainerColor else unselectedContainerColor)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) selectedBorderColor else cardBorderColor,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable(enabled = !isProcessing) { selectedFps = fpsVal }
                                    .testTag("fps_$fpsVal")
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$fpsVal FPS",
                                        color = if (isSelected) selectedContentColor else unselectedContentColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = when (fpsVal) {
                                            10 -> "Draft"
                                            15 -> "Default"
                                            24 -> "Cinema"
                                            30 -> "Fluid"
                                            else -> ""
                                        },
                                        color = if (isSelected) selectedContentColor.copy(alpha = 0.7f) else unselectedSubtextColor,
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val activeProcessingVideo = videoQueue.find { it.status == "Processing" }

                    if (activeProcessingVideo != null) {
                        val progressPct = activeProcessingVideo.progress
                        // Detailed Mockup processing panel styled inside a beautiful nested Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBackground),
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
                                            progress = { progressPct / 100f },
                                            modifier = Modifier.fillMaxSize(),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 6.dp,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        Text(
                                            text = "$progressPct%",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = activeProcessingVideo.name,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimaryColor,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Style: ${activeProcessingVideo.styleMode.uppercase()}",
                                            color = textSecondaryColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        // A small pill badge indicating processing state
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(selectedContainerColor)
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "RENDERING VIDEO QUEUE",
                                                color = selectedContentColor,
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
                                    targetValue = progressPct / 100f,
                                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
                                )
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Processing frame... (${progressPct}%)",
                                        color = textSecondaryColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "Est: converting...",
                                        color = textSecondaryColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Primary Action Button
                    if (!isProcessing) {
                        Button(
                            onClick = {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("start_processing_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            shape = CircleShape
                        ) {
                            Icon(imageVector = Icons.Default.Queue, contentDescription = "Select & Add", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SELECT VIDEOS & ADD TO QUEUE",
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
                                if (activeProcessingVideo != null) {
                                    QueueManager.updateVideoStatus(context, activeProcessingVideo.id, "Failed", errorMessage = "Work was cancelled by user")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("stop_processing_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                            shape = CircleShape
                        ) {
                            Icon(imageVector = Icons.Default.StopCircle, contentDescription = "Abort", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ABORT CURRENT RENDER",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 0.5.sp,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 3 Card: Video Processing Queue
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = cardBorderColor,
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Queue,
                                contentDescription = "Queue Icon",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "VIDEO PROCESSING QUEUE (${videoQueue.size})",
                                color = textPrimaryColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }

                        if (videoQueue.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    workManager.cancelUniqueWork("video_cartoonize_job")
                                    QueueManager.clearQueue(context)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear Queue",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (videoQueue.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(cardBackground, RoundedCornerShape(16.dp))
                                .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = "Empty Queue",
                                    tint = textSecondaryColor,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Your processing queue is empty.",
                                    color = textSecondaryColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            videoQueue.forEach { video ->
                                val statusBgColor = when (video.status) {
                                    "Processing" -> if (isDarkTheme) Color(0xFF2D1F4E) else Color(0xFFF3EDF7)
                                    "Completed" -> if (isDarkTheme) Color(0xFF1B3B2B) else Color(0xFFE8F5E9)
                                    "Failed" -> if (isDarkTheme) Color(0xFF3F1F1F) else Color(0xFFFDE8E8)
                                    else -> if (isDarkTheme) Color(0xFF2C2C2C) else Color(0xFFF5F5F5)
                                }
                                val statusBorderColor = when (video.status) {
                                    "Processing" -> if (isDarkTheme) Color(0xFFD0BCFF) else Color(0xFF6750A4)
                                    "Completed" -> if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
                                    "Failed" -> if (isDarkTheme) Color(0xFFE57373) else Color(0xFFE53935)
                                    else -> cardBorderColor
                                }
                                val statusTextColor = when (video.status) {
                                    "Processing" -> if (isDarkTheme) Color(0xFFD0BCFF) else Color(0xFF6750A4)
                                    "Completed" -> if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)
                                    "Failed" -> if (isDarkTheme) Color(0xFFE57373) else Color(0xFFE53935)
                                    else -> textSecondaryColor
                                }
                                val greenActionColor = if (isDarkTheme) Color(0xFF81C784) else Color(0xFF4CAF50)

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(statusBgColor, RoundedCornerShape(16.dp))
                                        .border(1.dp, statusBorderColor, RoundedCornerShape(16.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = video.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = textPrimaryColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(CircleShape)
                                                            .background(selectedContainerColor)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = video.styleMode.uppercase(),
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = selectedContentColor
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(CircleShape)
                                                            .background(if (isDarkTheme) Color(0xFF00363A) else Color(0xFFE0F7FA))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "${video.targetResolution} @ ${video.targetFps}FPS",
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isDarkTheme) Color(0xFF80DEEA) else Color(0xFF006064)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = video.size,
                                                        fontSize = 10.sp,
                                                        color = textSecondaryColor
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = when (video.status) {
                                                        "Processing" -> "Processing (${video.progress}%)"
                                                        "Completed" -> "Completed"
                                                        "Failed" -> "Failed"
                                                        else -> "Pending"
                                                    },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = statusTextColor,
                                                    modifier = Modifier.padding(end = 8.dp)
                                                )

                                                if (video.status == "Completed") {
                                                    var isSavingItem by remember { mutableStateOf(false) }

                                                    IconButton(
                                                        onClick = {
                                                            isSavingItem = true
                                                            coroutineScope.launch(Dispatchers.IO) {
                                                                val videoFile = File(video.outputPath ?: "")
                                                                val savedUri = saveVideoToGallery(
                                                                    context = context,
                                                                    videoFile = videoFile,
                                                                    displayName = "Cartoonized_${System.currentTimeMillis()}.mp4"
                                                                )
                                                                withContext(Dispatchers.Main) {
                                                                    isSavingItem = false
                                                                    if (savedUri != null) {
                                                                        Toast.makeText(
                                                                            context,
                                                                            "Saved to Gallery!",
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()
                                                                    } else {
                                                                        Toast.makeText(
                                                                            context,
                                                                            "Failed to save.",
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        enabled = !isSavingItem && video.outputPath != null,
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        if (isSavingItem) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(16.dp),
                                                                strokeWidth = 2.dp,
                                                                color = greenActionColor
                                                            )
                                                        } else {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = "Save to Gallery",
                                                                tint = greenActionColor
                                                            )
                                                        }
                                                    }

                                                    IconButton(
                                                        onClick = { activeComparisonVideo = video },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = "Play Comparison",
                                                            tint = greenActionColor
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        if (video.status == "Processing") {
                                                            workManager.cancelUniqueWork("video_cartoonize_job")
                                                        }
                                                        QueueManager.removeVideo(context, video.id)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Cancel,
                                                        contentDescription = "Remove Item",
                                                        tint = textSecondaryColor
                                                    )
                                                }
                                            }
                                        }

                                        // Progress bar or error message
                                        if (video.status == "Processing") {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { video.progress / 100f },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(CircleShape),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        } else if (video.status == "Failed" && video.errorMessage != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Error: ${video.errorMessage}",
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interactive Comparison Video Player Dialog
            if (activeComparisonVideo != null) {
                AlertDialog(
                    onDismissRequest = { activeComparisonVideo = null },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeComparisonVideo!!.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { activeComparisonVideo = null }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close Dialog")
                            }
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                        ) {
                            VideoComparisonPlayer(
                                originalUri = Uri.parse(activeComparisonVideo!!.originalUri),
                                stylizedPath = activeComparisonVideo!!.outputPath ?: "",
                                styleMode = activeComparisonVideo!!.styleMode,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {}
                )
            }
        }
    }
}

@Composable
fun VideoComparisonPlayer(
    originalUri: Uri,
    stylizedPath: String,
    styleMode: String,
    modifier: Modifier = Modifier
) {
    var previewLayout by remember { mutableStateOf("stacked") } // "stacked", "side_by_side", "stylized", "original"
    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(true) }

    // Store references to VideoViews and MediaPlayers for sync control
    var originalVideoView by remember { mutableStateOf<VideoView?>(null) }
    var stylizedVideoView by remember { mutableStateOf<VideoView?>(null) }
    var originalMediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var stylizedMediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    // Synchronize play/pause
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            originalVideoView?.start()
            stylizedVideoView?.start()
        } else {
            originalVideoView?.pause()
            stylizedVideoView?.pause()
        }
    }

    // Synchronize mute/unmute
    LaunchedEffect(isMuted) {
        stylizedMediaPlayer?.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF211F26), RoundedCornerShape(24.dp))
            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mode Selector Row (Tabs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(Color(0xFF1C1B1F), RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val modes = listOf(
                "stacked" to "Stacked",
                "side_by_side" to "Side-by-Side",
                "stylized" to "AI Output",
                "original" to "Original"
            )
            modes.forEach { (mode, label) ->
                val selected = previewLayout == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color(0xFF4F378B) else Color.Transparent)
                        .clickable { previewLayout = mode }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else Color(0xFFCAC4D0),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Players Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (previewLayout) {
                "stacked" -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top (Stylized Output)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(stylizedPath)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            stylizedMediaPlayer = mp
                                            mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                                            if (isPlaying) start()
                                        }
                                        stylizedVideoView = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Tag
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "STYLIZED (${styleMode.uppercase()})",
                                    color = Color(0xFFD0BCFF),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Bottom border separator divider line
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF49454F))
                        )

                        // Bottom (Original Input)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(originalUri)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            originalMediaPlayer = mp
                                            mp.setVolume(0f, 0f)
                                            if (isPlaying) start()
                                        }
                                        originalVideoView = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Tag
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ORIGINAL SOURCE",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                "side_by_side" -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left (Original)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(originalUri)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            originalMediaPlayer = mp
                                            mp.setVolume(0f, 0f)
                                            if (isPlaying) start()
                                        }
                                        originalVideoView = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Tag
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ORIGINAL",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Right border separator vertical divider line
                        Spacer(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(Color(0xFF49454F))
                        )

                        // Right (Stylized)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(stylizedPath)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            stylizedMediaPlayer = mp
                                            mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                                            if (isPlaying) start()
                                        }
                                        stylizedVideoView = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // Tag
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = styleMode.uppercase(),
                                    color = Color(0xFFD0BCFF),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                "stylized" -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(stylizedPath)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        stylizedMediaPlayer = mp
                                        mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                                        if (isPlaying) start()
                                    }
                                    stylizedVideoView = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Tag
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "STYLIZED OUTPUT (${styleMode.uppercase()})",
                                color = Color(0xFFD0BCFF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                "original" -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(originalUri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        originalMediaPlayer = mp
                                        mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                                        if (isPlaying) start()
                                    }
                                    originalVideoView = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Tag
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ORIGINAL SOURCE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Playback Controller Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Replay Button
            IconButton(
                onClick = {
                    originalVideoView?.seekTo(0)
                    stylizedVideoView?.seekTo(0)
                    originalVideoView?.start()
                    stylizedVideoView?.start()
                    isPlaying = true
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF313033), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Restart video playback",
                    tint = Color(0xFFD0BCFF),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Play/Pause Button
            IconButton(
                onClick = { isPlaying = !isPlaying },
                modifier = Modifier
                    .size(54.dp)
                    .background(Color(0xFF6750A4), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.StopCircle else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Volume/Mute Button
            IconButton(
                onClick = { isMuted = !isMuted },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF313033), CircleShape)
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = Color(0xFFD0BCFF),
                    modifier = Modifier.size(20.dp)
                )
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
