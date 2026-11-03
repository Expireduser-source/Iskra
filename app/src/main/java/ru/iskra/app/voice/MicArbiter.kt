package ru.iskra.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tells the always-on ear to release the microphone while the chat's push-to-talk recogniser uses it. */
object MicArbiter {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    fun set(inUse: Boolean) { _busy.value = inUse }
}
