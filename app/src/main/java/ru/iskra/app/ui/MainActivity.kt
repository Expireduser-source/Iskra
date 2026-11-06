package ru.iskra.app.ui

import ru.iskra.core.i18n.tr
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableLongStateOf
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
    /** A tab asked for by an intent (the tile or a notification opens «Разрешения»); counts new requests. */
    private var requestedTab = 0
    private var tabRequests by mutableIntStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) { requestedTab = intent.getIntExtra(EXTRA_TAB, 0); tabRequests++ }
        if (intent.data?.host == "connected") { requestedTab = TAB_SETTINGS; tabRequests++ }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_SETTINGS = 2
        const val TAB_PERMISSIONS = 3
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedTab = if (intent?.data?.host == "connected") TAB_SETTINGS else intent.getIntExtra(EXTRA_TAB, 0)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IskraTheme {
                // Test tags become resource ids, so the CI emulator check can find buttons in any language.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    var intro by remember { mutableStateOf(OnboardingState.needed(this@MainActivity)) }
                    if (intro) Onboarding { OnboardingState.done(this@MainActivity); intro = false }
                    else MainScreen(requestedTab, tabRequests)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore the overlay if the owner enabled it and the permission is still granted.
        val s = IskraApp.instance.settings.load()
        if (s.overlayEnabled && DeviceHelper.canDrawOverlays(this)) OverlayService.start(this)
        ru.iskra.app.data.Updates.maybeCheck(this)
    }
}

