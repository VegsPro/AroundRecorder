package com.vegspro.aroundrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            // ready
        } else {
            Toast.makeText(this, "Нужны разрешения микрофона и уведомлений", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()
        setContent {
            AroundRecorderTheme {
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
fun AroundRecorderTheme(content: @Composable () -> Unit) {
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
    MaterialTheme(
        colorScheme = darkColors,
        typography = Typography(),
        content = content
    )
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

    fun refreshList() {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "AroundRecordings")
        if (dir.exists()) {
            recordings = dir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".m4a") || it.name.endsWith(".mp4")) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else {
            recordings = emptyList()
        }
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
        // Prompt for battery optimization
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
                        Text("Around Recorder", fontWeight = FontWeight.Bold)
                        Text(
                            "24/7 запись окружения · отрывки 15 мин",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
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
            // Status card
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
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

            // Auto-start switch
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

            Text(
                "Записи (${recordings.size})",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                "Папка: Android/data/com.vegspro.aroundrecorder/files/AroundRecordings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Пока нет записей",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordings, key = { it.absolutePath }) { file ->
                        RecordingItem(
                            file = file,
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

    // API Key dialog
    if (showApiKeyDialog) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API ключ") },
            text = {
                Column {
                    Text(
                        "Получите бесплатный ключ на aistudio.google.com/apikey\n\nКлюч хранится только на устройстве.",
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

    // Battery optimization dialog
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

    // Analysis result dialog
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
                Text(
                    if (isAnalyzing) "Анализ…" else "Результат ИИ",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                            item {
                                Text(analysisResult!!, style = MaterialTheme.typography.bodyMedium)
                            }
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
    onAnalyze: () -> Unit,
    onDelete: () -> Unit
) {
    val sizeMb = remember(file) { "%.1f МБ".format(file.length() / (1024.0 * 1024.0)) }
    val timeStr = remember(file) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$timeStr · $sizeMb",
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
