package com.vegspro.aroundrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
            Toast.makeText(this, "Нужны разрешения микрофона и уведомлений", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()
        setContent {
            AudioShowTheme {
                MainScreen()
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        }
    }
}

@Composable
fun AudioShowTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF7C9CFF),
        onPrimary = Color(0xFF0A0E21),
        secondary = Color(0xFF4ADE80),
        background = Color(0xFF0B0F1A),
        surface = Color(0xFF151B2B),
        surfaceVariant = Color(0xFF1E2638),
        onBackground = Color(0xFFE8ECF4),
        onSurface = Color(0xFFE8ECF4),
        onSurfaceVariant = Color(0xFFA0AEC0),
        error = Color(0xFFF87171)
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var isRecording by remember { mutableStateOf(RecordingService.isRunning) }
    var recordings by remember { mutableStateOf(listOf<File>()) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var analyzingFile by remember { mutableStateOf<File?>(null) }
    var analysisResult by remember { mutableStateOf<String?>(null) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", false)) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var playerFile by remember { mutableStateOf<File?>(null) }

    fun refreshList() {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "AroundRecordings")
        recordings = if (dir.exists()) {
            dir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".m4a") || it.name.endsWith(".mp4")) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else emptyList()
    }

    LaunchedEffect(Unit) {
        while (true) {
            isRecording = RecordingService.isRunning
            refreshList()
            delay(2000)
        }
    }

    fun startService() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
        isRecording = true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
            showBatteryDialog = true
        }
    }

    fun stopService() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
        isRecording = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AudioShow", fontWeight = FontWeight.Bold)
                        Text(
                            "24/7 запись · отрывки 15 мин",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showApiKeyDialog = true }) {
                        Icon(Icons.Default.Key, contentDescription = "API ключ Gemini")
                    }
                    IconButton(onClick = { refreshList() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            val statusColor by animateColorAsState(
                if (isRecording) Color(0xFF4ADE80) else Color(0xFFF87171),
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "status"
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(statusColor.copy(alpha = 0.25f), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRecording) Icons.Outlined.Mic else Icons.Outlined.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = statusColor
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isRecording) "ЗАПИСЬ ИДЁТ" else "ОСТАНОВЛЕНО",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = statusColor
                    )
                    Text(
                        if (isRecording) "Отрывки сохраняются каждые 15 минут"
                        else "Нажмите, чтобы начать непрерывную запись",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { if (isRecording) stopService() else startService() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFF87171) else Color(0xFF4ADE80),
                            contentColor = Color(0xFF0A0E21)
                        )
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRecording) "Остановить" else "Начать запись 24/7",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Автозапуск после перезагрузки", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Продолжать запись после ребута",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoStart,
                    onCheckedChange = {
                        autoStart = it
                        prefs.edit().putBoolean("auto_start", it).apply()
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            Text("Записи (${recordings.size})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "Нажмите на запись, чтобы слушать / обрезать",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Пока нет записей", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordings, key = { it.absolutePath }) { file ->
                        RecordingItem(
                            file = file,
                            onPlay = { playerFile = file },
                            onAnalyze = {
                                if (apiKey.isBlank()) {
                                    showApiKeyDialog = true
                                    Toast.makeText(context, "Сначала введите API-ключ Gemini", Toast.LENGTH_SHORT).show()
                                } else {
                                    analyzingFile = file
                                    isAnalyzing = true
                                    analysisResult = null
                                    analysisError = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            GeminiHelper.analyzeAudio(apiKey, file)
                                        }
                                        isAnalyzing = false
                                        result.fold(
                                            onSuccess = { analysisResult = it },
                                            onFailure = { analysisError = it.message }
                                        )
                                    }
                                }
                            },
                            onDelete = {
                                file.delete()
                                refreshList()
                            }
                        )
                    }
                }
            }
        }
    }

    // Player bottom sheet / dialog
    playerFile?.let { file ->
        PlayerDialog(
            file = file,
            onDismiss = { playerFile = null },
            onSaved = {
                Toast.makeText(context, "Сохранено: $it", Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showApiKeyDialog) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API ключ") },
            text = {
                Column {
                    Text(
                        "Получите бесплатный ключ на aistudio.google.com/apikey\nКлюч хранится только на устройстве.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = tempKey.trim()
                    prefs.edit().putString("gemini_api_key", apiKey).apply()
                    showApiKeyDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("Отключить оптимизацию батареи") },
            text = {
                Text("Чтобы запись не прерывалась в фоне, разрешите приложению работать без ограничений батареи.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Открыть настройки") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("Позже") }
            }
        )
    }

    if (analyzingFile != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isAnalyzing) {
                    analyzingFile = null
                    analysisResult = null
                    analysisError = null
                }
            },
            title = {
                Text(if (isAnalyzing) "Анализ…" else "Результат ИИ")
            },
            text = {
                when {
                    isAnalyzing -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Отправка в Gemini…\n(первые ~8–10 минут файла)")
                        }
                    }
                    analysisError != null -> Text("Ошибка: $analysisError", color = MaterialTheme.colorScheme.error)
                    analysisResult != null -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            item { Text(analysisResult!!, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            },
            confirmButton = {
                if (!isAnalyzing) {
                    TextButton(onClick = {
                        analyzingFile = null
                        analysisResult = null
                        analysisError = null
                    }) { Text("Закрыть") }
                }
            }
        )
    }
}