@Composable
private fun MainScreen(requestedTab: Int, tabRequests: Int) {
    var tab by rememberSaveable { mutableIntStateOf(requestedTab) }
    LaunchedEffect(tabRequests) { if (tabRequests > 0) tab = requestedTab }
    val tabs = listOf("💬" to tr("Чат", "Chat"), "🧠" to tr("Память", "Memory"), "⚙️" to tr("Настройки", "Settings"), "🔐" to tr("Разрешения", "Permissions"))
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, modifier = Modifier.testTag("tab_$i"), icon = { Text(icon, Modifier.clearAndSetSemantics {}) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        // Tablets and unfolded foldables: a readable column instead of lines across the whole screen.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                AccessibilityWarning()
                CrashBanner()
                UpdateBanner()
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
    var seen by remember { mutableLongStateOf(prefs.getLong("seen", 0L)) }
    if (last <= seen) return
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Искра аварийно закрылась ", "Iskra crashed ") + java.text.SimpleDateFormat(tr("dd.MM в HH:mm", "MMM d 'at' HH:mm"), tr(java.util.Locale("ru"), java.util.Locale.ENGLISH)).format(java.util.Date(last)), fontSize = 15.sp)
            Text(tr("Причина записана в журнал. Отправь отчёт — так её проще найти. В нём нет ключей и переписки.", "The cause is in the journal. Send the report — it makes it easier to find. It has no keys and no chat."), fontSize = 13.sp)
            Row {
                TextButton(onClick = { runCatching { DiagCollector.shareReport(ctx) }; prefs.edit().putLong("seen", last).apply(); seen = last }) { Text(tr("📤 Отправить отчёт", "📤 Send report")) }
                TextButton(onClick = { prefs.edit().putLong("seen", last).apply(); seen = last }) { Text(tr("Понятно", "Got it")) }
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
                Diag.i("access", tr("Проверка при открытии: ${CompanionAccessibilityService.describe(ctx)}", "Check on open: ${CompanionAccessibilityService.describe(ctx)}"))
            }
            val now = state == CompanionAccessibilityService.State.ENABLED_NOT_CONNECTED ||
                state == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM
            if (now && problem != state) Diag.w("access", tr("Показано предупреждение: ${if (state == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM) "служба выключена, хотя раньше работала" else "включена, но не работает"} (${CompanionAccessibilityService.describe(ctx)})", "Warning shown: ${if (state == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM) "service off although it worked before" else "on but not running"} (${CompanionAccessibilityService.describe(ctx)})"))
            problem = if (now) state else null
        }
    }
    val p = problem ?: return
    val turnedOff = p == CompanionAccessibilityService.State.TURNED_OFF_BY_SYSTEM
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (turnedOff) tr("⚠️ Управление телефоном выключено", "⚠️ Phone control is off") else tr("⚠️ Управление телефоном не работает", "⚠️ Phone control isn't working"), fontSize = 16.sp)
            Text(
                if (turnedOff) tr("После обновления приложения HyperOS выключила «Искра · управление» в спецвозможностях. " +
                    "Без неё не работают «домой», «назад», шторка, скриншот. Открой спецвозможности и включи её снова.", "After the app update the system turned off “Iskra · control” in Accessibility. Without it, “home”, “back”, the notification shade and screenshots don't work. Open Accessibility and turn it on again.")
                else tr("«Искра · управление» включена в спецвозможностях, но Android её сейчас не запускает. " +
                    "Открой спецвозможности, выключи «Искра · управление» и включи снова.", "“Iskra · control” is on in Accessibility, but Android isn't running it right now. Open Accessibility, turn “Iskra · control” off and on again.") +
                    if (DeviceHelper.guide.any { it.autostart }) tr(" Чтобы не повторялось: включи Автозапуск для Искры.", " So it doesn't happen again: turn on Autostart for Iskra.") else "",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { DeviceHelper.openAccessibilitySettings(ctx) }) { Text(tr("Открыть спецвозможности", "Open Accessibility")) }
                if (turnedOff) TextButton(onClick = {
                    CompanionAccessibilityService.setExpected(ctx, false)
                    Diag.i("access", tr("Владелец: управление телефоном не нужно, не напоминать", "Owner: phone control not needed, don't remind"))
                    problem = null
                }) { Text(tr("Не нужно", "Not needed")) }
                else if (DeviceHelper.guide.any { it.autostart }) TextButton(onClick = { DeviceHelper.openAutostart(ctx) }) { Text(tr("Автозапуск", "Autostart")) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
                tr("Сохранено: $n записей памяти и настройки. Ключей API в файле нет.", "Saved: $n memory entries and the settings. No API keys in the file.")
            } catch (e: Exception) {
                Diag.e("backup", tr("Экспорт не удался", "Export failed"), e)
                tr("Не удалось сохранить: ${(e as? BackupException)?.userMessage ?: e.javaClass.simpleName}", "Couldn't save: ${(e as? BackupException)?.userMessage ?: e.javaClass.simpleName}")
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
                Diag.w("backup", tr("Файл копии не принят", "Backup file rejected"), e)
                backupMsg = (e as? BackupException)?.userMessage ?: tr("Не удалось прочитать файл: ${e.javaClass.simpleName}", "Couldn't read the file: ${e.javaClass.simpleName}")
            }
        }
    }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(tr("Что Искра помнит о тебе", "What Iskra remembers about you"), fontSize = 20.sp)
        Text(
            tr("Это обычные записи на телефоне, а не «обучение нейросети». Всё можно удалить. " +
                "📌 — из твоих слов (имя, планы, что любишь…), 🤖 — Искра решила запомнить сама, 📖 — сжатый пересказ разговоров.", "These are plain entries on the phone, not “neural network training”. Everything can be deleted. 📌 — from your own words (name, plans, what you like…), 🤖 — Iskra decided to remember it, 📖 — a short recap of conversations."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Spacer(Modifier.size(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.sortedByDescending { it.createdAtMs }, key = { it.id }) { item ->
                MemoryRow(item, enabled = !clearing) {
                    app.memory.delete(item.id); refresh()
                    Diag.i("memory", tr("Запись удалена владельцем, осталось ${items.size}", "Entry deleted by the owner, ${items.size} left"))
                }
            }
            item(key = "rem-h") { Text(tr("⏰ Напоминания (${reminders.size})", "⏰ Reminders (${reminders.size})"), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
            if (reminders.isEmpty()) item(key = "rem-e") { Text(tr("«Напомни через 10 минут позвонить маме»", "“Remind me in 10 minutes to call mom”"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(reminders, key = { "r" + it.id }) { r ->
                SimpleRow("${timeFmt.format(java.util.Date(r.atMs))} — ${r.text}", enabled = !clearing) {
                    app.reminderScheduler.cancel(r.id); app.reminders.delete(r.id); refresh()
                    Diag.i("remind", tr("Напоминание удалено владельцем, осталось ${reminders.size}", "Reminder deleted by the owner, ${reminders.size} left"))
                }
            }
            item(key = "note-h") { Text(tr("📝 Заметки (${notes.size})", "📝 Notes (${notes.size})"), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
            if (notes.isEmpty()) item(key = "note-e") { Text(tr("«Запиши заметку: купить молоко»", "“Note: buy milk”"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(notes, key = { "n" + it.id }) { n ->
                SimpleRow(n.text, enabled = !clearing) {
                    app.notes.delete(n.id); refresh()
                    Diag.i("notes", tr("Заметка удалена владельцем, осталось ${notes.size}", "Note deleted by the owner, ${notes.size} left"))
                }
            }
        }
        if (clearing) Text(tr("Стираю данные…", "Erasing data…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        backupMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !clearing, onClick = { exportLauncher.launch(Backups.suggestedName()) }) { Text(tr("💾 Сохранить копию", "💾 Save a backup")) }
            OutlinedButton(enabled = !clearing, onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) { Text(tr("📂 Восстановить", "📂 Restore")) }
        }
        var autoState by remember { mutableStateOf(AutoBackup.describe(ctx)) }
        val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null || app.chat.clearing.value) return@rememberLauncherForActivityResult
            runCatching { AutoBackup.enable(ctx, uri) }.onFailure { backupMsg = tr("Папка не подходит: ${it.javaClass.simpleName}", "This folder doesn't work: ${it.javaClass.simpleName}") }
            scope.launch { backupMsg = AutoBackup.runNow(ctx); autoState = AutoBackup.describe(ctx) }
        }
        Text(tr("🗓 Ежедневная копия: $autoState", "🗓 Daily backup: $autoState"), fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !clearing, onClick = { treeLauncher.launch(null) }) { Text(if (AutoBackup.folder(ctx) == null) tr("Включить: выбрать папку", "Turn on: choose a folder") else tr("Другая папка", "Another folder")) }
            if (AutoBackup.folder(ctx) != null) {
                TextButton(enabled = !clearing, onClick = { scope.launch { backupMsg = AutoBackup.runNow(ctx); autoState = AutoBackup.describe(ctx) } }) { Text(tr("Сейчас", "Now")) }
                TextButton(enabled = !clearing, onClick = { AutoBackup.disable(ctx); autoState = AutoBackup.describe(ctx) }) { Text(tr("Выключить", "Turn off")) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !clearing, onClick = { app.chat.clearHistory { Diag.i("memory", tr("Переписка очищена владельцем", "Chat history cleared by the owner")) } }) { Text(tr("Очистить переписку", "Clear chat history")) }
            OutlinedButton(enabled = !clearing, onClick = { confirmWipe = true }) { Text(tr("Стереть всё", "Erase everything")) }
        }
    }
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(tr("Восстановить из копии?", "Restore from the backup?")) },
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
                        backupMsg = tr("Восстановлено.", "Restored.") + if (skipped > 0) tr(" Пропущено записей: $skipped (пароли, номера карт или слишком длинные).", " Skipped entries: $skipped (passwords, card numbers or too long).") else ""
                        val cur = app.settings.load()
                        if (cur.overlayEnabled && DeviceHelper.canDrawOverlays(ctx)) { OverlayService.stop(ctx); OverlayService.start(ctx) }
                    }
                }) { Text(tr("Заменить", "Replace")) }
            },
            dismissButton = { TextButton(enabled = !clearing, onClick = { preview = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text(tr("Стереть все данные?", "Erase all data?")) },
        text = { Text(tr("Будут удалены память, заметки, напоминания, переписка, настроение, ключи API, кэш голоса и журнал диагностики. Настройки и сохранённые файлы резервных копий останутся. Это необратимо.", "Memory, notes, reminders, chat, mood, API keys, the voice cache and the diagnostics journal will be deleted. Settings and saved backup files stay. This cannot be undone.")) },
        confirmButton = { TextButton(enabled = !clearing, onClick = { app.wipeAllData { refresh() }; confirmWipe = false }) { Text(tr("Стереть", "Erase")) } },
        dismissButton = { TextButton(enabled = !clearing, onClick = { confirmWipe = false }) { Text(tr("Отмена", "Cancel")) } },
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
            TextButton(enabled = enabled, onClick = onDelete, modifier = Modifier.semantics { contentDescription = tr("Удалить: ${item.text}", "Delete: ${item.text}") }) {
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
    val freeBrain by ru.iskra.app.data.FreeBrain.state.collectAsState()
    if (diagnostics) { DiagnosticsScreen(modifier) { diagnostics = false }; return }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { diagnostics = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("🩺 Диагностика и отчёт об ошибках", "🩺 Diagnostics and error report")) }
        TextButton(onClick = { OnboardingState.again(ctx); (ctx as? android.app.Activity)?.recreate() }) { Text(tr("Пройти знакомство заново", "Take the introduction again")) }
        LanguageSection()
        Text(tr("Мозг Искры", "Iskra's brain"), fontSize = 20.sp)
        Text(
            tr("Ключ API хранится зашифрованным (Android Keystore) и отправляется только выбранному сервису. " +
                "Сообщения, релевантные заметки памяти и настроение уходят в облако только когда ты пишешь. " +
                "Команды телефона и «запомни» работают без облака.", "The API key is stored encrypted (Android Keystore) and is sent only to the chosen service. Messages, relevant memory notes and the mood go to the cloud only when you write. Phone commands and “remember” work without the cloud."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.preset == ProviderPreset.OFFLINE || !hasKey || freeBrain !is ru.iskra.app.data.FreeBrain.State.Idle)
            FreeBrainCard(onConnected = { s = app.settings.load(); hasKey = app.vault.hasKey(); saved = true })
        LocalBrainCard()
        ProviderPreset.entries.forEach { p ->
            RadioRow(s.preset == p, p.title) { s = s.copy(preset = p, baseUrl = p.baseUrl, model = p.defaultModel); saved = false }
        }
        if (s.preset != ProviderPreset.OFFLINE) {
            if (s.preset == ProviderPreset.CUSTOM) OutlinedTextField(
                value = s.baseUrl, onValueChange = { s = s.copy(baseUrl = it); saved = false },
                label = { Text(tr("Адрес API (https://…/v1)", "API address (https://…/v1)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.model, onValueChange = { s = s.copy(model = it); saved = false },
                label = { Text(tr("Модель", "Model")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it; saved = false },
                label = { Text(if (hasKey) tr("Ключ API сохранён — введи новый, чтобы заменить", "API key saved — enter a new one to replace it") else tr("Ключ API", "API key")) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasKey) TextButton(onClick = { app.vault.clear(); hasKey = false }) { Text(tr("Удалить сохранённый ключ", "Delete the saved key")) }
        }
        if (s.preset == ProviderPreset.OFFLINE && ru.iskra.app.data.LocalBrain.provider() == null) Text(
            tr("Сейчас у Искры нет «мозга»: она понимает команды, счёт, даты и простую болтовню. Чтобы она нормально разговаривала, " +
                "подключи OpenRouter бесплатно кнопкой выше, скачай «мозг на телефон» — или выбери сервис и вставь свой ключ. Из платных дешевле всего DeepSeek " +
                "(доли копейки за реплику).", "Right now Iskra has no “brain”: she understands commands, sums, dates and simple small talk. To really chat with her, connect OpenRouter for free with the button above, download the brain on the phone — or choose a service and paste your own key. DeepSeek is the cheapest paid option (a fraction of a cent per reply)."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary,
        )
        VoiceSection(s, onChange = { s = it; saved = false }, persist = { app.settings.save(s.copy(overlayEnabled = app.settings.load().overlayEnabled)) })
        EarSection(s, onChange = { s = it; saved = false })
        MotionSection(s, onChange = { s = it; saved = false })
        SkillsSection(s, onChange = { s = it; saved = false })
        Text(tr("Размер персонажа: ${s.avatarSizeDp} dp", "Character size: ${s.avatarSizeDp} dp"))
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
        }) { Text(tr("Сохранить", "Save")) }
        if (saved) Text(tr("Сохранено ✓", "Saved ✓"), color = MaterialTheme.colorScheme.primary)
        else Text(tr("Изменения применятся после «Сохранить».", "Changes apply after “Save”."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.preset == ProviderPreset.CUSTOM) Text(tr("Нужен HTTPS. Обычный http запрещён ради защиты ключа.", "HTTPS is required. Plain http is not allowed, to protect the key."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        AboutSection()
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
        Text(tr("Разрешения", "Permissions"), fontSize = 20.sp)
        Text(tr("Каждое разрешение необязательно и отключается в любой момент. Искра не читает экран, SMS, контакты и файлы.", "Every permission is optional and can be turned off at any time. Iskra doesn't read the screen, SMS, contacts or files."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        PermCard(tr("Поверх других приложений", "Over other apps"), tr("Нужно, чтобы Искра жила на экране, а не только в приложении.", "Needed so Iskra can live on the screen, not only inside the app."), overlay) {
            DeviceHelper.openOverlaySettings(ctx)
        }
        if (overlay) SwitchRow(overlayOn, tr("Показать Искру на экране", "Show Iskra on screen"), tag = "overlay_switch") { on ->
            overlayOn = on
            app.settings.save(app.settings.load().copy(overlayEnabled = on))
            if (on) {
                if (!notif && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                OverlayService.start(ctx)
            } else OverlayService.stop(ctx)
        }
        PermCard(tr("Уведомления", "Notifications"), tr("Android показывает значок, пока Искра на экране. Без него HyperOS чаще её выгружает.", "Android shows an icon while Iskra is on screen. Without it the system unloads her more often."), notif) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermCard(tr("Управление телефоном", "Phone control"), tr("Специальные возможности → «Искра · управление». Даёт только «Домой», «Назад», «Недавние», шторку, блокировку и скриншот. ", "Accessibility → “Iskra · control”. Gives only Home, Back, Recents, the notification shade, lock and screenshot. ") +
            if (Build.VERSION.SDK_INT >= 33) tr("Если переключатель серый: О приложении → ⋮ → «Разрешить ограниченные настройки» (Android так защищает APK не из магазина).", "If the switch is grey: App info → ⋮ → “Allow restricted settings” (this is how Android protects apps installed outside a store).") else "", access) {
            DeviceHelper.openAccessibilitySettings(ctx)
        }
        PermCard(tr("Работа в фоне", "Background work"), tr("Снимает ограничение батареи, чтобы Искра не исчезала.", "Removes the battery restriction so Iskra doesn't disappear."), battery) {
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
            Text(tr("Чтобы ${DeviceHelper.vendor.title} не выгружал Искру", "So that ${DeviceHelper.vendor.title} doesn't unload Iskra"), fontSize = 16.sp)
            DeviceHelper.guide.forEachIndexed { i, step ->
                Text("${i + 1}. ${step.text}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                step.button?.let { b -> TextButton(onClick = { DeviceHelper.open(ctx, step) }) { Text(b) } }
            }
            TextButton(onClick = { DeviceHelper.openAppDetails(ctx) }) { Text(tr("О приложении", "App info")) }
        }
    }
}

@Composable
private fun PermCard(title: String, text: String, granted: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (granted) "✅ " else "⬜ ") + title, fontSize = 16.sp, modifier = Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = title; stateDescription = if (granted) tr("разрешено", "allowed") else tr("не разрешено", "not allowed")
                })
                if (!granted) TextButton(onClick = onOpen) { Text(tr("Открыть", "Open")) }
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
            TextButton(enabled = enabled, onClick = onDelete, modifier = Modifier.semantics { contentDescription = tr("Удалить: $text", "Delete: $text") }) {
                Text("✕", Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/** «Язык» / “Language”: the phone's language by default; the same value as Android 13+ per-app language. */
@Composable
private fun LanguageSection() {
    val ctx = LocalContext.current
    val current = remember { ru.iskra.app.data.AppLanguage.choice(ctx) }
    Text(tr("Язык · Language", "Language · Язык"), fontSize = 20.sp)
    listOf<Pair<ru.iskra.core.i18n.Lang?, String>>(
        null to tr("Как в телефоне", "Phone language"),
        ru.iskra.core.i18n.Lang.RU to "Русский",
        ru.iskra.core.i18n.Lang.EN to "English",
    ).forEach { (lang, label) ->
        RadioRow(current == lang, label) {
            if (current != lang) {
                Diag.i("app", tr("Язык: ", "Language: ") + (lang?.tag ?: "system"))
                ru.iskra.app.data.AppLanguage.set(ctx, lang, ctx as? android.app.Activity)
            }
        }
    }
}
