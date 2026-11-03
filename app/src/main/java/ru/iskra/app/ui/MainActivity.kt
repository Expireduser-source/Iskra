package ru.iskra.app.ui

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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ru.iskra.app.data.ProviderPreset
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
        setContent { IskraTheme { MainScreen() } }
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
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        when (tab) {
            0 -> ChatScreen(m)
            1 -> MemoryScreen(m)
            2 -> SettingsScreen(m)
            else -> PermissionsScreen(m)
        }
    }
}

@Composable
private fun MemoryScreen(modifier: Modifier) {
    val app = IskraApp.instance
    var items by remember { mutableStateOf(app.memory.all()) }
    var confirmWipe by remember { mutableStateOf(false) }
    fun refresh() { items = app.memory.all() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Что Искра помнит о тебе", fontSize = 20.sp)
        Text(
            "Это обычные записи на телефоне, а не «обучение нейросети». Всё можно удалить. " +
                "📌 — ты попросил запомнить, 🤖 — предложила модель, 📖 — сжатый пересказ разговоров.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        Spacer(Modifier.size(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.sortedByDescending { it.createdAtMs }, key = { it.id }) { item -> MemoryRow(item) { app.memory.delete(item.id); refresh() } }
        }
        Row {
            OutlinedButton(onClick = { app.chat.clearHistory() }) { Text("Очистить переписку") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { confirmWipe = true }) { Text("Стереть всё") }
        }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Стереть все данные?") },
        text = { Text("Будут удалены память, переписка, настроение и ключ API. Это необратимо.") },
        confirmButton = { TextButton(onClick = { app.wipeAllData(); refresh(); confirmWipe = false }) { Text("Стереть") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Отмена") } },
    )
}

@Composable
private fun MemoryRow(item: MemoryItem, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val badge = when {
                item.kind == MemoryKind.EPISODE -> "📖"
                item.source == MemorySource.USER_EXPLICIT -> "📌"
                else -> "🤖"
            }
            Text("$badge ${item.text}", Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("✕") }
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

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Мозг Искры", fontSize = 20.sp)
        Text(
            "Ключ API хранится зашифрованным (Android Keystore) и отправляется только выбранному сервису. " +
                "Сообщения, релевантные заметки памяти и настроение уходят в облако только когда ты пишешь. " +
                "Команды телефона и «запомни» работают без облака.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderPreset.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.preset == p, onClick = { s = s.copy(preset = p, baseUrl = p.baseUrl, model = p.defaultModel); saved = false })
                Text(p.title)
            }
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
    val access = remember(tick) { CompanionAccessibilityService.isEnabled(ctx) }
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
        if (overlay) Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = overlayOn, onCheckedChange = { on ->
                overlayOn = on
                app.settings.save(app.settings.load().copy(overlayEnabled = on))
                if (on) {
                    if (!notif && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    OverlayService.start(ctx)
                } else OverlayService.stop(ctx)
            })
            Spacer(Modifier.size(8.dp)); Text("Показать Искру на экране")
        }
        PermCard("Уведомления", "Android показывает значок, пока Искра на экране. Без него HyperOS чаще её выгружает.", notif) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermCard("Управление телефоном", "Специальные возможности → «Искра · управление». Даёт только «Домой», «Назад», «Недавние», шторку, блокировку и скриншот. " +
            if (DeviceHelper.isXiaomi) "На Xiaomi для APK не из магазина сначала: О приложении → ⋮ → «Разрешить ограниченные настройки»." else "", access) {
            DeviceHelper.openAccessibilitySettings(ctx)
        }
        PermCard("Работа в фоне", "Снимает ограничение батареи, чтобы Искра не исчезала.", battery) {
            DeviceHelper.openBatterySettings(ctx)
        }
        if (DeviceHelper.isXiaomi) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Xiaomi / HyperOS", fontSize = 16.sp)
                    Text("1) Автозапуск — включить. 2) Контроль активности → «Нет ограничений». " +
                        "3) Другие разрешения → «Всплывающие окна в фоне» и «Отображать на экране блокировки». " +
                        "4) В недавних приложениях потяни карточку Искры вниз и закрепи замком.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = { DeviceHelper.openXiaomiAutostart(ctx) }) { Text("Автозапуск") }
                        TextButton(onClick = { DeviceHelper.openXiaomiOtherPermissions(ctx) }) { Text("Другие разрешения") }
                        TextButton(onClick = { DeviceHelper.openAppDetails(ctx) }) { Text("О приложении") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermCard(title: String, text: String, granted: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (granted) "✅ " else "⬜ ") + title, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (!granted) TextButton(onClick = onOpen) { Text("Открыть") }
            }
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
