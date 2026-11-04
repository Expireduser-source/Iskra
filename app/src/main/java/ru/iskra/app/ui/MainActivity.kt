package ru.iskra.app.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ru.iskra.app.IskraApp
import ru.iskra.app.data.AutoBackup
import ru.iskra.app.data.Backups
import ru.iskra.app.data.ProviderPreset
import ru.iskra.app.diag.Diag
import ru.iskra.app.diag.DiagCollector
import ru.iskra.core.backup.BackupException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import ru.iskra.app.overlay.OverlayService
import ru.iskra.app.system.CompanionAccessibilityService
import ru.iskra.app.system.DeviceHelper
import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemorySource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IskraTheme {
                var intro by remember { mutableStateOf(OnboardingState.needed(this)) }
                if (intro) Onboarding { OnboardingState.done(this); intro = false } else MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore the overlay if the owner enabled it and the permission is still granted.
        val s = IskraApp.instance.settings.load()
        if (s.overlayEnabled && DeviceHelper.canDrawOverlays(this)) OverlayService.start(this)
    }
}

@Composable
private fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("💬" to "Чат", "🧠" to "Память", "⚙️" to "Настройки", "🔐" to "Разрешения")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon, Modifier.clearAndSetSemantics {}) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        // Tablets and unfolded foldables: a readable column instead of lines across the whole screen.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                AccessibilityWarning()
                CrashBanner()
                val m = Modifier.weight(1f)
                when (tab) {
                    0 -> ChatScreen(m)
                    1 -> MemoryScreen(m)
                    2 -> SettingsScreen(m)
                    else -> PermissionsScreen(m)
                }
            }
        }
    }
}

/**
 * The switch in system settings is on, but the system is not running the service (seen on HyperOS
 * after an app update or when the app was killed in the background). Then "Домой"/"Назад"/screenshot
 * silently do nothing, so say it plainly and offer the fix: switch it off and on again.
 */
private var lastLoggedAccess: CompanionAccessibilityService.State? = null

/** Stage 5 crash journal: after a crash, offer the report once (the cause is in it, without keys or chat). */
@Composable
private fun CrashBanner() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("crash_seen", android.content.Context.MODE_PRIVATE) }
    val last = remember { Diag.crashTimes().maxOrNull() ?: 0L }
    var seen by remember { mutableStateOf(prefs.getLong("seen", 0L)) }
    if (last <= seen) return
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Искра аварийно закрылась " + java.text.SimpleDateFormat("dd.MM в HH:mm", java.util.Locale.ROOT).format(java.util.Date(last)), fontSize = 15.sp)
            Text("Причина записана в журнал. Отправь отчёт — так её проще найти. В нём нет ключей и переписки.", fontSize = 13.sp)
            Row {
                TextButton(onClick = { runCatching { DiagCollector.shareReport(ctx) }; prefs.edit().putLong("seen", last).apply(); seen = last }) { Text("📤 Отправить отчёт") }
                TextButton(onClick = { prefs.edit().putLong("seen", last).apply(); seen = last }) { Text("Понятно") }
            }
        }
    }
}

@Composable
private fun AccessibilityWarning() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var problem by remember { mutableStateOf<CompanionAccessibilityService.State?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Give the system a moment to bind the service after the app process starts.
            kotlinx.coroutines.delay(2000)
            val state = CompanionAccessibilityService.state(ctx)
            if (state != lastLoggedAccess) {
                lastLoggedAccess = state
                Diag.i("access", "Проверка при открытии: ${CompanionAccessibilityService.describe(ctx)}")
            }
            val now = state == CompanionAccessibilityService.State.ENABLED_NOT_CONNECTED ||
                state == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM
            if (now && problem != state) Diag.w("access", "Показано предупреждение: ${if (state == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM) "служба выключена, хотя раньше работала" else "включена, но не работает"} (${CompanionAccessibilityService.describe(ctx)})")
            problem = if (now) state else null
        }
    }
    val p = problem ?: return
    val turnedOff = p == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (turnedOff) "⚠️ Управление телефоном выключено" else "⚠️ Управление телефоном не работает", fontSize = 16.sp)
            Text(
                if (turnedOff) "После обновления приложения HyperOS выключила «Искра · управление» в спецвозможностях. " +
                    "Без неё не работают «домой», «назад», шторка, скриншот. Открой спецвозможности и включи её снова."
                else "«Искра · управление» включена в спецвозможностях, но Android её сейчас не запускает. " +
                    "Открой спецвозможности, выключи «Искра · управление» и включи снова." +
                    if (DeviceHelper.guide.any { it.button == "Автозапуск" }) " Чтобы не повторялось: включи Автозапуск для Искры." else "",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { DeviceHelper.openAccessibilitySettings(ctx) }) { Text("Открыть спецвозможности") }
                if (turnedOff) TextButton(onClick = {
                    CompanionAccessibilityService.setExpected(ctx, false)
                    Diag.i("access", "Владелец: управление телефоном не нужно, не напоминать")
                    problem = null
                }) { Text("Не нужно") }
                else if (DeviceHelper.guide.any { it.button == "Автозапуск" }) TextButton(onClick = { DeviceHelper.openAutostart(ctx) }) { Text("Автозапуск") }
            }
        }
    }
}

