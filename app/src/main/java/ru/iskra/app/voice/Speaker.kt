package ru.iskra.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import java.util.Locale

/**
 * System TTS (offline if a Russian voice is installed). Drives the avatar's mouth via the bus.
 * Pitch is raised slightly for a lighter "character" voice; a neural cloud TTS can replace this later.
 */
class Speaker(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale.forLanguageTag("ru-RU"))
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setPitch(1.25f)
                t.setSpeechRate(1.05f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { CompanionBus.emit(AvatarCue.StopTalking) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { CompanionBus.emit(AvatarCue.StopTalking) }
                })
            }
        }
    }

    val isReady get() = ready

    fun speak(text: String): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val clean = text.replace(Regex("\\*[^*]{1,60}\\*"), " ").replace(Regex("[\\p{So}\\p{Cn}]"), " ").trim()
        if (clean.isEmpty()) return false
        t.speak(clean.take(3000), TextToSpeech.QUEUE_FLUSH, null, "iskra-${System.nanoTime()}")
        return true
    }

    fun stop() { tts?.stop() }
}
