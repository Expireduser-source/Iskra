package ru.iskra.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.iskra.app.IskraApp
import ru.iskra.app.data.AppSettings
import ru.iskra.app.data.AvatarLook
import ru.iskra.app.diag.Diag
import ru.iskra.app.system.DeviceHelper
import ru.iskra.core.power.EconomyMode
import ru.iskra.app.data.TtsEngine
import ru.iskra.app.overlay.FrameStats
import ru.iskra.app.voice.SpeechModel
import ru.iskra.core.persona.Emotion

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 18.sp)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The whole row is the switch: one big touch target, and TalkBack reads the label with the state. */
@Composable
internal fun SwitchRow(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.size(8.dp)); Text(label)
    }
}

/** A radio choice whose label is part of the touch target and of the TalkBack announcement. */
@Composable
internal fun RadioRow(selected: Boolean, label: String, hint: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(label)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Neural voice selection. [persist] saves the whole settings form (needed before a voice test). */
@Composable
fun VoiceSection(s: AppSettings, onChange: (AppSettings) -> Unit, persist: () -> Unit) {
    val app = IskraApp.instance
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(app.ttsVault.hasKey()) }
    val error by app.speaker.lastError.collectAsState()
    var tested by remember { mutableStateOf(false) }

    Section("Голос") {
        SwitchRow(s.ttsEnabled, "Озвучивать ответы") { onChange(s.copy(ttsEnabled = it)) }
        TtsEngine.entries.forEach { e ->
            RadioRow(s.ttsEngine == e, e.title) { onChange(s.copy(ttsEngine = e, ttsVoice = "", ttsModel = "")) }
        }
        Hint(s.ttsEngine.note)
        if (s.ttsEngine == TtsEngine.SYSTEM && app.speaker.systemVoiceMissing) {
            Text("В телефоне нет русского голоса, поэтому Искра молчит. Установи его в настройках синтеза речи " +
                "(например, «Синтезатор речи Google» → русский).", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { DeviceHelper.openTtsSettings(ctx) }) { Text("Открыть настройки голоса") }
        }
        if (s.ttsEngine != TtsEngine.SYSTEM) {
            Hint("В сервис голоса уходит только текст ответа Искры — не твои сообщения и не память.")
            if (s.ttsEngine == TtsEngine.CUSTOM) OutlinedTextField(
                value = s.ttsBaseUrl, onValueChange = { onChange(s.copy(ttsBaseUrl = it)) },
                label = { Text("Адрес (https://…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.ttsVoice, onValueChange = { onChange(s.copy(ttsVoice = it)) },
                label = { Text("Голос" + if (s.ttsEngine.defaultVoice.isNotEmpty()) " (по умолчанию ${s.ttsEngine.defaultVoice})" else "") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (s.ttsEngine == TtsEngine.OPENAI || s.ttsEngine == TtsEngine.ELEVENLABS || s.ttsEngine == TtsEngine.CUSTOM) OutlinedTextField(
                value = s.ttsModel, onValueChange = { onChange(s.copy(ttsModel = it)) },
                label = { Text("Модель" + if (s.ttsEngine.defaultModel.isNotEmpty()) " (по умолчанию ${s.ttsEngine.defaultModel})" else "") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text(if (hasKey) "Ключ голоса сохранён — введи новый, чтобы заменить" else "Ключ сервиса голоса") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                if (key.isNotBlank()) TextButton(onClick = { app.ttsVault.save(key); key = ""; hasKey = true }) { Text("Сохранить ключ") }
                if (hasKey) TextButton(onClick = { app.ttsVault.clear(); hasKey = false }) { Text("Удалить ключ") }
            }
        }
        OutlinedButton(onClick = {
            if (key.isNotBlank()) { app.ttsVault.save(key); key = ""; hasKey = true }
            persist()
            tested = true
            app.speaker.speak("Мяу! Привет, это Искра. Так я теперь звучу. Нравится?", Emotion.HAPPY)
        }) { Text("▶ Проверить голос") }
        if (tested) {
            val e = error
            if (e != null) Text("Облачный голос не сработал, говорю системным: $e", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Always-on listening: model download, permission, wake words. */
@Composable
fun EarSection(s: AppSettings, onChange: (AppSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val model by SpeechModel.state.collectAsState()
    LaunchedEffect(Unit) { SpeechModel.refresh(ctx) }
    var micDenied by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onChange(s.copy(alwaysListen = true)) else micDenied = true
    }
    val ready = model is SpeechModel.State.Ready

    Section("Слух: отзывается на имя") {
        Hint("Искра постоянно слушает и реагирует, когда слышит своё имя: «Искра, поставь таймер на 5 минут» " +
            "или просто «Искра» — и дальше команда. Распознавание полностью на телефоне: звук никуда не отправляется " +
            "и не записывается. Пока она слушает, Android показывает зелёный значок микрофона. Расход батареи — " +
            "заметный (несколько процентов в час), поэтому при выключенном экране слух по умолчанию на паузе.")
        when (val m = model) {
            SpeechModel.State.Missing -> OutlinedButton(onClick = { scope.launch { SpeechModel.download(ctx) } }) {
                Text("Скачать модель распознавания (${SpeechModel.SIZE_MB} МБ)")
            }
            is SpeechModel.State.Downloading -> {
                Text("Скачиваю… ${(m.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
            }
            SpeechModel.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✅ Модель на телефоне", Modifier.weight(1f))
                TextButton(onClick = { onChange(s.copy(alwaysListen = false)); SpeechModel.delete(ctx) }) { Text("Удалить") }
            }
            is SpeechModel.State.Failed -> {
                Text("Не удалось скачать: ${m.message}", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                OutlinedButton(onClick = { scope.launch { SpeechModel.download(ctx) } }) { Text("Повторить") }
            }
        }
        SwitchRow(s.alwaysListen, "Слушать постоянно", enabled = ready || s.alwaysListen) { on ->
            if (!on) { onChange(s.copy(alwaysListen = false)); return@SwitchRow }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onChange(s.copy(alwaysListen = true))
            } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (micDenied) Text("Без доступа к микрофону слух не работает.", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        if (s.alwaysListen && !s.overlayEnabled) Hint("Слух работает, пока Искра на экране — включи её на вкладке «Разрешения».")
        OutlinedTextField(
            value = s.wakeNames, onValueChange = { onChange(s.copy(wakeNames = it.take(120))) },
            label = { Text("На какие имена отзываться (через запятую)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(s.followUp, "Продолжать разговор без имени (6 с после ответа)") { onChange(s.copy(followUp = it)) }
        SwitchRow(s.economicalEar, "Экономный слух: в тишине не распознавать, в ожидании слушать только имя") { onChange(s.copy(economicalEar = it)) }
        SwitchRow(s.listenScreenOff, "Слушать и при выключенном экране") { onChange(s.copy(listenScreenOff = it)) }
        SwitchRow(s.voiceInputOffline, "Микрофон в чате — тоже только на телефоне", enabled = ready || s.voiceInputOffline) {
            onChange(s.copy(voiceInputOffline = it))
        }
        Hint("Системное распознавание (обычно Google) может отправлять звук на сервер. С этим переключателем чат " +
            "распознаёт речь той же моделью, что и слух. На телефонах без сервисов Google так работает всегда.")
        Hint("Скажи «Искра, стоп», чтобы прервать ответ. Тап по Искре во время речи — тоже замолчит.")
    }
}

/** «Экономия», look, smoothness and how restless she is. */
@Composable
fun MotionSection(s: AppSettings, onChange: (AppSettings) -> Unit) {
    val ctx = LocalContext.current
    var hz by remember { mutableIntStateOf(FrameStats.hz) }
    var now by remember { mutableStateOf(Diag.economyState) }
    LaunchedEffect(Unit) { while (true) { hz = FrameStats.hz; now = Diag.economyState; delay(1000) } }
    val systemCalm = remember { DeviceHelper.animationsOff(ctx) }
    Section("Экономия") {
        EconomyMode.entries.forEach { m -> RadioRow(s.economy == m, m.title, m.hint) { onChange(s.copy(economy = m)) } }
        Hint("Сейчас: $now")
        Hint(if (hz > 0) "Экран отдаёт Искре ~$hz кадров/с." else "Частота появится, когда Искра будет на экране.")
        Hint("Телефон: ${DeviceHelper.deviceClass(ctx).title}. Расход батареи за час — в «Диагностике», строка «Расход в покое».")
    }
    Section("Движение") {
        Text("Вид")
        AvatarLook.entries.forEach { m -> RadioRow(s.avatarLook == m, m.title) { onChange(s.copy(avatarLook = m)) } }
        SwitchRow(s.calmMotion || systemCalm, "Спокойные движения (не гуляет по экрану)", enabled = !systemCalm) {
            onChange(s.copy(calmMotion = it))
        }
        if (systemCalm) Hint("В Android выключены анимации — Искра двигается спокойно, пока они выключены.")
        SwitchRow(s.wander, "Живёт своей жизнью (гуляет, прыгает, лазает)", enabled = !(s.calmMotion || systemCalm)) { onChange(s.copy(wander = it)) }
        if (s.wander && !(s.calmMotion || systemCalm)) {
            Text("Непоседливость")
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("спокойная", "обычная", "шустрая").forEachIndexed { i, label ->
                    Row(Modifier.weight(1f).selectable(s.liveliness == i, role = Role.RadioButton) { onChange(s.copy(liveliness = i)) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.liveliness == i, onClick = null)
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
            Hint("Прыгает, забирается на край экрана и висит, ползает по верхнему краю, убегает за экран и выглядывает. " +
                "Если бросить её в край — зацепится. В «Батарее» она всегда спокойная.")
        }
    }
}

/** Stage 3: weather city, proactive lines, exact reminders. */
@Composable
fun SkillsSection(s: AppSettings, onChange: (AppSettings) -> Unit) {
    val app = IskraApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf(app.settings.weatherPlace()) }
    var status by remember { mutableStateOf<String?>(null) }
    Section("Погода") {
        OutlinedTextField(
            value = s.weatherCity, onValueChange = { onChange(s.copy(weatherCity = it.take(80))); status = null },
            label = { Text("Твой город") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                status = "Ищу…"
                scope.launch {
                    status = try {
                        val p = ru.iskra.core.skills.WeatherClient().geocode(s.weatherCity)
                        app.settings.saveWeatherPlace(p); place = p
                        if (p == null) "Не нашла такой город" else "Нашла: ${p.name}"
                    } catch (e: Exception) { "Нет связи с сервисом погоды" }
                }
            }, enabled = s.weatherCity.isNotBlank()) { Text("Найти город") }
            Spacer(Modifier.size(8.dp))
            Text(status ?: place?.let { "Сейчас: ${it.name}" } ?: "Город не задан", fontSize = 13.sp)
        }
        Hint("«Искра, какая погода», «погода завтра», «нужен ли зонт». Данные: open-meteo.com — туда уходят только координаты города, без ключа и без доступа к местоположению.")
    }
    Section("Сама заговаривает") {
        ru.iskra.core.skills.ProactiveFrequency.entries.forEach { f ->
            RadioRow(s.proactive == f, f.title) { onChange(s.copy(proactive = f)) }
        }
        if (s.proactive != ru.iskra.core.skills.ProactiveFrequency.OFF) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тихие часы: с ")
                HourField(s.quietFrom) { onChange(s.copy(quietFrom = it)) }
                Text(" до ")
                HourField(s.quietTo) { onChange(s.copy(quietTo = it)) }
            }
            SwitchRow(s.proactiveVoice, "Говорить голосом (иначе только облачко)") { onChange(s.copy(proactiveVoice = it)) }
            Hint("Утром при первом включении экрана, когда ты вернулся после долгого перерыва, иногда вечером. Никогда — в тихие часы, при выключенном экране и чаще, чем выбрано. Голосом — только если не играет музыка.")
        }
    }
    Section("Напоминания") {
        val exact = app.reminderScheduler.exact
        Text(if (exact) "✅ Напоминания приходят точно в срок" else "⚠️ Android может прислать напоминание на несколько минут позже", fontSize = 14.sp)
        if (!exact && android.os.Build.VERSION.SDK_INT >= 31) {
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + ctx.packageName)))
                }
            }) { Text("Разрешить «Будильники и напоминания»") }
        }
        Hint("«Напомни через 10 минут позвонить маме», «напомни завтра в 9 про встречу», «какие напоминания». Переживают перезагрузку телефона. Список и удаление — во вкладке «Память».")
    }
}

@Composable
private fun HourField(value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v -> text = v.filter(Char::isDigit).take(2); text.toIntOrNull()?.takeIf { it in 0..23 }?.let(onValue) },
        singleLine = true, modifier = Modifier.size(width = 72.dp, height = 56.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
