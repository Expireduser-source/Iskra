package ru.iskra.app.voice

import android.content.Context
import org.vosk.Model

/**
 * One loaded speech model for the always-on ear and chat dictation. Loading it twice would double
 * its native memory (≈100 MB), which low-RAM phones do not have. Recognizers on different threads
 * may share one model. Freed when the last user releases it.
 */
object VoskModels {
    private var model: Model? = null
    private var users = 0

    @Synchronized
    fun acquire(ctx: Context): Model {
        val m = model ?: Model(SpeechModel.dir(ctx).absolutePath).also { model = it }
        users++
        return m
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) { runCatching { model?.close() }; model = null }
    }
}
