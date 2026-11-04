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
    private var path: String? = null
    private var users = 0

    @Synchronized
    fun acquire(ctx: Context): Model {
        val want = SpeechModel.dir(ctx).absolutePath
        // The UI language changed (another model) and nobody uses the old one: load the new one.
        if (model != null && path != want && users == 0) { runCatching { model?.close() }; model = null }
        val m = model ?: Model(want).also { model = it; path = want }
        users++
        return m
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) { runCatching { model?.close() }; model = null }
    }
}
