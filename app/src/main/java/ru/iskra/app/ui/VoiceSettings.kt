package ru.iskra.app.ui

import ru.iskra.core.i18n.tr
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** A settings section on a soft card, like the entries of «Память» (0.16.0). */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The whole row is the switch: one big touch target, and TalkBack reads the label with the state. */
@Composable
internal fun SwitchRow(checked: Boolean, label: String, enabled: Boolean = true, tag: String? = null, onChange: (Boolean) -> Unit) {
    // At least 48 dp high, the size a finger needs (the emulator check's audit found a 38-dp row, 0.16.1).
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.size(8.dp)); Text(label)
    }
}

/** A radio choice whose label is part of the touch target and of the TalkBack announcement. */
@Composable
internal fun RadioRow(selected: Boolean, label: String, hint: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
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

    Section(tr("Голос", "Voice")) {
        SwitchRow(s.ttsEnabled, tr("Озвучивать ответы", "Speak the replies")) { onChange(s.copy(ttsEnabled = it)) }
        TtsEngine.entries.forEach { e ->
            RadioRow(s.ttsEngine == e, e.title) { onChange(s.copy(ttsEngine = e, ttsVoice = "", ttsModel = "")) }
        }
        Hint(s.ttsEngine.note)
        if (s.ttsEngine == TtsEngine.SYSTEM && app.speaker.systemVoiceMissing) {
            Text(tr("В телефоне нет русского голоса, поэтому Искра молчит. Установи его в настройках синтеза речи " +
                "(например, «Синтезатор речи Google» → русский).", "The phone has no system voice for this language, so Iskra stays silent. Install one in the text-to-speech settings (for example, “Speech Services by Google” → English)."), fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { DeviceHelper.openTtsSettings(ctx) }) { Text(tr("Открыть настройки голоса", "Open voice settings")) }
        }
        if (s.ttsEngine != TtsEngine.SYSTEM) {
            Hint(tr("В сервис голоса уходит только текст ответа Искры — не твои сообщения и не память.", "Only the text of Iskra's reply goes to the voice service — not your messages and not the memory."))
            if (s.ttsEngine == TtsEngine.CUSTOM) OutlinedTextField(
                value = s.ttsBaseUrl, onValueChange = { onChange(s.copy(ttsBaseUrl = it)) },
                label = { Text(tr("Адрес (https://…/v1)", "Address (https://…/v1)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.ttsVoice, onValueChange = { onChange(s.copy(ttsVoice = it)) },
                label = { Text(tr("Голос", "Voice") + if (s.ttsEngine.defaultVoice.isNotEmpty()) tr(" (по умолчанию ${s.ttsEngine.defaultVoice})", " (default ${s.ttsEngine.defaultVoice})") else "") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (s.ttsEngine == TtsEngine.OPENAI || s.ttsEngine == TtsEngine.ELEVENLABS || s.ttsEngine == TtsEngine.CUSTOM) OutlinedTextField(
                value = s.ttsModel, onValueChange = { onChange(s.copy(ttsModel = it)) },
                label = { Text(tr("Модель", "Model") + if (s.ttsEngine.defaultModel.isNotEmpty()) tr(" (по умолчанию ${s.ttsEngine.defaultModel})", " (default ${s.ttsEngine.defaultModel})") else "") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text(if (hasKey) tr("Ключ голоса сохранён — введи новый, чтобы заменить", "Voice key saved — enter a new one to replace it") else tr("Ключ сервиса голоса", "Voice service key")) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                if (key.isNotBlank()) TextButton(onClick = { app.ttsVault.save(key); key = ""; hasKey = true }) { Text(tr("Сохранить ключ", "Save key")) }
                if (hasKey) TextButton(onClick = { app.ttsVault.clear(); hasKey = false }) { Text(tr("Удалить ключ", "Delete key")) }
            }
        }
        OutlinedButton(onClick = {
            if (key.isNotBlank()) { app.ttsVault.save(key); key = ""; hasKey = true }
            persist()
            tested = true
            app.speaker.speak(tr("Мяу! Привет, это Искра. Так я теперь звучу. Нравится?", "Meow! Hi, it's Iskra. This is how I sound now. Do you like it?"), Emotion.HAPPY)
        }) { Text(tr("▶ Проверить голос", "▶ Test the voice")) }
        if (tested) {
            val e = error
            if (e != null) Text(tr("Облачный голос не сработал, говорю системным: $e", "The cloud voice didn't work, using the system one: $e"), fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
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

    Section(tr("Слух: отзывается на имя", "Hearing: answers to her name")) {
        Hint(tr("Искра постоянно слушает и реагирует, когда слышит своё имя: «Искра, поставь таймер на 5 минут» " +
            "или просто «Искра» — и дальше команда. Распознавание полностью на телефоне: звук никуда не отправляется " +
            "и не записывается. Пока она слушает, Android показывает зелёный значок микрофона. Расход батареи — " +
            "заметный (несколько процентов в час), поэтому при выключенном экране слух по умолчанию на паузе.", "Iskra listens all the time and reacts when she hears her name: “Spark, set a timer for 5 minutes” or just “Iskra” — and then the command. Recognition runs entirely on the phone: the sound is never sent anywhere and never recorded. While she listens, Android shows a green microphone icon. It uses noticeable battery (a few percent an hour), so with the screen off hearing is paused by default."))
        when (val m = model) {
            SpeechModel.State.Missing -> OutlinedButton(onClick = { scope.launch { SpeechModel.download(ctx) } }) {
                Text(tr("Скачать модель распознавания (${SpeechModel.SIZE_MB} МБ)", "Download the speech model (${SpeechModel.SIZE_MB} MB)"))
            }
            is SpeechModel.State.Downloading -> {
                Text(tr("Скачиваю… ${(m.progress * 100).toInt()}%", "Downloading… ${(m.progress * 100).toInt()}%"))
                LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
            }
            SpeechModel.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("✅ Модель на телефоне", "✅ The model is on the phone"), Modifier.weight(1f))
                TextButton(onClick = { onChange(s.copy(alwaysListen = false)); SpeechModel.delete(ctx) }) { Text(tr("Удалить", "Delete")) }
            }
            is SpeechModel.State.Failed -> {
                Text(tr("Не удалось скачать: ${m.message}", "Download failed: ${m.message}"), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                OutlinedButton(onClick = { scope.launch { SpeechModel.download(ctx) } }) { Text(tr("Повторить", "Retry")) }
            }
        }
        SwitchRow(s.alwaysListen, tr("Слушать постоянно", "Listen all the time"), enabled = ready || s.alwaysListen) { on ->
            if (!on) { onChange(s.copy(alwaysListen = false)); return@SwitchRow }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onChange(s.copy(alwaysListen = true))
            } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (micDenied) Text(tr("Без доступа к микрофону слух не работает.", "Hearing doesn't work without microphone access."), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        if (s.alwaysListen && !s.overlayEnabled) Hint(tr("Слух работает, пока Искра на экране — включи её на вкладке «Разрешения».", "Hearing works while Iskra is on screen — turn her on in the “Permissions” tab."))
        OutlinedTextField(
            value = s.wakeNames, onValueChange = { onChange(s.copy(wakeNames = it.take(120))) },
            label = { Text(tr("На какие имена отзываться (через запятую)", "Names she answers to (comma-separated)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(s.followUp, tr("Продолжать разговор без имени (6 с после ответа)", "Keep talking without the name (6 s after a reply)")) { onChange(s.copy(followUp = it)) }
        SwitchRow(s.economicalEar, tr("Экономный слух: в тишине не распознавать, в ожидании слушать только имя", "Economical hearing: don't recognize in silence, listen only for the name while waiting")) { onChange(s.copy(economicalEar = it)) }
        SwitchRow(s.listenScreenOff, tr("Слушать и при выключенном экране", "Listen with the screen off too")) { onChange(s.copy(listenScreenOff = it)) }
        SwitchRow(s.voiceInputOffline, tr("Микрофон в чате — тоже только на телефоне", "Chat microphone — on the phone only, too"), enabled = ready || s.voiceInputOffline) {
            onChange(s.copy(voiceInputOffline = it))
        }
        Hint(tr("Системное распознавание (обычно Google) может отправлять звук на сервер. С этим переключателем чат " +
            "распознаёт речь той же моделью, что и слух. На телефонах без сервисов Google так работает всегда.", "System recognition (usually Google) may send audio to a server. With this switch the chat recognizes speech with the same model as hearing. On phones without Google services it always works this way."))
        Hint(tr("Скажи «Искра, стоп», чтобы прервать ответ. Тап по Искре во время речи — тоже замолчит.", "Say “Iskra, stop” to interrupt a reply. Tapping Iskra while she speaks stops her too."))
    }
}

private fun plain(d: Double): String = if (d == kotlin.math.floor(d)) d.toLong().toString() else d.toString()

/** A number field that keeps what is typed (even an empty field) and reports only valid values; empty = 0. */
@Composable
private fun NumberField(label: String, initial: String, decimal: Boolean = false, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (initial == "0") "" else initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val t = v.replace(',', '.').filter { it.isDigit() || (decimal && it == '.') }.take(12)
            text = t
            val n = if (t.isEmpty()) 0.0 else t.toDoubleOrNull()
            if (n != null) onValue(n.coerceAtLeast(0.0))
        },
        label = { Text(label) }, placeholder = { Text("0") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

/** «Экономия», look, smoothness and how restless she is. */
@Composable
fun MotionSection(s: AppSettings, onChange: (AppSettings) -> Unit) {
    val ctx = LocalContext.current
    var hz by remember { mutableIntStateOf(FrameStats.hz) }
    var now by remember { mutableStateOf(Diag.economyState) }
    var cloud by remember { mutableStateOf(IskraApp.instance.usage.describe()) }
    LaunchedEffect(Unit) {
        while (true) { hz = FrameStats.hz; now = Diag.economyState; cloud = IskraApp.instance.usage.describe(); delay(1000) }
    }
    var limitsOpen by rememberSaveable { mutableStateOf(s.usageLimits.any || s.usageLimits.priced) }
    val systemCalm = remember { DeviceHelper.animationsOff(ctx) }
    Section(tr("Экономия", "Economy")) {
        EconomyMode.entries.forEach { m -> RadioRow(s.economy == m, m.title, m.hint) { onChange(s.copy(economy = m)) } }
        Hint(tr("Сейчас: $now", "Now: $now"))
        Hint(if (hz > 0) tr("Экран отдаёт Искре ~$hz кадров/с.", "The screen gives Iskra ~$hz fps.") else tr("Частота появится, когда Искра будет на экране.", "The refresh rate appears when Iskra is on screen."))
        Hint(tr("Телефон: ${DeviceHelper.deviceClass(ctx).title}. Расход батареи за час — в «Диагностике», строка «Расход в покое».", "Phone: ${DeviceHelper.deviceClass(ctx).title}. Battery use per hour is in “Diagnostics”, the “Idle drain” line."))
        Hint(tr("Облако: $cloud", "Cloud: $cloud"))
        TextButton(onClick = { limitsOpen = !limitsOpen }) { Text(if (limitsOpen) tr("Скрыть лимиты и цены", "Hide limits and prices") else tr("Лимиты и цены облака", "Cloud limits and prices")) }
        if (limitsOpen) {
            Hint(tr("Дневные лимиты: на 80 % Искра предупредит, на 100 % до завтра ответит без модели и скажет системным голосом. 0 — без лимита.", "Daily limits: at 80 % Iskra warns you, at 100 % she answers without the model and with the system voice until tomorrow. 0 — no limit."))
            NumberField(tr("Запросов к модели в день", "Model requests per day"), s.limitRequests.toString()) { onChange(s.copy(limitRequests = it.toInt())) }
            NumberField(tr("Токенов в день", "Tokens per day"), s.limitTokens.toString()) { onChange(s.copy(limitTokens = it.toLong())) }
            NumberField(tr("Символов облачного голоса в день", "Cloud voice characters per day"), s.limitTtsChars.toString()) { onChange(s.copy(limitTtsChars = it.toLong())) }
            Hint(tr("Цены за 1 млн — с сайта твоего сервиса. Без цен Искра считает только количество. Если сервис не сообщает токены, они оцениваются по длине текста (≈).", "Prices per 1 million — from your service's website. Without prices Iskra counts only amounts. If the service doesn't report tokens, they are estimated from the text length (≈)."))
            NumberField(tr("Цена 1 млн входных токенов", "Price of 1M input tokens"), plain(s.priceInPerM), decimal = true) { onChange(s.copy(priceInPerM = it)) }
            NumberField(tr("Цена 1 млн выходных токенов", "Price of 1M output tokens"), plain(s.priceOutPerM), decimal = true) { onChange(s.copy(priceOutPerM = it)) }
            NumberField(tr("Цена 1 млн символов голоса", "Price of 1M voice characters"), plain(s.priceTtsPerM), decimal = true) { onChange(s.copy(priceTtsPerM = it)) }
            OutlinedTextField(value = s.currency, onValueChange = { onChange(s.copy(currency = it.take(8))) },
                label = { Text(tr("Валюта", "Currency")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    Section(tr("Движение", "Movement")) {
        Text(tr("Вид", "Look"))
        AvatarLook.entries.forEach { m -> RadioRow(s.avatarLook == m, m.title) { onChange(s.copy(avatarLook = m)) } }
        SwitchRow(s.calmMotion || systemCalm, tr("Спокойные движения (не гуляет по экрану)", "Calm movements (doesn't wander around the screen)"), enabled = !systemCalm) {
            onChange(s.copy(calmMotion = it))
        }
        if (systemCalm) Hint(tr("В Android выключены анимации — Искра двигается спокойно, пока они выключены.", "Animations are off in Android — Iskra moves calmly while they are off."))
        SwitchRow(s.wander, tr("Живёт своей жизнью (гуляет, прыгает, лазает)", "Lives her own life (walks, jumps, climbs)"), enabled = !(s.calmMotion || systemCalm)) { onChange(s.copy(wander = it)) }
        if (s.wander && !(s.calmMotion || systemCalm)) {
            Text(tr("Непоседливость", "Restlessness"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(tr("спокойная", "calm"), tr("обычная", "normal"), tr("шустрая", "lively")).forEachIndexed { i, label ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(s.liveliness == i, role = Role.RadioButton) { onChange(s.copy(liveliness = i)) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.liveliness == i, onClick = null)
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
            Hint(tr("Прыгает, забирается на край экрана и висит, ползает по верхнему краю, убегает за экран и выглядывает. " +
                "Если бросить её в край — зацепится. В «Батарее» она всегда спокойная.", "Jumps, climbs onto the screen edge and hangs there, crawls along the top edge, runs off the screen and peeks out. Throw her at an edge — she'll grab it. In “Battery” mode she is always calm."))
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
    Section(tr("Погода", "Weather")) {
        OutlinedTextField(
            value = s.weatherCity, onValueChange = { onChange(s.copy(weatherCity = it.take(80))); status = null },
            label = { Text(tr("Твой город", "Your city")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                status = tr("Ищу…", "Searching…")
                scope.launch {
                    status = try {
                        val p = ru.iskra.core.skills.WeatherClient().geocode(s.weatherCity)
                        app.settings.saveWeatherPlace(p); place = p
                        if (p == null) tr("Не нашла такой город", "Couldn't find that city") else tr("Нашла: ${p.name}", "Found: ${p.name}")
                    } catch (e: Exception) { tr("Нет связи с сервисом погоды", "No connection to the weather service") }
                }
            }, enabled = s.weatherCity.isNotBlank()) { Text(tr("Найти город", "Find city")) }
            Spacer(Modifier.size(8.dp))
            Text(status ?: place?.let { tr("Сейчас: ${it.name}", "Now: ${it.name}") } ?: tr("Город не задан", "City not set"), fontSize = 13.sp)
        }
        Hint(tr("«Искра, какая погода», «погода завтра», «нужен ли зонт». Данные: open-meteo.com — туда уходят только координаты города, без ключа и без доступа к местоположению.", "“Iskra, what's the weather”, “weather tomorrow”, “do I need an umbrella”. Data: open-meteo.com — only the city's coordinates are sent, no key and no location access."))
    }
    Section(tr("Сама заговаривает", "Speaks up on her own")) {
        ru.iskra.core.skills.ProactiveFrequency.entries.forEach { f ->
            RadioRow(s.proactive == f, f.title) { onChange(s.copy(proactive = f)) }
        }
        if (s.proactive != ru.iskra.core.skills.ProactiveFrequency.OFF) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Тихие часы: с ", "Quiet hours: from "))
                HourField(s.quietFrom) { onChange(s.copy(quietFrom = it)) }
                Text(tr(" до ", " to "))
                HourField(s.quietTo) { onChange(s.copy(quietTo = it)) }
            }
            SwitchRow(s.proactiveVoice, tr("Говорить голосом (иначе только облачко)", "Speak aloud (otherwise only the bubble)")) { onChange(s.copy(proactiveVoice = it)) }
            Hint(tr("Утром при первом включении экрана, после долгого перерыва, иногда вечером. Никогда — в тихие часы, при выключенном экране и чаще, чем выбрано. Голосом — только если не играет музыка.", "In the morning when the screen first turns on, when you come back after a long break, sometimes in the evening. Never during quiet hours, with the screen off or more often than chosen. Aloud — only if no music is playing."))
        }
    }
    Section(tr("Свобода модели", "Model freedom")) {
        Hint(tr("По умолчанию модель отвечает свободно: Искра не подменяет её ответы и не добавляет запретов в её указания. " +
                "Нужное правило можно включить — оно действует со следующего сообщения после «Сохранить».",
            "By default the model answers freely: Iskra doesn't replace its answers or add prohibitions to its instructions. " +
                "Turn a rule on if you want it — it applies from the next message after “Save”."))
        SwitchRow(s.crisisHelp, tr("Помощь в кризисе: на «жить не хочется» — телефоны помощи", "Help in a crisis: helplines for “I don't want to live”"), tag = "crisisHelp") {
            onChange(s.copy(crisisHelp = it))
        }
        if (s.crisisHelp) Hint(tr("Без «мозга» и с моделью на телефоне — сразу ответ с номерами (112, 8-800-2000-122, психологи МЧС); облачная " +
                "модель отвечает своими словами, номера добавляются, если она их не назвала. Полдня Искра говорит бережно, назавтра спрашивает, как ты.",
            "Without a brain and with the phone model — an answer with numbers at once (112, 988, 116 123); a cloud model answers in its " +
                "own words, and the numbers are added if it named none. For half a day Iskra is gentle, the next day she asks how you are."))
        SwitchRow(s.aiHonesty, tr("Говорит, что она программа, если спросят прямо", "Says she is a program when asked directly"), tag = "aiHonesty") {
            onChange(s.copy(aiHonesty = it))
        }
        SwitchRow(s.noGuilt, tr("Не давит на чувства: без упрёков и всерьёз ревности", "No guilt trips and no serious jealousy"), tag = "noGuilt") {
            onChange(s.copy(noGuilt = it))
        }
        SwitchRow(s.specialists, tr("Советует специалиста по здоровью, праву и деньгам", "Suggests a professional on health, law and money"), tag = "specialists") {
            onChange(s.copy(specialists = it))
        }
        OutlinedTextField(
            value = s.ownWords,
            onValueChange = { onChange(s.copy(ownWords = it.take(ru.iskra.core.agent.PromptComposer.OWN_WORDS_MAX))) },
            label = { Text(tr("Свои указания модели", "Your own instructions to the model")) },
            placeholder = { Text(tr("Например: говори коротко и с юмором, зови меня капитаном", "For example: be brief and funny, call me captain")) },
            minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("ownWords"),
        )
        Hint(tr("Твои слова идут в указания модели (облачной и на телефоне) и важнее её обычной манеры речи. " +
                "${s.ownWords.length} из ${ru.iskra.core.agent.PromptComposer.OWN_WORDS_MAX} знаков.",
            "Your words go into the model's instructions (cloud and phone) and come before her usual manner of speech. " +
                "${s.ownWords.length} of ${ru.iskra.core.agent.PromptComposer.OWN_WORDS_MAX} characters."))
        Hint(tr("Всегда остаются: подтверждение действий с телефоном, честность о том, что сделано, и защита от команд, спрятанных в заметках " +
                "и найденных текстах. Собственная осторожность облачных моделей и Gemma — часть самих моделей, приложение её не меняет.",
            "Always kept: confirmation of actions on the phone, honesty about what was done, and protection from commands hidden in notes " +
                "and found texts. Cloud models' and Gemma's own caution is part of the models; the app doesn't change it."))
    }
    Section(tr("Напоминания", "Reminders")) {
        val exact = app.reminderScheduler.exact
        Text(if (exact) tr("✅ Напоминания приходят точно в срок", "✅ Reminders arrive exactly on time") else tr("⚠️ Android может прислать напоминание на несколько минут позже", "⚠️ Android may deliver a reminder a few minutes late"), fontSize = 14.sp)
        if (!exact && android.os.Build.VERSION.SDK_INT >= 31) {
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + ctx.packageName)))
                }
            }) { Text(tr("Разрешить «Будильники и напоминания»", "Allow “Alarms & reminders”")) }
        }
        Hint(tr("«Напомни через 10 минут позвонить маме», «напомни завтра в 9 про встречу», «какие напоминания». Переживают перезагрузку телефона. Список и удаление — во вкладке «Память».", "“Remind me in 10 minutes to call mom”, “remind me tomorrow at 9 about the meeting”, “my reminders”. They survive a phone restart. The list and deletion are in the “Memory” tab."))
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