@Composable
fun RecordingItem(
    file: File,
    onPlay: () -> Unit,
    onAnalyze: () -> Unit,
    onDelete: () -> Unit
) {
    val sizeMb = remember(file) { "%.1f МБ".format(file.length() / (1024.0 * 1024.0)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Слушать",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.nameWithoutExtension,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    sizeMb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onAnalyze) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "Анализ ИИ", tint = Color(0xFF7C9CFF))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color(0xFFF87171))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDialog(
    file: File,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var trimStart by remember { mutableFloatStateOf(0f) }
    var trimEnd by remember { mutableFloatStateOf(1f) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(file) {
        val mp = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                durationMs = duration
                trimEnd = 1f
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        player = mp
        onDispose {
            try {
                mp.stop()
                mp.release()
            } catch (_: Exception) {
            }
            player = null
        }
    }

    // position ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player?.currentPosition ?: 0
            delay(200)
            if ((player?.currentPosition ?: 0) >= (player?.duration ?: 0)) {
                isPlaying = false
                player?.seekTo(0)
                positionMs = 0
            }
        }
    }

    fun formatTime(ms: Int): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val s = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60
        return "%d:%02d".format(m, s)
    }

    AlertDialog(
        onDismissRequest = {
            player?.pause()
            onDismiss()
        },
        title = {
            Text(
                file.nameWithoutExtension,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                // Play controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = {
                        val mp = player ?: return@IconButton
                        if (isPlaying) {
                            mp.pause()
                            isPlaying = false
                        } else {
                            // start from trim start if near beginning
                            if (positionMs < (trimStart * durationMs).toInt() + 500) {
                                mp.seekTo((trimStart * durationMs).toInt())
                            }
                            mp.start()
                            isPlaying = true
                        }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Slider(
                            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                            onValueChange = {
                                val pos = (it * durationMs).toInt()
                                player?.seekTo(pos)
                                positionMs = pos
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTime(positionMs), style = MaterialTheme.typography.bodySmall)
                            Text(formatTime(durationMs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Обрезка", fontWeight = FontWeight.SemiBold)
                Text(
                    "Начало: ${formatTime((trimStart * durationMs).toInt())}  →  Конец: ${formatTime((trimEnd * durationMs).toInt())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Range-like dual slider approximation
                Text("Начало", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = trimStart,
                    onValueChange = { v ->
                        trimStart = v.coerceAtMost(trimEnd - 0.02f)
                    },
                    valueRange = 0f..1f
                )
                Text("Конец", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = trimEnd,
                    onValueChange = { v ->
                        trimEnd = v.coerceAtLeast(trimStart + 0.02f)
                    },
                    valueRange = 0f..1f
                )

                if (saveError != null) {
                    Text(saveError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isSaving) return@Button
                    isSaving = true
                    saveError = null
                    scope.launch {
                        val startMs = (trimStart * durationMs).toLong()
                        val endMs = (trimEnd * durationMs).toLong()
                        val outName = file.nameWithoutExtension + "_cut"
                        val result = withContext(Dispatchers.IO) {
                            AudioTrimmer.trimAndSave(context, file, startMs, endMs, outName)
                        }
                        isSaving = false
                        result.fold(
                            onSuccess = {
                                onSaved(it)
                                player?.pause()
                                onDismiss()
                            },
                            onFailure = { saveError = it.message }
                        )
                    }
                },
                enabled = !isSaving && durationMs > 0
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Сохранить в Download/AudioShow")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                player?.pause()
                onDismiss()
            }) { Text("Закрыть") }
        }
    )
}
