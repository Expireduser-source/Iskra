package ru.iskra.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import ru.iskra.core.persona.Emotion

/** Cues from chat/voice to the on-screen character. */
sealed interface AvatarCue {
    data class Feel(val emotion: Emotion) : AvatarCue
    /** [lipSync]: the mouth will follow [Mouth] levels from real audio until [StopTalking]. */
    data class Talk(val text: String, val lipSync: Boolean = false) : AvatarCue
    data class Mouth(val level: Float) : AvatarCue
    data object StopTalking : AvatarCue
    data class Thinking(val on: Boolean) : AvatarCue
    data class Listening(val on: Boolean) : AvatarCue
    data class Say(val text: String) : AvatarCue   // speech bubble near the character
}

object CompanionBus {
    private val _cues = MutableSharedFlow<AvatarCue>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val cues: SharedFlow<AvatarCue> = _cues
    fun emit(cue: AvatarCue) { _cues.tryEmit(cue) }
}
