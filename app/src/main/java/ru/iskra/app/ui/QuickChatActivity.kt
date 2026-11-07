package ru.iskra.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.iskra.app.IskraApp
import ru.iskra.app.data.ProviderPreset
import ru.iskra.app.diag.Diag
import ru.iskra.core.i18n.tr
import ru.iskra.core.skills.SharedText

/**
 * Floating compact chat over the current app. Opens when the character is tapped, from the launcher
 * shortcuts («Спросить», «Голосом», «Напоминание»), from "Share → Iskra" and from "Ask Iskra" in the
 * text-selection menu of any app. Shared text is only shown with actions; nothing is sent until the
 * owner taps one.
 */
class QuickChatActivity : ComponentActivity() {
    private var shared by mutableStateOf<String?>(null)
    private var prefill by mutableStateOf("")
    private var voice by mutableStateOf(false)
    /** A new intent restarts the chat screen state (prefill, voice). */
    private var generation by mutableIntStateOf(0)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) read(intent)
        setContent {
            IskraTheme {
                val outside = remember { MutableInteractionSource() }
                val inside = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .clickable(interactionSource = outside, indication = null) { finish() }
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // The sheet rises from the bottom with a soft spring; her header is in it (ChatScreen).
                    val rise = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(rise, enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 520f)) { it / 2 } + fadeIn(tween(180))) {
                        val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        Column(
                            Modifier.fillMaxWidth().fillMaxHeight(if (shared != null) 0.72f else 0.58f)
                                .clickable(interactionSource = inside, indication = null) { }
                                .shadow(18.dp, sheet)
                                .background(MaterialTheme.colorScheme.background, sheet)
                                .padding(top = 6.dp)
                        ) {
                            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 2.dp).size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                            key(generation) {
                                ChatScreen(Modifier.weight(1f), compact = true, initialInput = prefill, autoVoice = voice, onClose = { finish() }, top = {
                                    shared?.let { text ->
                                        SharedTextCard(text,
                                            onSend = { msg -> shared = null; IskraApp.instance.chat.send(msg) },
                                            onEdit = { msg -> shared = null; prefill = msg; generation++ },
                                            onClose = { shared = null })
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(i: Intent?) {
        voice = false
        prefill = ""
        when (i?.action) {
            Intent.ACTION_SEND -> shared = SharedText.clean(i.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra(Intent.EXTRA_SUBJECT))
            Intent.ACTION_PROCESS_TEXT -> shared = SharedText.clean(i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            ACTION_VOICE -> voice = true
            ACTION_REMIND -> prefill = tr("Напомни через 10 минут ", "Remind me in 10 minutes to ")
        }
        if (i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_PROCESS_TEXT) {
            // Length only: the text itself never goes to the journal.
            Diag.i("share", tr("Текст из другого приложения: ", "Text from another app: ") + (shared?.length ?: 0) + tr(" симв.", " chars"))
        }
        generation++
    }

    companion object {
        const val ACTION_ASK = "ru.iskra.app.action.ASK"
        const val ACTION_VOICE = "ru.iskra.app.action.VOICE"
        const val ACTION_REMIND = "ru.iskra.app.action.REMIND"
    }
}

/** The shared text and what Iskra can do with it. */
@androidx.compose.runtime.Composable
private fun SharedTextCard(text: String, onSend: (String) -> Unit, onEdit: (String) -> Unit, onClose: () -> Unit) {
    // A cloud brain or the model on the phone can explain and summarize; without either, say how to get one.
    val brain = remember { IskraApp.instance.settings.load().preset != ProviderPreset.OFFLINE || ru.iskra.app.data.LocalBrain.provider() != null }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("shared_card")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Текст из другого приложения", "Text from another app"), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(tr("Скрыть", "Hide")) }
            }
            Text(text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SharedText.actions(text, brain).forEach { a ->
                    OutlinedButton(onClick = {
                        val msg = SharedText.message(a, text)
                        if (SharedText.editFirst(a)) onEdit(msg) else onSend(msg)
                    }, modifier = Modifier.testTag("share_" + a.name.lowercase())) { Text(SharedText.title(a)) }
                }
            }
            if (!brain) Text(SharedText.needsBrainHint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
