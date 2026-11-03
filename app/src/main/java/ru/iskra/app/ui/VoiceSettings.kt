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
import ru.iskra.app.data.FpsMode
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

@Composable
private fun SwitchRow(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.size(8.dp)); Text(label)
    }
}

/** Neural voice selection. [persist] saves the whole settings form (needed before a voice test). */
@Composable
fun VoiceSection(s: AppSettings, onChange: (AppSettings) -> Unit, persist: () -> Unit) {
    val app = IskraApp.instance
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(app.ttsVault.hasKey()) }
    val error by app.speaker.lastError.collectAsState()
    var tested by remember { mutableStateOf(false) }

    Section("Голос") {
        SwitchRow(s.ttsEnabled, "Озвучивать ответы") { onChange(s.copy(ttsEnabled = it)) }
        TtsEngine.entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.ttsEngine == e, onClick = { onChange(s.copy(ttsEngine = e, ttsVoice = "", ttsModel = "")) })
                Text(e.title)
            }
        }
        Hint(s.ttsEngine.note)
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
        SwitchRow(s.listenScreenOff, "Слушать и при выключенном экране") { onChange(s.copy(listenScreenOff = it)) }
        Hint("Скажи «Искра, стоп», чтобы прервать ответ. Тап по Искре во время речи — тоже замолчит.")
    }
}

/** Smoothness and how restless she is. */
@Composable
fun MotionSection(s: AppSettings, onChange: (AppSettings) -> Unit) {
    var hz by remember { mutableIntStateOf(FrameStats.hz) }
    LaunchedEffect(Unit) { while (true) { hz = FrameStats.hz; delay(1000) } }
    Section("Движение") {
        FpsMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.fpsMode == m, onClick = { onChange(s.copy(fpsMode = m)) })
                Text(m.title)
            }
        }
        Hint(if (hz > 0) "Сейчас экран отдаёт Искре ~$hz кадров/с. HyperOS сама снижает частоту в некоторых приложениях и при экономии энергии." else "Частота появится, когда Искра будет на экране.")
        SwitchRow(s.wander, "Живёт своей жизнью (гуляет, прыгает, лазает)") { onChange(s.copy(wander = it)) }
        if (s.wander) {
            Text("Непоседливость")
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("спокойная", "обычная", "шустрая").forEachIndexed { i, label ->
                    RadioButton(selected = s.liveliness == i, onClick = { onChange(s.copy(liveliness = i)) })
                    Text(label, fontSize = 13.sp)
                }
            }
            Hint("Прыгает, забирается на край экрана и висит, ползает по верхнему краю, убегает за экран и выглядывает. " +
                "Если бросить её в край — зацепится.")
        }
    }
}
