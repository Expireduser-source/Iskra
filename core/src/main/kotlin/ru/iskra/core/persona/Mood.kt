package ru.iskra.core.persona

import ru.iskra.core.i18n.tr
import kotlin.math.exp
import kotlin.math.ln

/** Visible emotions. The avatar and voice map these to animations/prosody. */
enum class Emotion { NEUTRAL, HAPPY, EXCITED, CURIOUS, SHY, POUTY, SAD, SURPRISED, SLEEPY, THINKING }

/**
 * Internal affective state. Valence/arousal move fast and decay to a baseline; bond moves slowly.
 * These are simulation parameters, not feelings.
 */
data class MoodState(
    val valence: Double = 0.2,   // -1 .. 1   unpleasant .. pleasant
    val arousal: Double = 0.4,   //  0 .. 1   calm .. excited
    val bond: Double = 0.1,      //  0 .. 1   familiarity with the owner (slow)
    val updatedAtMs: Long = 0L,
) {
    fun clamp() = copy(
        valence = valence.coerceIn(-1.0, 1.0),
        arousal = arousal.coerceIn(0.0, 1.0),
        bond = bond.coerceIn(0.0, 1.0),
    )

    /** Coarse emotion used when nothing more specific was expressed. */
    fun dominantEmotion(): Emotion = when {
        arousal < 0.15 -> Emotion.SLEEPY
        valence > 0.55 && arousal > 0.6 -> Emotion.EXCITED
        valence > 0.35 -> Emotion.HAPPY
        valence < -0.45 -> Emotion.SAD
        valence < -0.15 -> Emotion.POUTY
        arousal > 0.65 -> Emotion.CURIOUS
        else -> Emotion.NEUTRAL
    }

    fun describeForPrompt(): String {
        val v = when {
            valence > 0.5 -> tr("в отличном настроении", "in a great mood")
            valence > 0.15 -> tr("в хорошем настроении", "in a good mood")
            valence > -0.15 -> tr("в ровном настроении", "in an even mood")
            valence > -0.5 -> tr("немного надулась", "a little sulky")
            else -> tr("расстроена", "upset")
        }
        val a = when {
            arousal > 0.7 -> tr("очень оживлена", "very lively")
            arousal > 0.4 -> tr("бодра", "cheerful")
            arousal > 0.15 -> tr("спокойна", "calm")
            else -> tr("сонная", "sleepy")
        }
        val b = when {
            bond > 0.7 -> tr("вы давно и хорошо знакомы", "you two have known each other well for a long time")
            bond > 0.35 -> tr("вы уже неплохо знакомы", "you already know each other fairly well")
            else -> tr("вы знакомы недавно", "you met recently")
        }
        return tr("Сейчас ты $v, $a; $b.", "Right now you are $v, $a; $b.")
    }
}

sealed interface MoodEvent {
    /** sentiment in [-1, 1] estimated from the user's message. */
    data class UserMessage(val sentiment: Double) : MoodEvent
    data object Petted : MoodEvent
    data object Dragged : MoodEvent
    data object Flung : MoodEvent
    data class Absence(val hours: Double) : MoodEvent
    data object TaskSucceeded : MoodEvent
    data object TaskFailed : MoodEvent
    /** The model expressed this emotion in its reply; nudges the internal state towards it. */
    data class Expressed(val emotion: Emotion) : MoodEvent
}

