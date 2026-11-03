package ru.iskra.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.app.voice.Listener
import ru.iskra.app.voice.MicArbiter
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

    val listener = remember {
        Listener(ctx,
            onResult = { app.chat.send(it) },
            onState = { on ->
                listening = on
                CompanionBus.emit(AvatarCue.Listening(on))
                if (!on) MicArbiter.set(false)
            },
            onError = { voiceError = it; MicArbiter.set(false) })
    }
    DisposableEffect(Unit) { onDispose { listener.destroy(); MicArbiter.set(false) } }

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
        scope.launch { delay(250); listener.start() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else voiceError = "Без доступа к микрофону голосовой ввод не работает."
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && !compact) item {
                Text(
                    "Привет! Я Искра. Могу поставить будильник или таймер, включить фонарик, открыть приложение, " +
                        "запомнить что-то важное («запомни: …»). Для свободной беседы подключи модель в «Настройках».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            items(messages, key = { it.id }) { m -> Bubble(m) }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("думаю…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { app.chat.cancel() }) { Text("Стоп") }
                }
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
            FilledTonalButton(onClick = {
                voiceError = null
                if (listening) { listener.stop(); return@FilledTonalButton }
                if (!listener.available()) { voiceError = "На телефоне нет службы распознавания речи."; return@FilledTonalButton }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (listening) "■" else "🎤") }
            Spacer(Modifier.size(6.dp))
            Button(enabled = !busy && input.isNotBlank(), onClick = { app.chat.send(input); input = "" }) { Text("➤") }
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
