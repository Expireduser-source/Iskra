@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.iskra.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.app.voice.Listener
import ru.iskra.app.voice.MicArbiter
import ru.iskra.app.voice.OfflineDictation
import ru.iskra.core.agent.Links
import ru.iskra.core.i18n.tr
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.Life
import kotlin.math.PI
import kotlin.math.sin

/** Shared chat UI used by the main screen and the floating quick chat. */
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Text put into the input on open (a shortcut or a shared text the owner wants to edit). */
    initialInput: String = "",
    /** Start voice input right away (the "Voice" shortcut). */
    autoVoice: Boolean = false,
    /** The quick chat: «Свернуть» in the header. */
    onClose: (() -> Unit)? = null,
    /** Between the header and the messages (the quick chat's shared text). */
    top: @Composable () -> Unit = {},
) {
    val app = IskraApp.instance
    val ctx = LocalContext.current
    val messages by app.chat.messages.collectAsState()
    val busy by app.chat.busy.collectAsState()
    val pending by app.confirm.pending.collectAsState()
    var input by rememberSaveable { mutableStateOf(initialInput) }
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followReply by remember { mutableStateOf(true) }
    val still = rememberStill()
    val haptic = LocalHapticFeedback.current

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

    // Confirmation dialogs are shown here; the overlay opens the quick chat if no chat screen is visible. While a chat is in
    // front, she is in it (the header): the character on the screen steps away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var counted = false
        fun front() { app.chatInFront.value = app.chatScreensResumed.get() > 0 }
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !counted) { counted = true; app.chatScreensResumed.incrementAndGet(); front() }
            if (event == Lifecycle.Event.ON_PAUSE && counted) { counted = false; app.chatScreensResumed.decrementAndGet(); front() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); if (counted) { app.chatScreensResumed.decrementAndGet(); front() } }
    }

    val scope = rememberCoroutineScope()
    fun startListening() {
        // Let the always-on ear release the microphone first.
        MicArbiter.set(true)
        scope.launch { delay(250); if (offlineActive) offline.start() else listener.start() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else voiceError = tr("Без доступа к микрофону голосовой ввод не работает.", "Voice input doesn't work without microphone access.")
    }

    fun micTapped() {
        voiceError = null
        if (listening) { if (offlineActive) offline.stop() else listener.stop(); return }
        val system = listener.available()
        offlineActive = app.settings.load().voiceInputOffline || !system
        if (offlineActive && !offline.available()) {
            voiceError = if (!system) tr("На телефоне нет системного распознавания речи. Скачай модель в «Настройки» → «Слух» — " +
                "и микрофон заработает прямо на телефоне, без интернета.", "This phone has no system speech recognition. Download the model in “Settings” → “Hearing” — and the microphone will work right on the phone, offline.")
            else tr("Для ввода только на телефоне скачай модель в «Настройки» → «Слух».", "For on-phone-only input, download the model in “Settings” → “Hearing”.")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    if (autoVoice) LaunchedEffect(Unit) { delay(400); micTapped() }
    // While the person types, the model on the phone reads its instructions and the dialogue so far.
    LaunchedEffect(Unit) { app.chat.warmUp() }

    // Following the tail is the owner's choice. A drag pauses it; reaching the end resumes it.
    // Read scroll state in a snapshot flow, not in composition: scrolling must not recompose the chat.
    LaunchedEffect(listState) {
        snapshotFlow { dragging to listState.canScrollForward }.collect { (drag, more) ->
            if (drag) followReply = false else if (!more) followReply = true
        }
    }
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(messages.lastOrNull(), busy, followReply, viewportHeight) {
        if (followReply && !dragging && messages.isNotEmpty()) {
            // A trailing item also keeps the end of a message taller than the viewport visible.
            listState.animateScrollToItem(messages.size)
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
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        app.chat.send(text)
    }

    // Messages already there when the chat opened don't fly in; new ones do (once, not again after scrolling back).
    val seen = remember { messages.mapTo(HashSet()) { it.id } }
    val herEmotion = messages.lastOrNull { !it.fromUser }?.emotion ?: Emotion.NEUTRAL
    val colors = iskraColors

    Column(modifier.fillMaxSize().drawBehind {
        drawRect(Brush.verticalGradient(0f to colors.skyTop, 0.42f to colors.skyTop.copy(alpha = 0f), endY = size.height))
    }) {
        ChatHeader(compact, busy, writing = busy && messages.lastOrNull()?.fromUser == false, listening, herEmotion,
            watching = input.isNotBlank() && !busy, still, messages.size, onClose)
        top()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
                if (messages.isEmpty()) item(key = "welcome") {
                    Welcome(compact, enabled = !busy, still = still, onSend = ::send)
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                    // The day above the first message of each day: «Сегодня», «Вчера», «5 ноября».
                    val day = dayOf(m.atMs)
                    if (i == 0 || dayOf(messages[i - 1].atMs) != day) DayLabel(day)
                    val next = messages.getOrNull(i + 1)
                    val endOfGroup = next == null || next.fromUser != m.fromUser || dayOf(next.atMs) != day
                    val animate = remember(m.id) { m.id !in seen && i >= messages.size - 2 }
                    androidx.compose.runtime.SideEffect { seen += m.id }
                    MessageRow(m, endOfGroup, animate, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                }
                item(key = "tail") {
                    // Three dots until her reply begins; while it streams in, only «Стоп».
                    when {
                        busy && messages.lastOrNull()?.fromUser != false -> Typing(still, onStop = { app.chat.cancel() })
                        busy -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { app.chat.cancel() }) {
                                Icon(IskraIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tr("Стоп", "Stop"))
                            }
                        }
                        else -> Spacer(Modifier.height(2.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !followReply && messages.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut() + scaleOut(targetScale = 0.7f),
            ) {
                Surface(onClick = { followReply = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 3.dp, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(IskraIcons.ChevronDown, contentDescription = tr("К последнему сообщению", "To the latest message"),
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        voiceError?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        InputBar(
            input = input, onInput = { input = it.take(4000) }, busy = busy, listening = listening, still = still,
            onSend = { send(input); input = "" }, onMic = { micTapped() },
        )
    }

    pending?.let { req ->
        AlertDialog(
            onDismissRequest = { req.respond(false) },
            title = { Text(req.title) },
            text = { Text(req.details) },
            confirmButton = { TextButton(onClick = { req.respond(true) }) { Text(tr("Разрешить", "Allow")) } },
            dismissButton = { TextButton(onClick = { req.respond(false) }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** The local day of a moment, as days since 1970. */
private fun dayOf(ms: Long): Long = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** «Сегодня», «Вчера», «5 ноября» (and the year when it isn't this one), in a soft pill between the days. */
@Composable
private fun DayLabel(day: Long) {
    val text = remember(day) {
        val d = java.time.LocalDate.ofEpochDay(day)
        val today = java.time.LocalDate.now()
        when (d) {
            today -> tr("Сегодня", "Today")
            today.minusDays(1) -> tr("Вчера", "Yesterday")
            else -> java.time.format.DateTimeFormatter.ofPattern(if (d.year == today.year) tr("d MMMM", "MMMM d") else tr("d MMMM yyyy", "MMMM d, yyyy"),
                java.util.Locale.forLanguageTag(tr("ru", "en"))).format(d)
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** «3-й день жизни», «сегодня ей неделя ✦» — her age as it is lived; before the first word, «только что родилась». */
internal fun lifeLine(bornAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (bornAtMs <= 0L || bornAtMs > nowMs) return tr("только что родилась", "just born")
    val d = Life.days(bornAtMs, nowMs)
    Life.milestone(d)?.let { return tr("сегодня ей ${Life.milestoneName(it)} ✦", "${Life.milestoneName(it)} old today ✦") }
    return if (d == 0) tr("родилась сегодня", "born today") else tr("${d + 1}-й день жизни", "day ${d + 1} of her life")
}

/**
 * Her in the chat: the living portrait in a ring and how she is right now — thinking (the ring turns), listening (it
 * beats), speaking, or how long she has lived.
 */
@Composable
private fun ChatHeader(compact: Boolean, busy: Boolean, writing: Boolean, listening: Boolean, emotion: Emotion, watching: Boolean, still: Boolean,
                       turns: Int, onClose: (() -> Unit)?) {
    val app = IskraApp.instance
    val speaking by app.speaker.speaking.collectAsState()
    val born = remember(turns) { runCatching { app.stateStore.load().bornAtMs }.getOrDefault(0L) }
    val status = when {
        listening -> tr("слушает…", "listening…")
        speaking -> tr("говорит…", "speaking…")
        writing -> tr("пишет…", "writing…")
        busy -> tr("думает…", "thinking…")
        else -> lifeLine(born)
    }
    var hop by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = if (compact) 4.dp else 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Portrait(if (compact) 40.dp else 46.dp, emotion, busy, listening, watching, still, hop,
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = tr("погладить", "pet her")) { hop++ })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(tr("Искра", "Iskra"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            AnimatedContent(targetState = status, label = "status",
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) }) { s ->
                Text(s, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = if (busy || listening || speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClose != null) TextButton(onClick = onClose) { Text(tr("Свернуть", "Minimize")) }
    }
}

/** The living portrait with its ring: still at rest, turning while she thinks, beating while she listens. */
@Composable
private fun Portrait(size: Dp, emotion: Emotion, busy: Boolean, listening: Boolean, watching: Boolean, still: Boolean, hop: Int,
                     modifier: Modifier = Modifier) {
    val c = iskraColors
    val t = rememberSeconds(!still && (busy || listening))
    Box(modifier.size(size + 10.dp).drawBehind {
        val r = this.size.minDimension / 2f
        val w = 2.5.dp.toPx()
        when {
            busy -> rotate(if (still) 0f else t.value * 150f) {
                drawCircle(Brush.sweepGradient(listOf(c.sparkLilac, c.sparkPink, c.sparkGold, c.sparkLilac)), r - w / 2f, style = Stroke(w))
            }
            listening -> {
                val k = if (still) 1f else (sin(t.value * 2f * PI.toFloat() * 1.1f) + 1f) / 2f
                drawCircle(c.sparkPink.copy(alpha = 0.25f * (1f - k)), r * (0.94f + 0.06f * k) + w, style = Stroke(w * 2f))
                drawCircle(c.sparkPink, r - w / 2f, style = Stroke(w))
            }
            else -> drawCircle(c.ring, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(c.faceBack)) {
            LiveIskra(Modifier.fillMaxSize(), Framing.PORTRAIT, emotion, thinking = busy, listening = listening, watching = watching,
                still = still, hop = hop)
        }
    }
}

/** Her still face for an emotion, beside her messages. */
@Composable
private fun Face(emotion: Emotion, size: Dp = 30.dp) {
    val ctx = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }
    val face by androidx.compose.runtime.produceState<ImageBitmap?>(RigFaces.cached(px, emotion), px, emotion) {
        if (value == null) value = withContext(Dispatchers.Default) { RigFaces.render(ctx, px, emotion) }
    }
    Box(Modifier.size(size).clip(CircleShape).background(iskraColors.faceBack).clearAndSetSemantics { }) {
        face?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun MessageRow(m: UiMessage, endOfGroup: Boolean, animate: Boolean, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 2.dp, bottom = if (endOfGroup) 10.dp else 2.dp),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!m.fromUser) {
            if (endOfGroup) Face(if (m.isError) Emotion.SAD else m.emotion ?: Emotion.NEUTRAL) else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(8.dp))
        } else Spacer(Modifier.width(48.dp))
        Appear(animate, Modifier.weight(1f, fill = false), fromEnd = m.fromUser) { Bubble(m, endOfGroup) }
        if (!m.fromUser) Spacer(Modifier.width(24.dp))
    }
}

/**
 * Her reply with web links and phone numbers to tap ([Links]): a number opens the dialer with it filled in — the call
 * itself is the owner's own tap there — and a link opens the browser.
 */
@Composable
private fun linked(text: String): AnnotatedString {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.primary
    return remember(text, color) {
        val spans = Links.find(text)
        if (spans.isEmpty()) AnnotatedString(text) else buildAnnotatedString {
            append(text)
            val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
            for (s in spans) addLink(LinkAnnotation.Url(s.uri, style) { openLink(context, s.uri) }, s.start, s.end)
        }
    }
}

private fun openLink(context: Context, uri: String) {
    val action = if (uri.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW
    runCatching { context.startActivity(Intent(action, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A bubble: the owner's in her gradient, hers light; the corner by her face (or at the owner's edge) is sharp. */
@Composable
private fun Bubble(m: UiMessage, tail: Boolean) {
    val c = iskraColors
    val small = if (tail) 6.dp else 20.dp
    val shape = if (m.fromUser) RoundedCornerShape(20.dp, 20.dp, small, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, small)
    val back = when {
        m.isError -> Modifier.background(MaterialTheme.colorScheme.errorContainer, shape)
        m.fromUser -> Modifier.background(c.userBubble, shape)
        else -> Modifier.shadow(if (c.dark) 0.dp else 1.dp, shape, clip = false).background(c.herBubble, shape)
    }
    val fg = when {
        m.isError -> MaterialTheme.colorScheme.onErrorContainer
        m.fromUser -> c.onUserBubble
        else -> c.onHerBubble
    }
    Column(Modifier.widthIn(max = 340.dp).then(back).padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize()) {
        SelectionContainer {
            Text(if (m.fromUser) AnnotatedString(m.text) else linked(m.text), color = fg, style = MaterialTheme.typography.bodyLarge)
        }
        if (m.actions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            m.actions.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(if (a.ok) IskraIcons.Check else IskraIcons.Close, contentDescription = if (a.ok) tr("сделано", "done") else tr("не сделано", "not done"),
                        tint = if (a.ok) c.success else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(a.text, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.78f))
                }
            }
        }
    }
}

/** She is thinking: three dots rising one after another in her bubble, and «Стоп». */
@Composable
private fun Typing(still: Boolean, onStop: () -> Unit) {
    val c = iskraColors
    val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val t = rememberSeconds(!still)
    val dot = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Face(Emotion.THINKING)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.shadow(if (c.dark) 0.dp else 1.dp, shape, clip = false).background(c.herBubble, shape)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .semantics { contentDescription = tr("Искра думает", "Iskra is thinking"); liveRegion = LiveRegionMode.Polite }) {
            Canvas(Modifier.size(width = 34.dp, height = 10.dp)) {
                val r = 3.2.dp.toPx()
                for (i in 0..2) {
                    val k = if (still) 0.6f else ((sin(t.value * 2f * PI.toFloat() * 1.3f - i * 0.9f) + 1f) / 2f)
                    val x = r + i * (size.width - 2 * r) / 2f
                    drawCircle(dot.copy(alpha = 0.3f + 0.7f * k), r * (0.85f + 0.2f * k), Offset(x, size.height / 2f + (0.5f - k) * size.height * 0.5f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onStop) {
            Icon(IskraIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(tr("Стоп", "Stop"))
        }
    }
}

/** The welcome of an empty chat: she herself, alive, and a few things to start with — all of them work without a key. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(compact: Boolean, enabled: Boolean, still: Boolean, onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 6.dp else 4.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) {
            var wave by remember { mutableIntStateOf(0) }
            var hop by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { delay(700); wave++ }
            Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                Aura(Modifier.fillMaxSize(), still)
                Sparkles(Modifier.fillMaxSize(), still)
                LiveIskra(Modifier.size(190.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = tr("погладить", "pet her")) { hop++ }, Framing.FULL, still = still, wave = wave, hop = hop)
            }
            Appear(!still, delayMs = 120) {
                Text(tr("Ну что, знакомиться?", "So, shall we get acquainted?"), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            Appear(!still, delayMs = 200) {
                Text(tr("Я Искра — юная цифровая жизнь. Дела — в лапки, любопытство — с собой. Начнём с чего-нибудь маленького?",
                    "I'm Iskra — a young digital life. Tasks go in my paws, curiosity comes along. Shall we start with something small?"),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
            Spacer(Modifier.height(18.dp))
        } else {
            Text(tr("Я тут. Что придумаем?", "I'm here. What shall we come up with?"), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }
        val starters = listOfNotNull(
            Triple(tr("Удиви меня", "Surprise me"), tr("Удиви меня", "Surprise me"), "chat_surprise"),
            Triple(tr("Сколько тебе лет?", "How old are you?"), tr("Сколько тебе лет?", "How old are you?"), null),
            Triple(tr("Что ты умеешь?", "What can you do?"), tr("Что ты умеешь?", "What can you do?"), null),
            if (compact) null else Triple(tr("Что ты помнишь?", "What do you remember?"), tr("Что ты помнишь", "What do you remember"), null),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEachIndexed { i, (label, message, tag) ->
                Appear(!still, delayMs = (if (compact) 0 else 280) + i * 70) {
                    Starter(label, spark = i == 0, enabled = enabled, tag = tag) { onSend(message) }
                }
            }
        }
        if (!compact) {
            Spacer(Modifier.height(16.dp))
            Text(tr("Таймеры, заметки и напоминания работают сразу и без интернета. Для свободной беседы подключи модель в «Настройках».",
                "Timers, notes and reminders work right away, offline. For free conversation, connect a model in “Settings”."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun Starter(label: String, spark: Boolean, enabled: Boolean, tag: String?, onClick: () -> Unit) {
    val c = iskraColors
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = if (spark) MaterialTheme.colorScheme.primaryContainer else c.herBubble,
        contentColor = if (spark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (spark) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (spark) {
                Icon(IskraIcons.Sparkle, contentDescription = null, tint = c.sparkGold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private enum class Action { SEND, MIC, LISTENING }

/**
 * A round field to write in and one button beside it: a microphone while it's empty, «send» once there's text, «stop»
 * with rings like sound while she listens. The button changes with a soft scale.
 */
@Composable
private fun InputBar(input: String, onInput: (String) -> Unit, busy: Boolean, listening: Boolean, still: Boolean,
                     onSend: () -> Unit, onMic: () -> Unit) {
    val c = iskraColors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = input, onValueChange = onInput, maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).clip(shape).background(c.inputBar)
                .border(1.dp, scheme.outlineVariant, shape).testTag("chat_input"),
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) Text(tr("Напиши Искре…", "Write to Iskra…"), style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant)
                    field()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        val mode = when { listening -> Action.LISTENING; input.isNotBlank() -> Action.SEND; else -> Action.MIC }
        AnimatedContent(targetState = mode, label = "action",
            // Not clipped: the rings while she listens reach beyond the button.
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) using SizeTransform(clip = false) }) { m ->
            when (m) {
                Action.SEND -> Round(c.userBubble, enabled = !busy, tag = "chat_send", label = tr("Отправить сообщение", "Send message"), onClick = onSend) {
                    Icon(IskraIcons.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Action.MIC -> Round(SolidColor(scheme.primaryContainer), enabled = !busy, tag = "chat_mic", label = tr("Голосовое сообщение", "Voice message"), onClick = onMic) {
                    Icon(IskraIcons.Mic, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
                Action.LISTENING -> {
                    val t = rememberSeconds(!still)
                    val ring = c.sparkPink
                    Round(SolidColor(scheme.secondary), enabled = true, tag = "chat_mic", label = tr("Остановить запись", "Stop recording"), onClick = onMic,
                        modifier = Modifier.drawBehind {
                            for (i in 0..1) {
                                val k = if (still) 0.5f else (t.value * 0.9f + i * 0.5f) % 1f
                                drawCircle(ring.copy(alpha = 0.4f * (1f - k)), radius = size.minDimension / 2f * (1f + 0.55f * k))
                            }
                        }) {
                        Icon(IskraIcons.Stop, contentDescription = null, tint = scheme.onSecondary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Round(brush: Brush, enabled: Boolean, tag: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  content: @Composable () -> Unit) {
    Box(modifier.size(52.dp).clip(CircleShape).background(brush, alpha = if (enabled) 1f else 0.45f)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) { content() }
}
