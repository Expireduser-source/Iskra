package ru.iskra.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.iskra.app.data.FreeBrain
import ru.iskra.core.i18n.tr

/**
 * «🆓 Бесплатный мозг»: the one-tap way to give Iskra a real conversation without buying a key.
 * Before anything opens, the owner reads what happens and what free models may do with the chat.
 */
@Composable
fun FreeBrainCard(onConnected: () -> Unit) {
    val ctx = LocalContext.current
    val state by FreeBrain.state.collectAsState()
    var explain by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(state) { if (state is FreeBrain.State.Done) onConnected() }

    Card(Modifier.fillMaxWidth().testTag("free_brain"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("🆓 Бесплатный «мозг» за минуту", "🆓 A free “brain” in a minute"), style = MaterialTheme.typography.titleMedium)
            when (val s = state) {
                FreeBrain.State.Idle, is FreeBrain.State.Failed -> {
                    Text(tr("Вход через OpenRouter — Google или почта. Ключ копировать не нужно: Искра получит его сама.",
                        "Sign in with OpenRouter — Google or e-mail. No key to copy: Iskra gets it herself."), fontSize = 13.sp)
                    if (s is FreeBrain.State.Failed) Text(s.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { explain = true }, modifier = Modifier.fillMaxWidth().testTag("free_brain_start")) {
                        Text(tr("Подключить бесплатно", "Connect for free"))
                    }
                }
                is FreeBrain.State.Waiting -> {
                    Text(if (s.manual) tr("Войди на странице OpenRouter, скопируй показанный код и вставь его сюда.",
                        "Sign in on the OpenRouter page, copy the code it shows and paste it here.")
                    else tr("Жду подтверждения на сайте OpenRouter… После входа нажми «Вернуться в Искру» или просто вернись сюда.",
                        "Waiting for OpenRouter… After signing in, tap “Back to Iskra” or just come back here."), fontSize = 13.sp)
                    if (s.manual) {
                        OutlinedTextField(code, { code = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(tr("Код со страницы", "Code from the page")) })
                        Button(enabled = code.length >= 8, onClick = { FreeBrain.paste(code); code = "" }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Подключить", "Connect"))
                        }
                    }
                    Row {
                        if (!s.manual) TextButton(onClick = { FreeBrain.manual(ctx) }) { Text(tr("Не вышло? Войти с кодом", "Didn't work? Use a code")) }
                        TextButton(onClick = { FreeBrain.cancel() }) { Text(tr("Отмена", "Cancel")) }
                    }
                }
                FreeBrain.State.Finishing -> Text(tr("Получаю ключ и выбираю бесплатную модель…", "Getting the key and choosing a free model…"), fontSize = 13.sp)
                is FreeBrain.State.Done -> {
                    Text(tr("Подключено ✓ Модель: ${s.model}. Бесплатно — примерно 50 сообщений в день.",
                        "Connected ✓ Model: ${s.model}. Free — about 50 messages a day."), fontSize = 13.sp)
                    TextButton(onClick = { FreeBrain.cancel() }) { Text(tr("Понятно", "Got it")) }
                }
            }
        }
    }

    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text(tr("Подключить через OpenRouter?", "Connect through OpenRouter?")) },
        text = {
            Text(tr(
                "Искра откроет сайт openrouter.ai. Регистрация бесплатная: вход через Google или почту. После входа Искра получит твой личный ключ " +
                    "и сохранит его зашифрованным на телефоне.\n\n" +
                    "• Бесплатно — примерно 50 сообщений в день; модели иногда меняются, Искра выберет новую сама.\n" +
                    "• Бесплатные модели могут сохранять переписку и учиться на ней. Не пиши им пароли и личное.\n" +
                    "• В облако уходят только твои сообщения Искре и нужные заметки памяти — как с любым «мозгом».\n" +
                    "• Отключить можно в любой момент: «Удалить сохранённый ключ», а сам ключ — на openrouter.ai/keys.",
                "Iskra will open openrouter.ai. Sign-up is free: Google or e-mail. After you sign in, Iskra gets your personal key " +
                    "and keeps it encrypted on the phone.\n\n" +
                    "• Free — about 50 messages a day; models change sometimes, Iskra picks a new one herself.\n" +
                    "• Free models may store the chat and learn from it. Don't tell them passwords or private things.\n" +
                    "• Only your messages to Iskra and the needed memory notes go to the cloud — as with any “brain”.\n" +
                    "• You can disconnect any time: “Delete the saved key”, and revoke the key itself on openrouter.ai/keys.",
            ), fontSize = 14.sp)
        },
        confirmButton = { TextButton(onClick = { explain = false; FreeBrain.start(ctx) }, modifier = Modifier.testTag("free_brain_go")) { Text(tr("Продолжить", "Continue")) } },
        dismissButton = { TextButton(onClick = { explain = false }) { Text(tr("Отмена", "Cancel")) } },
    )
}
