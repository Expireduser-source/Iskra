package ru.iskra.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import ru.iskra.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.app.voice.Listener
import ru.iskra.app.voice.MicArbiter
import ru.iskra.app.voice.OfflineDictation
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.iskra.core.persona.Emotion

private fun emotionEmoji(e: Emotion?) = when (e) {
    Emotion.HAPPY -> "😺"; Emotion.EXCITED -> "😸"; Emotion.CURIOUS -> "🐱"; Emotion.SHY -> "😽"
    Emotion.POUTY -> "😾"; Emotion.SAD -> "😿"; Emotion.SURPRISED -> "🙀"; Emotion.SLEEPY -> "😴"
    else -> "🐈"
}

/** Shared chat UI used by the main screen and the floating quick chat. */
@Composable
fun ChatScreen(modifier: Modifier = Modifier, compact: Boolean = false) {
    val app = IskraApp.instance
    val ctx = LocalContext.current
    val messages by app.chat.messages.collectAsState()
    val busy by app.chat.busy.collectAsState()
    val pending by app.confirm.pending.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followReply by remember { mutableStateOf(true) }

    val onVoiceState: (Boolean) -> Unit = { on ->
        listening = on
        CompanionBus.emit(AvatarCue.Listening(on))
        if (!on) MicArbiter.set(false)
    }
    val onVoiceError: (String) -> Unit = { voiceError = it; MicArbiter.set(false) }
    val listener = remember { Listener(ctx, onResult = { app.chat.send(it) }, onState = onVoiceState, onError = onVoiceError) }
    // On-phone recognition: phones without a system recognizer, or the owner wants audio to stay on the phone.
    val offline = remember { OfflineDictation(ctx, onResult = { app.chat.send(it) }, onState = onVoiceState, onError = onVoiceError) }
    var offlineActive by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { listener.destroy(); offline.destroy(); MicArbiter.set(false) } }

    // Confirmation dialogs are shown here; the overlay opens the quick chat if no chat screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var counted = false
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !counted) { counted = true; app.chatScreensResumed.incrementAndGet() }
            if (event == Lifecycle.Event.ON_PAUSE && counted) { counted = false; app.chatScreensResumed.decrementAndGet() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); if (counted) app.chatScreensResumed.decrementAndGet() }
    }

    val scope = rememberCoroutineScope()
    fun startListening() {
        // Let the always-on ear release the microphone first.
        MicArbiter.set(true)
        scope.launch { delay(250); if (offlineActive) offline.start() else listener.start() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else voiceError = "Без доступа к микрофону голосовой ввод не работает."
    }

    // Following the tail is the owner's choice. A drag pauses it; reaching the end resumes it.
    LaunchedEffect(dragging, listState.canScrollForward) {
        if (dragging) followReply = false
        else if (!listState.canScrollForward) followReply = true
    }
    LaunchedEffect(messages.lastOrNull(), busy, followReply, listState.layoutInfo.viewportSize.height) {
        if (followReply && !dragging && messages.isNotEmpty()) {
            // A trailing item also keeps the end of a message taller than the viewport visible.
            listState.scrollToItem(messages.size)
        }
    }

    LaunchedEffect(messages.isEmpty()) {
        if (messages.isEmpty()) {
            followReply = true
            listState.scrollToItem(0)
        }
    }

    fun send(text: String) {
        followReply = true
        app.chat.send(text)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item(key = "welcome") {
                Welcome(compact, enabled = !busy, onSend = ::send)
            }
            items(messages, key = { it.id }) { m -> Bubble(m) }
            item(key = "tail") {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Думаю…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { app.chat.cancel() }) { Text("Стоп") }
                } else Spacer(Modifier.size(1.dp))
            }
        }
        if (!followReply && messages.isNotEmpty()) {
            TextButton(onClick = { followReply = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("К последнему сообщению ↓")
            }
        }
        voiceError?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(4000) },
                modifier = Modifier.weight(1f), placeholder = { Text("Напиши Искре…") }, maxLines = 4,
            )
            Spacer(Modifier.size(6.dp))
            IconButton(enabled = !busy || listening, modifier = Modifier.semantics {
                contentDescription = if (listening) "Остановить запись" else "Голосовое сообщение"
            }, onClick = {
                voiceError = null
                if (listening) { if (offlineActive) offline.stop() else listener.stop(); return@IconButton }
                val system = listener.available()
                offlineActive = app.settings.load().voiceInputOffline || !system
                if (offlineActive && !offline.available()) {
                    voiceError = if (!system) "На телефоне нет системного распознавания речи. Скачай модель в «Настройки» → «Слух» — " +
                        "и микрофон заработает прямо на телефоне, без интернета."
                    else "Для ввода только на телефоне скачай модель в «Настройки» → «Слух»."
                    return@IconButton
                }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (listening) "■" else "🎤", Modifier.clearAndSetSemantics {}) }
            Spacer(Modifier.size(6.dp))
            IconButton(enabled = !busy && input.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Отправить сообщение" },
                onClick = { send(input); input = "" }) { Text("➤", Modifier.clearAndSetSemantics {}, color = if (!busy && input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    pending?.let { req ->
        AlertDialog(
            onDismissRequest = { req.respond(false) },
            title = { Text(req.title) },
            text = { Text(req.details) },
            confirmButton = { TextButton(onClick = { req.respond(true) }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = { req.respond(false) }) { Text("Отмена") } },
        )
    }
}

/** The existing character art anchors the welcome; every suggestion works without an API key. */
@Composable
private fun Welcome(compact: Boolean, enabled: Boolean, onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!compact) {
            Image(painterResource(R.drawable.iskra_wave), contentDescription = null,
                modifier = Modifier.size(132.dp).align(Alignment.CenterHorizontally))
            Text("Ну что, знакомиться?", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(if (compact) "Я тут. Что придумаем?" else
            "Я Искра. Дела — в лапки, любопытство — с собой. Начнём с чего-нибудь маленького?",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(enabled = enabled, onClick = { onSend("Удиви меня") }, modifier = Modifier.fillMaxWidth()) {
            Text("Удиви меня")
        }
        if (!compact) {
            Text("Маленькая игра с привычным миром. Работает без интернета.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = enabled, onClick = { onSend("Что ты помнишь") }) { Text("Заглянуть в память") }
            Text("Таймеры, заметки и напоминания тоже доступны сразу. Для свободной беседы подключи модель в «Настройках».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Bubble(m: UiMessage) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .background(if (m.fromUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!m.fromUser) Text("${emotionEmoji(m.emotion)} Искра", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Text(m.text, color = if (m.isError) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
            m.actions.forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
