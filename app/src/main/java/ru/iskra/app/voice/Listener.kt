package ru.iskra.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wrapper over the system speech recognizer (on Xiaomi usually Google's service; may need internet
 * unless the offline Russian pack is installed). Must be used from the main thread of a visible Activity.
 */
class Listener(private val context: Context, private val onResult: (String) -> Unit, private val onState: (Boolean) -> Unit, private val onError: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onState(false)
            override fun onError(error: Int) {
                onState(false)
                onError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышала. Попробуй ещё раз."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Распознаванию речи нужен интернет или офлайн-пакет русского языка."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят."
                    else -> "Ошибка распознавания ($error)."
                })
            }
            override fun onResults(results: Bundle?) {
                onState(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    fun stop() { recognizer?.stopListening() }

    fun destroy() { recognizer?.destroy(); recognizer = null }
}