@Composable
private fun MemoryScreen(modifier: Modifier) {
    val app = IskraApp.instance
    val clearing by app.chat.clearing.collectAsState()
    var items by remember { mutableStateOf(app.memory.all()) }
    var notes by remember { mutableStateOf(app.notes.all()) }
    var reminders by remember { mutableStateOf(app.reminders.pending()) }
    var confirmWipe by remember { mutableStateOf(false) }
    fun refresh() { items = app.memory.all(); notes = app.notes.all(); reminders = app.reminders.pending() }
    val timeFmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Backups.Preview?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null || app.chat.clearing.value) return@rememberLauncherForActivityResult
        scope.launch {
            backupMsg = try {
                val n = Backups.export(ctx, uri)
                "Сохранено: $n записей памяти и настройки. Ключей API в файле нет."
            } catch (e: Exception) {
                Diag.e("backup", "Экспорт не удался", e)
                "Не удалось сохранить: ${(e as? BackupException)?.userMessage ?: e.javaClass.simpleName}"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || app.chat.clearing.value) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                preview = Backups.read(ctx, uri)
                backupMsg = null
            } catch (e: Exception) {
                Diag.w("backup", "Файл копии не принят", e)
                backupMsg = (e as? BackupException)?.userMessage ?: "Не удалось прочитать файл: ${e.javaClass.simpleName}"
            }
        }
    }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Что Искра помнит о тебе", fontSize = 20.sp)
        Text(
            "Это обычные записи на телефоне, а не «обучение нейросети». Всё можно удалить. " +
                "📌 — ты попросил запомнить, 🤖 — предложила модель, 📖 — сжатый пересказ разговоров.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Spacer(Modifier.size(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.sortedByDescending { it.createdAtMs }, key = { it.id }) { item ->
                MemoryRow(item, enabled = !clearing) {
                    app.memory.delete(item.id); refresh()
                    Diag.i("memory", "Запись удалена владельцем, осталось ${items.size}")
                }
            }
            item(key = "rem-h") { Text("⏰ Напоминания (${reminders.size})", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
            if (reminders.isEmpty()) item(key = "rem-e") { Text("«Напомни через 10 минут позвонить маме»", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(reminders, key = { "r" + it.id }) { r ->
                SimpleRow("${timeFmt.format(java.util.Date(r.atMs))} — ${r.text}", enabled = !clearing) {
                    app.reminderScheduler.cancel(r.id); app.reminders.delete(r.id); refresh()
                    Diag.i("remind", "Напоминание удалено владельцем, осталось ${reminders.size}")
                }
            }
            item(key = "note-h") { Text("📝 Заметки (${notes.size})", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
            if (notes.isEmpty()) item(key = "note-e") { Text("«Запиши заметку: купить молоко»", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(notes, key = { "n" + it.id }) { n ->
                SimpleRow(n.text, enabled = !clearing) {
                    app.notes.delete(n.id); refresh()
                    Diag.i("notes", "Заметка удалена владельцем, осталось ${notes.size}")
                }
            }
        }
        if (clearing) Text("Стираю данные…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        backupMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
        Row {
            OutlinedButton(enabled = !clearing, onClick = { exportLauncher.launch(Backups.suggestedName()) }) { Text("💾 Сохранить копию") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(enabled = !clearing, onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) { Text("📂 Восстановить") }
        }
        var autoState by remember { mutableStateOf(AutoBackup.describe(ctx)) }
        val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null || app.chat.clearing.value) return@rememberLauncherForActivityResult
            runCatching { AutoBackup.enable(ctx, uri) }.onFailure { backupMsg = "Папка не подходит: ${it.javaClass.simpleName}" }
            scope.launch { backupMsg = AutoBackup.runNow(ctx); autoState = AutoBackup.describe(ctx) }
        }
        Text("🗓 Ежедневная копия: $autoState", fontSize = 13.sp)
        Row {
            OutlinedButton(enabled = !clearing, onClick = { treeLauncher.launch(null) }) { Text(if (AutoBackup.folder(ctx) == null) "Включить: выбрать папку" else "Другая папка") }
            if (AutoBackup.folder(ctx) != null) {
                TextButton(enabled = !clearing, onClick = { scope.launch { backupMsg = AutoBackup.runNow(ctx); autoState = AutoBackup.describe(ctx) } }) { Text("Сейчас") }
                TextButton(enabled = !clearing, onClick = { AutoBackup.disable(ctx); autoState = AutoBackup.describe(ctx) }) { Text("Выключить") }
            }
        }
        Row {
            OutlinedButton(enabled = !clearing, onClick = { app.chat.clearHistory { Diag.i("memory", "Переписка очищена владельцем") } }) { Text("Очистить переписку") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(enabled = !clearing, onClick = { confirmWipe = true }) { Text("Стереть всё") }
        }
    }
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Восстановить из копии?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.lines.forEach { Text(it, fontSize = 14.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !clearing, onClick = {
                    preview = null
                    scope.launch {
                        val skipped = Backups.apply(p.backup)
                        refresh()
                        backupMsg = "Восстановлено." + if (skipped > 0) " Пропущено записей: $skipped (пароли, номера карт или слишком длинные)." else ""
                        val cur = app.settings.load()
                        if (cur.overlayEnabled && DeviceHelper.canDrawOverlays(ctx)) { OverlayService.stop(ctx); OverlayService.start(ctx) }
                    }
                }) { Text("Заменить") }
            },
            dismissButton = { TextButton(enabled = !clearing, onClick = { preview = null }) { Text("Отмена") } },
        )
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Стереть все данные?") },
        text = { Text("Будут удалены память, заметки, напоминания, переписка, настроение, ключи API, кэш голоса и журнал диагностики. Настройки и сохранённые файлы резервных копий останутся. Это необратимо.") },
        confirmButton = { TextButton(enabled = !clearing, onClick = { app.wipeAllData { refresh() }; confirmWipe = false }) { Text("Стереть") } },
        dismissButton = { TextButton(enabled = !clearing, onClick = { confirmWipe = false }) { Text("Отмена") } },
    )
}

@Composable
private fun MemoryRow(item: MemoryItem, enabled: Boolean = true, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val badge = when {
                item.kind == MemoryKind.EPISODE -> "📖"
                item.source == MemorySource.USER_EXPLICIT -> "📌"
                else -> "🤖"
            }
            Text("$badge ${item.text}", Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Удалить: ${item.text}" }) {
                Text("✕", Modifier.clearAndSetSemantics {})
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    val app = IskraApp.instance
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(app.settings.load()) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(app.vault.hasKey()) }
    var saved by remember { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    if (diagnostics) { DiagnosticsScreen(modifier) { diagnostics = false }; return }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { diagnostics = true }, modifier = Modifier.fillMaxWidth()) { Text("🩺 Диагностика и отчёт об ошибках") }
        TextButton(onClick = { OnboardingState.again(ctx); (ctx as? android.app.Activity)?.recreate() }) { Text("Пройти знакомство заново") }
        Text("Мозг Искры", fontSize = 20.sp)
        Text(
            "Ключ API хранится зашифрованным (Android Keystore) и отправляется только выбранному сервису. " +
                "Сообщения, релевантные заметки памяти и настроение уходят в облако только когда ты пишешь. " +
                "Команды телефона и «запомни» работают без облака.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderPreset.entries.forEach { p ->
            RadioRow(s.preset == p, p.title) { s = s.copy(preset = p, baseUrl = p.baseUrl, model = p.defaultModel); saved = false }
        }
        if (s.preset != ProviderPreset.OFFLINE) {
            if (s.preset == ProviderPreset.CUSTOM) OutlinedTextField(
                value = s.baseUrl, onValueChange = { s = s.copy(baseUrl = it); saved = false },
                label = { Text("Адрес API (https://…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.model, onValueChange = { s = s.copy(model = it); saved = false },
                label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it; saved = false },
                label = { Text(if (hasKey) "Ключ API сохранён — введи новый, чтобы заменить" else "Ключ API") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasKey) TextButton(onClick = { app.vault.clear(); hasKey = false }) { Text("Удалить сохранённый ключ") }
        }
        if (s.preset == ProviderPreset.OFFLINE) Text(
            "Сейчас у Искры нет «мозга»: она понимает только команды телефона. Чтобы она нормально разговаривала, " +
                "выбери сервис и вставь ключ. Дешевле всего DeepSeek (доли копейки за реплику, оплата картой) " +
                "или OpenRouter (много моделей через один ключ).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary,
        )
        VoiceSection(s, onChange = { s = it; saved = false }, persist = { app.settings.save(s.copy(overlayEnabled = app.settings.load().overlayEnabled)) })
        EarSection(s, onChange = { s = it; saved = false })
        MotionSection(s, onChange = { s = it; saved = false })
        SkillsSection(s, onChange = { s = it; saved = false })
        Text("Размер персонажа: ${s.avatarSizeDp} dp")
        Slider(value = s.avatarSizeDp.toFloat(), onValueChange = { s = s.copy(avatarSizeDp = it.toInt()); saved = false }, valueRange = 72f..220f)
        Button(onClick = {
            if (s.preset == ProviderPreset.CUSTOM && !s.baseUrl.startsWith("https://")) {
                s = s.copy(baseUrl = "https://")
                return@Button
            }
            // overlayEnabled is owned by the Permissions tab; never overwrite it from this form.
            s = s.copy(overlayEnabled = app.settings.load().overlayEnabled)
            app.settings.save(s)
            if (key.isNotBlank()) { app.vault.save(key); key = ""; hasKey = true }
            app.agent.resumeCloud() // new key or service: try the cloud again right away
            saved = true
            // Apply size/motion/listening changes to a running overlay.
            val cur = app.settings.load()
            if (cur.overlayEnabled && DeviceHelper.canDrawOverlays(ctx)) { OverlayService.stop(ctx); OverlayService.start(ctx) }
        }) { Text("Сохранить") }
        if (saved) Text("Сохранено ✓", color = MaterialTheme.colorScheme.primary)
        else Text("Изменения применятся после «Сохранить».", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.preset == ProviderPreset.CUSTOM) Text("Нужен HTTPS. Обычный http запрещён ради защиты ключа.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionsScreen(modifier: Modifier) {
    val app = IskraApp.instance
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableIntStateOf(0) }
    // Re-check permissions every time the owner comes back from system settings.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val overlay = remember(tick) { DeviceHelper.canDrawOverlays(ctx) }
    // "Granted" only when the system actually runs it; enabled-but-not-running shows as not done.
    val access = remember(tick) { CompanionAccessibilityService.state(ctx) == CompanionAccessibilityService.State.CONNECTED }
    val battery = remember(tick) { DeviceHelper.isIgnoringBatteryOptimizations(ctx) }
    val notif = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    var overlayOn by remember { mutableStateOf(app.settings.load().overlayEnabled) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Разрешения", fontSize = 20.sp)
        Text("Каждое разрешение необязательно и отключается в любой момент. Искра не читает экран, SMS, контакты и файлы.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        PermCard("Поверх других приложений", "Нужно, чтобы Искра жила на экране, а не только в приложении.", overlay) {
            DeviceHelper.openOverlaySettings(ctx)
        }
        if (overlay) SwitchRow(overlayOn, "Показать Искру на экране") { on ->
            overlayOn = on
            app.settings.save(app.settings.load().copy(overlayEnabled = on))
            if (on) {
                if (!notif && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                OverlayService.start(ctx)
            } else OverlayService.stop(ctx)
        }
        PermCard("Уведомления", "Android показывает значок, пока Искра на экране. Без него HyperOS чаще её выгружает.", notif) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermCard("Управление телефоном", "Специальные возможности → «Искра · управление». Даёт только «Домой», «Назад», «Недавние», шторку, блокировку и скриншот. " +
            if (Build.VERSION.SDK_INT >= 33) "Если переключатель серый: О приложении → ⋮ → «Разрешить ограниченные настройки» (Android так защищает APK не из магазина)." else "", access) {
            DeviceHelper.openAccessibilitySettings(ctx)
        }
        PermCard("Работа в фоне", "Снимает ограничение батареи, чтобы Искра не исчезала.", battery) {
            DeviceHelper.openBatterySettings(ctx)
        }
        VendorCard()
    }
}

/** What this phone's maker needs switched on so the firmware does not unload Iskra (core VendorGuide). */
@Composable
private fun VendorCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Чтобы ${DeviceHelper.vendor.title} не выгружал Искру", fontSize = 16.sp)
            DeviceHelper.guide.forEachIndexed { i, step ->
                Text("${i + 1}. ${step.text}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                step.button?.let { b -> TextButton(onClick = { DeviceHelper.open(ctx, step) }) { Text(b) } }
            }
            TextButton(onClick = { DeviceHelper.openAppDetails(ctx) }) { Text("О приложении") }
        }
    }
}

@Composable
private fun PermCard(title: String, text: String, granted: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (granted) "✅ " else "⬜ ") + title, fontSize = 16.sp, modifier = Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = title; stateDescription = if (granted) "разрешено" else "не разрешено"
                })
                if (!granted) TextButton(onClick = onOpen) { Text("Открыть") }
            }
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SimpleRow(text: String, enabled: Boolean = true, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 14.sp)
            TextButton(enabled = enabled, onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Удалить: $text" }) {
                Text("✕", Modifier.clearAndSetSemantics {})
            }
        }
    }
}