class MoodEngine(
    private val traits: Traits,
    private val baseline: MoodState = MoodState(),
    /** Half-life of valence/arousal deviations from baseline. */
    private val halfLifeMinutes: Double = 45.0,
) {
    /** Exponential relaxation towards baseline; bond is not decayed here. */
    fun decay(state: MoodState, nowMs: Long): MoodState {
        if (state.updatedAtMs <= 0L) return state.copy(updatedAtMs = nowMs)
        val minutes = (nowMs - state.updatedAtMs).coerceAtLeast(0L) / 60_000.0
        val k = exp(-ln(2.0) * minutes / halfLifeMinutes)
        return state.copy(
            valence = baseline.valence + (state.valence - baseline.valence) * k,
            arousal = baseline.arousal + (state.arousal - baseline.arousal) * k,
            updatedAtMs = nowMs,
        ).clamp()
    }

    fun apply(state: MoodState, event: MoodEvent, nowMs: Long): MoodState {
        val s = decay(state, nowMs)
        val t = traits
        val next = when (event) {
            is MoodEvent.UserMessage -> s.copy(
                valence = s.valence + event.sentiment * (0.15 + 0.25 * t.sensitivity),
                arousal = s.arousal + 0.05 + 0.1 * kotlin.math.abs(event.sentiment),
                bond = s.bond + 0.002 + if (event.sentiment > 0.3) 0.004 else 0.0,
            )
            MoodEvent.Petted -> s.copy(valence = s.valence + 0.12 * (0.5 + t.warmth), arousal = s.arousal + 0.05, bond = s.bond + 0.003)
            MoodEvent.Dragged -> s.copy(valence = s.valence - 0.04 * (0.5 + t.sensitivity), arousal = s.arousal + 0.1)
            MoodEvent.Flung -> s.copy(valence = s.valence - 0.15 * (0.5 + t.sensitivity), arousal = s.arousal + 0.25)
            is MoodEvent.Absence -> {
                val h = event.hours.coerceAtLeast(0.0)
                // A bit of "missed you" excitement after a long break, capped.
                s.copy(arousal = s.arousal + (h / 24.0).coerceAtMost(0.3), valence = s.valence + (h / 48.0).coerceAtMost(0.15))
            }
            MoodEvent.TaskSucceeded -> s.copy(valence = s.valence + 0.08, bond = s.bond + 0.002)
            MoodEvent.TaskFailed -> s.copy(valence = s.valence - 0.08)
            is MoodEvent.Expressed -> when (event.emotion) {
                Emotion.HAPPY, Emotion.EXCITED -> s.copy(valence = s.valence + 0.05, arousal = s.arousal + 0.05)
                Emotion.SAD -> s.copy(valence = s.valence - 0.05)
                Emotion.POUTY -> s.copy(valence = s.valence - 0.03, arousal = s.arousal + 0.03)
                Emotion.SLEEPY -> s.copy(arousal = s.arousal - 0.05)
                Emotion.SURPRISED, Emotion.CURIOUS -> s.copy(arousal = s.arousal + 0.05)
                else -> s
            }
        }
        return next.copy(updatedAtMs = nowMs).clamp()
    }
}

/** Tiny Russian and English lexicon-based sentiment estimate. Good enough to drive mood; not an NLU. */
object Sentiment {
    private val positive = listOf(
        "спасиб", "молодец", "умниц", "люблю", "обожаю", "класс", "супер", "отличн", "круто", "хорош",
        "мило", "милая", "прекрасн", "благодар", "ура", "здорово", "лучшая", "❤", "😊", "😍", "👍",
        "thank", "good job", "well done", "love you", "i love", "awesome", "great", "amazing", "cool", "nice",
        "cute", "wonderful", "yay", "the best", "brilliant",
    )
    private val negative = listOf(
        "дура", "тупая", "тупица", "бесишь", "заткнись", "ненавиж", "отстань", "бесполезн", "ужасн",
        "плохо", "глупая", "идиот", "надоел", "👎", "😡",
        "stupid", "dumb", "shut up", "i hate", "hate you", "go away", "useless", "terrible", "awful", "idiot",
        "annoying", "you suck",
    )

    fun estimate(text: String): Double {
        val t = text.lowercase()
        val p = positive.count { t.contains(it) }
        val n = negative.count { t.contains(it) }
        if (p == 0 && n == 0) return 0.0
        return ((p - n).toDouble() / (p + n)).coerceIn(-1.0, 1.0)
    }
}
