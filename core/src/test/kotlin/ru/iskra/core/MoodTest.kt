package ru.iskra.core

import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.MoodEngine
import ru.iskra.core.persona.MoodEvent
import ru.iskra.core.persona.MoodState
import ru.iskra.core.persona.Personas
import ru.iskra.core.persona.Sentiment
import ru.iskra.core.persona.StyleFeedback
import ru.iskra.core.persona.StyleProfile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoodTest {
    private val engine = MoodEngine(Personas.ISKRA.traits)

    @Test fun praiseRaisesValenceAndInsultLowersIt() {
        val s0 = MoodState(updatedAtMs = 1)
        val up = engine.apply(s0, MoodEvent.UserMessage(Sentiment.estimate("Спасибо, ты умница!")), 1)
        val down = engine.apply(s0, MoodEvent.UserMessage(Sentiment.estimate("ты тупая")), 1)
        assertTrue(up.valence > s0.valence)
        assertTrue(down.valence < s0.valence)
    }

    @Test fun decaysTowardsBaseline() {
        val excited = MoodState(valence = 1.0, arousal = 1.0, updatedAtMs = 0L + 1)
        val later = engine.decay(excited, 1 + 10 * 3_600_000L)
        assertTrue(abs(later.valence - MoodState().valence) < 0.01, "$later")
    }

    @Test fun bondGrowsSlowlyAndIsBounded() {
        var s = MoodState(updatedAtMs = 1)
        repeat(10_000) { s = engine.apply(s, MoodEvent.UserMessage(1.0), 1) }
        assertEquals(1.0, s.bond)
        assertTrue(s.valence <= 1.0)
    }

    @Test fun dominantEmotion() {
        assertEquals(Emotion.SLEEPY, MoodState(arousal = 0.05).dominantEmotion())
        assertEquals(Emotion.SAD, MoodState(valence = -0.8).dominantEmotion())
    }

    @Test fun styleFeedback() {
        val p = assertNotNull(StyleFeedback.detect("отвечай покороче, пожалуйста", StyleProfile()))
        assertTrue(p.verbosity < 0)
        assertNull(StyleFeedback.detect("как погода?", StyleProfile()))
        val q = assertNotNull(StyleFeedback.detect("хватит мяукать", StyleProfile()))
        assertTrue(q.catSpeech < 0)
    }
}
