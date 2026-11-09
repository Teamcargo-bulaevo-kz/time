package kz.teamcargo.time

import android.app.TimePickerDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.roundToInt

private data class TimeAudioFile(val name: String, val mimeType: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private lateinit var store: AlarmStore
    private lateinit var scheduler: AlarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        scheduler = AlarmScheduler(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            var selectedPalette by remember {
                mutableStateOf(getSharedPreferences("time_ui", MODE_PRIVATE).getString("palette", "red") ?: "red")
            }
            TimeTheme(selectedPalette) {
                SideEffect {
                    val bar = when (selectedPalette) {
                        "blue" -> android.graphics.Color.rgb(11, 17, 25)
                        "green" -> android.graphics.Color.rgb(13, 19, 15)
                        else -> android.graphics.Color.rgb(16, 13, 14)
                    }
                    window.statusBarColor = bar
                    window.navigationBarColor = bar
                    @Suppress("DEPRECATION")
                    run { window.decorView.systemUiVisibility = 0 }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
                }
                val alarms = remember { mutableStateListOf<Alarm>().apply { addAll(store.getAll()) } }
                var expandedAlarmId by remember { mutableStateOf<String?>(null) }
                var showRepeatFor by remember { mutableStateOf<Alarm?>(null) }
                var showLabelFor by remember { mutableStateOf<Alarm?>(null) }
                var showSoundFor by remember { mutableStateOf<Alarm?>(null) }
                var showRetryFor by remember { mutableStateOf<Alarm?>(null) }
                var showChallengeFor by remember { mutableStateOf<Alarm?>(null) }
                var showVisualFor by remember { mutableStateOf<Alarm?>(null) }
                var pickingMusicFor by remember { mutableStateOf<Alarm?>(null) }
                var musicFolderUri by remember {
                    mutableStateOf(getSharedPreferences("time_music", MODE_PRIVATE).getString("folder_uri", null)?.let(Uri::parse))
                }
                var folderSongs by remember { mutableStateOf<List<TimeAudioFile>>(musicFolderUri?.let { loadTimeAudioFiles(it) } ?: emptyList()) }
                var showFolderSongsFor by remember { mutableStateOf<Alarm?>(null) }
                var showSleepJournal by remember { mutableStateOf(false) }
                var showSleepCalculator by remember { mutableStateOf(false) }
                var showSleepStats by remember { mutableStateOf(false) }
                var showThemePicker by remember { mutableStateOf(false) }
                val sleepJournalStore = remember { SleepJournalStore(this@MainActivity) }
                var pendingFlashAlarmId by remember { mutableStateOf<String?>(null) }

                fun persist(updated: List<Alarm>) {
                    store.saveAll(updated)
                    scheduler.scheduleAll(updated)
                    alarms.clear()
                    alarms.addAll(updated)
                }

                val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    val alarmId = pendingFlashAlarmId
                    if (granted && alarmId != null) {
                        persist(alarms.map { if (it.id == alarmId) it.copy(flashEnabled = true) else it })
                    }
                    pendingFlashAlarmId = null
                }

                val musicFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        musicFolderUri = uri
                        folderSongs = loadTimeAudioFiles(uri)
                        getSharedPreferences("time_music", MODE_PRIVATE).edit().putString("folder_uri", uri.toString()).apply()
                    }
                }

                val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data
                    val target = pickingMusicFor
                    if (result.resultCode == RESULT_OK && uri != null && target != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        persist(alarms.map { if (it.id == target.id) it.copy(soundProfile = "custom", customSoundUri = uri.toString()) else it })
                    }
                    pickingMusicFor = null
                }

                fun chooseTime(existing: Alarm?) {
                    val now = Calendar.getInstance()
                    TimePickerDialog(
                        this,
                        { _, hour, minute ->
                            val next = if (existing == null) Alarm(hour = hour, minute = minute)
                                else existing.copy(hour = hour, minute = minute)
                            val updated = if (existing == null) alarms.toList() + next
                                else alarms.map { if (it.id == existing.id) next else it }
                            persist(updated)
                            requestExactAlarmPermissionIfNeeded()
                        },
                        existing?.hour ?: now.get(Calendar.HOUR_OF_DAY),
                        existing?.minute ?: now.get(Calendar.MINUTE),
                        true
                    ).show()
                }

                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(onClick = { chooseTime(null) }) { Text("+") }
                    }
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding)) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("TIME", style = MaterialTheme.typography.labelLarge)
                            Text("Будильники", style = MaterialTheme.typography.headlineMedium)
                            TextButton(onClick = { showThemePicker = true }) { Text("Цвет интерфейса · " + when (selectedPalette) { "blue" -> "Midnight Blue"; "green" -> "Forest Night"; else -> "Graphite Red" }) }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(onClick = { showSleepJournal = true }, modifier = Modifier.weight(1f)) { Text("Дневник сна") }
                                    Button(onClick = { showSleepCalculator = true }, modifier = Modifier.weight(1f)) { Text("Фазы сна") }
                                }
                                Button(onClick = { showSleepStats = true }, modifier = Modifier.fillMaxWidth()) { Text("Статистика сна") }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Моя музыкальная папка", style = MaterialTheme.typography.titleSmall)
                                    Text(if (musicFolderUri == null) "Папка не выбрана. Создай или выбери TIME во внутренней памяти." else "Папка подключена. Добавляй туда MP3 и другие аудиофайлы.", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { musicFolderPicker.launch(musicFolderUri) }) { Text(if (musicFolderUri == null) "Выбрать папку TIME" else "Изменить папку") }
                                        if (musicFolderUri != null) {
                                            TextButton(onClick = { folderSongs = loadTimeAudioFiles(musicFolderUri!!) }) { Text("Обновить список") }
                                            TextButton(onClick = { musicFolderUri = null; folderSongs = emptyList(); getSharedPreferences("time_music", MODE_PRIVATE).edit().remove("folder_uri").apply() }) { Text("Отключить") }
                                        }
                                    }
                                    if (musicFolderUri != null) {
                                        Text("Аудиофайлы: ${folderSongs.size}", style = MaterialTheme.typography.labelMedium)
                                        folderSongs.take(5).forEach { audio ->
                                            Text(audio.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                        }
                                        if (folderSongs.size > 5) Text("И ещё ${folderSongs.size - 5}…", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (Build.VERSION.SDK_INT >= 34 &&
                                !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                            ) {
                                Text("Для открытия будильника поверх экрана блокировки разрешите полноэкранные уведомления.")
                                TextButton(onClick = {
                                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:$packageName")))
                                }) { Text("Разрешить полноэкранный будильник") }
                            }

                            if (alarms.isEmpty()) {
                                Text("Пока нет будильников. Нажмите +, чтобы добавить первый.",
                                    style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    alarms.forEach { alarm ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(alarm.timeText(), style = MaterialTheme.typography.headlineLarge)
                                                    Text(
                                                        alarm.label.ifBlank { repeatLabel(alarm.repeatDays) },
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                                        TextButton(onClick = { chooseTime(alarm) }) { Text("Время") }
                                                        TextButton(onClick = { showRepeatFor = alarm }) { Text("Повтор") }
                                                        TextButton(onClick = { expandedAlarmId = if (expandedAlarmId == alarm.id) null else alarm.id }) {
                                                            Text(if (expandedAlarmId == alarm.id) "Свернуть" else "Настройки")
                                                        }
                                                    }
                                                    if (expandedAlarmId == alarm.id) {
                                                        TextButton(onClick = { showLabelFor = alarm }) { Text(if (alarm.label.isBlank()) "Добавить название" else "Название: ${alarm.label}") }
                                                        TextButton(onClick = { showSoundFor = alarm }) { Text("Звук: " + soundLabel(alarm.soundProfile)) }
                                                        TextButton(onClick = { showRetryFor = alarm }) { Text("Повтор проверки: ${alarm.retryMinutes} мин") }
                                                        TextButton(onClick = { showChallengeFor = alarm }) { Text("Задание: " + challengeLabel(alarm.challengeMode)) }
                                                        TextButton(onClick = { showVisualFor = alarm }) { Text("Экран: " + visualStyleLabel(alarm.wakeVisualStyle)) }
                                                        TextButton(onClick = { showFolderSongsFor = alarm }) { Text("Музыка из папки TIME") }
                                                        TextButton(onClick = { pickingMusicFor = alarm; musicPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                                            addCategory(Intent.CATEGORY_OPENABLE)
                                                            type = "audio/*"
                                                            musicFolderUri?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
                                                        }) }) { Text("Выбрать отдельный файл") }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Вспышка", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                                            Switch(checked = alarm.flashEnabled, onCheckedChange = { enabled ->
                                                                if (enabled && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                                                    pendingFlashAlarmId = alarm.id
                                                                    cameraPermission.launch(Manifest.permission.CAMERA)
                                                                } else persist(alarms.map { if (it.id == alarm.id) it.copy(flashEnabled = enabled) else it })
                                                            })
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text("Вибрация", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                                            Switch(checked = alarm.vibrationEnabled, onCheckedChange = { enabled ->
                                                                persist(alarms.map { if (it.id == alarm.id) it.copy(vibrationEnabled = enabled) else it })
                                                            })
                                                        }
                                                        TextButton(onClick = {
                                                            scheduler.cancel(alarm.id)
                                                            persist(alarms.filterNot { it.id == alarm.id })
                                                            expandedAlarmId = null
                                                        }) { Text("Удалить будильник") }
                                                    }
                                                }
                                                Switch(
                                                    checked = alarm.enabled,
                                                    onCheckedChange = { enabled ->
                                                        persist(alarms.map { if (it.id == alarm.id) it.copy(enabled = enabled) else it })
                                                        if (enabled) requestExactAlarmPermissionIfNeeded()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                "Свет и вибрация включаются отдельно для каждого будильника. Вспышка требует разрешения камеры.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (showThemePicker) {
                    AlertDialog(
                        onDismissRequest = { showThemePicker = false },
                        title = { Text("Ночная цветовая тема") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("red" to "Graphite Red · графит и приглушённый красный", "blue" to "Midnight Blue · тёмно-синий и стальной", "green" to "Forest Night · угольный и мягкий зелёный").forEach { option ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedPalette == option.first, onClick = {
                                            selectedPalette = option.first
                                            getSharedPreferences("time_ui", MODE_PRIVATE).edit().putString("palette", option.first).apply()
                                        })
                                        Text(option.second, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showThemePicker = false }) { Text("Готово") } }
                    )
                }

                showFolderSongsFor?.let { target ->
                    AlertDialog(
                        onDismissRequest = { showFolderSongsFor = null },
                        title = { Text("Музыка из TIME") },
                        text = {
                            if (folderSongs.isEmpty()) {
                                Text("В папке пока нет аудиофайлов. Добавь песни и нажми «Обновить список».")
                            } else {
                                Column {
                                    folderSongs.forEach { audio ->
                                        TextButton(onClick = {
                                            persist(alarms.map { if (it.id == target.id) it.copy(soundProfile = "custom", customSoundUri = audio.uri.toString()) else it })
                                            showFolderSongsFor = null
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(audio.name, maxLines = 1)
                                                Text(audio.mimeType, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showFolderSongsFor = null }) { Text("Закрыть") } }
                    )
                }

                if (showSleepJournal) {
                    SleepJournalDialog(store = sleepJournalStore, onDismiss = { showSleepJournal = false })
                }
                if (showSleepCalculator) {
                    SleepPhaseCalculatorDialog(onDismiss = { showSleepCalculator = false })
                }
                if (showSleepStats) {
                    SleepStatsDialog(context = this@MainActivity, onDismiss = { showSleepStats = false })
                }

                showLabelFor?.let { selected ->
                    var label by remember(selected.id) { mutableStateOf(selected.label) }
                    AlertDialog(
                        onDismissRequest = { showLabelFor = null },
                        title = { Text("Название будильника") },
                        text = {
                            OutlinedTextField(value = label, onValueChange = { label = it.take(40) },
                                label = { Text("Например, Работа") }, singleLine = true)
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map { if (it.id == selected.id) it.copy(label = label.trim()) else it })
                                showLabelFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = { TextButton(onClick = { showLabelFor = null }) { Text("Отмена") } }
                    )
                }
                showRepeatFor?.let { selected ->
                    val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                    var days by remember(selected.id) { mutableStateOf(selected.repeatDays) }
                    AlertDialog(
                        onDismissRequest = { showRepeatFor = null },
                        title = { Text("Повторять") },
                        text = {
                            Column {
                                dayNames.forEachIndexed { index, name ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = (index + 1) in days,
                                            onCheckedChange = { checked ->
                                                days = if (checked) days + (index + 1) else days - (index + 1)
                                            }
                                        )
                                        Text(name)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map { if (it.id == selected.id) it.copy(repeatDays = days) else it })
                                showRepeatFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRepeatFor = null }) { Text("Отмена") }
                        }
                    )
                }

                showChallengeFor?.let { selected ->
                    var mode by remember(selected.id) { mutableStateOf(selected.challengeMode) }
                    AlertDialog(
                        onDismissRequest = { showChallengeFor = null },
                        title = { Text("Задание для пробуждения") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("schulte" to "Таблица Шульте", "math" to "Математика", "shake" to "Встряхнуть телефон").forEach { option ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = mode == option.first, onClick = { mode = option.first })
                                        Text(option.second)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map { if (it.id == selected.id) it.copy(challengeMode = mode) else it })
                                showChallengeFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = { TextButton(onClick = { showChallengeFor = null }) { Text("Отмена") } }
                    )
                }

                showRetryFor?.let { selected ->
                    var retry by remember(selected.id) { mutableStateOf(selected.retryMinutes.toFloat()) }
                    AlertDialog(
                        onDismissRequest = { showRetryFor = null },
                        title = { Text("Повтор проверки") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Повторить сигнал через ${retry.roundToInt()} мин, если пробуждение не подтверждено.")
                                Slider(value = retry, onValueChange = { retry = it }, valueRange = 1f..30f, steps = 28)
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map { if (it.id == selected.id) it.copy(retryMinutes = retry.roundToInt()) else it })
                                showRetryFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = { TextButton(onClick = { showRetryFor = null }) { Text("Отмена") } }
                    )
                }

                showVisualFor?.let { selected ->
                    var style by remember(selected.id) { mutableStateOf(selected.wakeVisualStyle) }
                    AlertDialog(
                        onDismissRequest = { showVisualFor = null },
                        title = { Text("Визуализация пробуждения") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "sunrise" to "Рассвет — плавное золотистое свечение",
                                    "pulse" to "Пульсация — мягкий световой ритм",
                                    "waves" to "Волны — движущиеся световые кольца"
                                ).forEach { option ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = style == option.first, onClick = { style = option.first })
                                        Text(option.second)
                                    }
                                }
                                Text("Без резких вспышек. Яркость экрана нарастает постепенно.",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map { if (it.id == selected.id) it.copy(wakeVisualStyle = style) else it })
                                showVisualFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = { TextButton(onClick = { showVisualFor = null }) { Text("Отмена") } }
                    )
                }

                showSoundFor?.let { selected ->
                    var profile by remember(selected.id) { mutableStateOf(selected.soundProfile) }
                    var ramp by remember(selected.id) { mutableStateOf(selected.rampMinutes.toFloat()) }
                    AlertDialog(
                        onDismissRequest = { showSoundFor = null },
                        title = { Text("Звук и нарастание") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("rain" to "Дождь", "storm" to "Гроза", "system" to "Системный сигнал", "custom" to "Моя музыка").forEach { option ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = profile == option.first, onClick = { profile = option.first })
                                        Text(option.second)
                                    }
                                }
                                Text("Время нарастания: ${ramp.roundToInt()} мин")
                                Slider(
                                    value = ramp,
                                    onValueChange = { ramp = it },
                                    valueRange = 0f..30f,
                                    steps = 29
                                )
                                Text("0 минут — сразу полная громкость", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map {
                                    if (it.id == selected.id) it.copy(soundProfile = profile, rampMinutes = ramp.roundToInt()) else it
                                })
                                showSoundFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSoundFor = null }) { Text("Отмена") }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && ::scheduler.isInitialized) scheduler.scheduleAll(store.getAll())
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            }
        }
    }

    private fun loadTimeAudioFiles(treeUri: Uri): List<TimeAudioFile> {
        return runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val result = mutableListOf<TimeAudioFile>()
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeColumn) ?: ""
                    if (mime.startsWith("audio/")) {
                        val documentId = cursor.getString(idColumn)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        result += TimeAudioFile(cursor.getString(nameColumn) ?: "Аудиофайл", mime, uri)
                    }
                }
            }
            result.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun challengeLabel(mode: String): String = when (mode) {
        "math" -> "Математика"
        "shake" -> "Встряхнуть телефон"
        else -> "Шульте"
    }

    private fun visualStyleLabel(style: String): String = when (style) {
        "pulse" -> "Пульсация"
        "waves" -> "Волны"
        else -> "Рассвет"
    }

    private fun soundLabel(profile: String): String = when (profile) {
        "storm" -> "Гроза"
        "system" -> "Системный сигнал"
        "custom" -> "Моя музыка"
        else -> "Дождь"
    }

    private fun repeatLabel(days: Set<Int>): String = when {
        days.isEmpty() -> "Один раз"
        days.size == 7 -> "Каждый день"
        days == setOf(1, 2, 3, 4, 5) -> "По будням"
        days == setOf(6, 7) -> "По выходным"
        else -> "Повтор: " + days.sorted().joinToString(", ") {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1]
        }
    }
}
