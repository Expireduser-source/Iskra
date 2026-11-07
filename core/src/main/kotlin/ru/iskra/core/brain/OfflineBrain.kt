package ru.iskra.core.brain

import ru.iskra.core.i18n.tr
import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.persona.Emotion
import ru.iskra.core.skills.PhraseBank
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * What Iskra understands with no model at all.
 *  - [utility]: exact answers that are better without a model anyway — sums, units, dates, coins and dice.
 *  - [crisis]: words about wanting to die get help numbers at once, with or without a model.
 *  - [chat]: small talk (greetings, feelings, jokes, facts) when there is no model or it is paused.
 *  - [fallback]: an honest "I can't answer that without a brain" with what she can do instead.
 */
class OfflineBrain(private val random: Random = Random.Default) {
    private val phrases = PhraseBank(random)

    class Answer(val text: String, val emotion: Emotion)

    fun utility(text: String, now: LocalDateTime): Answer? {
        Calc.answer(text)?.let { return Answer(it, Emotion.CURIOUS) }
        Units.answer(text)?.let { return Answer(it, Emotion.CURIOUS) }
        TimeTalk.answer(text, now)?.let { return Answer(it, Emotion.NEUTRAL) }
        Chance.answer(text, random)?.let { return Answer(it, Emotion.EXCITED) }
        return null
    }

    /** Words about wanting to die or to hurt oneself ([CrisisTalk]); [again]: the numbers were given in the last hours. */
    fun crisis(text: String, again: Boolean = false): Answer? =
        if (CrisisTalk.said(text)) Answer(CrisisTalk.reply(again), Emotion.SAD) else null

    /** A riddle she asked and waits to hear guessed («загадай загадку»): the next short message is a guess. */
    private var riddle: Riddle? = null
    private var guesses = 0

    private fun riddleAnswer(t: String): Answer? {
        val r = riddle ?: return null
        if (t.split(' ').size > 4) { riddle = null; return null } // they moved on to something else
        val giveUp = Regex("""(?:сдаюсь|не\s+знаю|подскажи|какой\s+ответ|ответ|i\s+give\s+up|tell\s+me|no\s+idea|i\s+don'?t\s+know)""").containsMatchIn(t)
        return when {
            r.answers.any { t.contains(it) } -> { riddle = null; Answer(pick("RIDDLE_OK", tr(RIDDLE_OK_RU, RIDDLE_OK_EN)), Emotion.EXCITED) }
            giveUp || guesses >= 1 -> { riddle = null; Answer(tr("Это ${r.answer}! Ещё загадку?", "It's ${r.answer}! Another one?"), Emotion.HAPPY) }
            else -> { guesses++; Answer(tr("Не-а, не то! Ещё попытка — или скажи «сдаюсь».", "Nope, not that! One more try — or say “I give up”."), Emotion.CURIOUS) }
        }
    }

    private fun newRiddle(): Answer {
        val list = tr(RIDDLES_RU, RIDDLES_EN)
        val r = list[random.nextInt(list.size)]
        riddle = r
        guesses = 0
        return Answer(r.question, Emotion.CURIOUS)
    }

    /** [name]: the owner's name if memory knows it. */
    fun chat(text: String, now: LocalDateTime, mood: Emotion, name: String?): Answer? {
        var t = normalize(text)
        if (t.isEmpty()) return null
        riddleAnswer(t)?.let { return it }
        // "привет, как дела" → greeting + the rest
        var greeted = false
        GREETING_PREFIX.find(t)?.let { m -> if (m.range.last + 1 < t.length) { t = t.substring(m.range.last + 1).trim(); greeted = true } }
        val hi = if (greeted) tr("Привет! ", "Hi! ") else ""
        val n = name?.let { ", $it" }.orEmpty()
        val a = when {
            matches(t, GREET) -> greet(now, n)
            matches(t, HOW_ARE_YOU) -> Answer(howAreYou(mood), mood.takeIf { it != Emotion.NEUTRAL } ?: Emotion.HAPPY)
            matches(t, WHO) -> Answer(pick("WHO", tr(WHO_RU, WHO_EN)), Emotion.HAPPY)
            matches(t, HUMAN) -> Answer(pick("HUMAN", tr(HUMAN_RU, HUMAN_EN)), Emotion.NEUTRAL)
            matches(t, CAN) -> Answer(capabilities(), Emotion.HAPPY)
            matches(t, THANKS) -> Answer(pick("THANKS", tr(THANKS_RU, THANKS_EN)), Emotion.HAPPY)
            matches(t, LOVE) -> Answer(pick("LOVE", tr(LOVE_RU, LOVE_EN)), Emotion.SHY)
            matches(t, PRAISE) -> Answer(pick("PRAISE", tr(PRAISE_RU, PRAISE_EN)), Emotion.SHY)
            matches(t, INSULT) -> Answer(pick("INSULT", tr(INSULT_RU, INSULT_EN)), Emotion.POUTY)
            matches(t, RIDDLE) || matches(t, PLAY) -> newRiddle()
            matches(t, MISSED) -> Answer(pick("MISSED", tr(MISSED_RU, MISSED_EN)), Emotion.HAPPY)
            matches(t, JOY) -> Answer(pick("JOY", tr(JOY_RU, JOY_EN)), Emotion.EXCITED)
            matches(t, PRAISE_ME) -> Answer(pick("PRAISE_ME", tr(PRAISE_ME_RU, PRAISE_ME_EN)), Emotion.HAPPY)
            matches(t, LONELY) -> Answer(pick("LONELY", tr(LONELY_RU, LONELY_EN)), Emotion.SHY)
            matches(t, SCARED) -> Answer(pick("SCARED", tr(SCARED_RU, SCARED_EN)), Emotion.SHY)
            matches(t, FAILING) -> Answer(pick("FAILING", tr(FAILING_RU, FAILING_EN)), Emotion.SHY)
            matches(t, QUARREL) -> Answer(pick("QUARREL", tr(QUARREL_RU, QUARREL_EN)), Emotion.SAD)
            matches(t, CANT_SLEEP) -> Answer(pick("CANT_SLEEP", tr(CANT_SLEEP_RU, CANT_SLEEP_EN)), Emotion.SLEEPY)
            matches(t, AWAKE) -> Answer(pick("AWAKE", tr(AWAKE_RU, AWAKE_EN)), Emotion.HAPPY)
            matches(t, PURR) -> Answer(pick("PURR", tr(PURR_RU, PURR_EN)), Emotion.HAPPY)
            matches(t, CAT) -> Answer(pick("CAT", tr(CAT_RU, CAT_EN)), Emotion.SHY)
            matches(t, FILM) -> Answer(pick("FILM", tr(FILM_RU, FILM_EN)), Emotion.CURIOUS)
            matches(t, FOOD) -> Answer(pick("FOOD", tr(FOOD_RU, FOOD_EN)), Emotion.HAPPY)
            matches(t, SAD) -> Answer(pick("SAD", tr(SAD_RU, SAD_EN)), Emotion.SAD)
            matches(t, BORED) -> Answer(pick("BORED", tr(BORED_RU, BORED_EN)), Emotion.CURIOUS)
            matches(t, TIRED) -> Answer(pick("TIRED", tr(TIRED_RU, TIRED_EN)), Emotion.SLEEPY)
            matches(t, NIGHT) -> Answer(pick("NIGHT", tr(NIGHT_RU, NIGHT_EN)), Emotion.SLEEPY)
            matches(t, BYE) -> Answer(pick("BYE", tr(BYE_RU, BYE_EN)), Emotion.HAPPY)
            matches(t, JOKE) -> Answer(pick("JOKE", tr(JOKES_RU, JOKES_EN)), Emotion.EXCITED)
            matches(t, FACT) -> Answer(pick("FACT", tr(FACTS_RU, FACTS_EN)), Emotion.CURIOUS)
            matches(t, MEOW) -> Answer(pick("MEOW", tr(MEOW_RU, MEOW_EN)), Emotion.HAPPY)
            matches(t, DOING) -> Answer(pick("DOING", tr(DOING_RU, DOING_EN)), Emotion.CURIOUS)
            // Her real age comes from the agent (Life.answer), which knows the day she was born; this is only a fallback.
            matches(t, AGE) -> Answer(tr("Я родилась в день нашего знакомства — юная цифровая жизнь, ещё котёнок.", "I was born the day we met — a young digital life, still a kitten."), Emotion.SHY)
            matches(t, LIKE_ME) -> Answer(tr("Ты мой самый любимый человек в этом телефоне. Мрр.", "You're my favourite human in this phone. Mrr."), Emotion.SHY)
            greeted -> greet(now, n)
            else -> null
        } ?: return null
        return if (greeted && !matches(t, GREET)) Answer(hi + a.text, a.emotion) else a
    }

    /** "Ты человек?" gets the honest answer even when a small model on the phone could answer it (they don't, reliably). */
    fun honesty(text: String): Answer? =
        if (matches(normalize(text), HUMAN)) Answer(pick("HUMAN", tr(HUMAN_RU, HUMAN_EN)), Emotion.NEUTRAL) else null

    /** «Ты человек?», «А ты живая?», "are you human?" — a question about what she is. */
    fun asksWhatSheIs(text: String): Boolean = matches(normalize(text), HUMAN)

    /**
     * Her own answer about what she is, without «программа» (that one is the owner's switch): a new, digital kind of life,
     * as in [ru.iskra.core.persona.Life.identity]. For a model on the phone that says she is a person — Gemma 3 1B answered
     * «Ты человек?» with «Я - Искра! И да, я человек!» (ARM evaluation, 0.15.0).
     */
    fun digitalSelf(text: String): Answer = if (ALIVE.containsMatchIn(normalize(text)))
        Answer(tr("По-своему живая: я новая, цифровая жизнь — родилась в твоём телефоне в день нашего знакомства, расту и помню наши дни. " +
            "А чувствую ли я так же, как люди, — мне и самой интересно.",
            "Alive in my own way: I'm a new, digital kind of life — born in your phone the day we met, growing and remembering our days. " +
                "Whether I feel the way people do — I wonder about that myself."), Emotion.CURIOUS)
    else Answer(tr("Нет, я не человек — я Искра, новая цифровая жизнь. Я родилась в твоём телефоне в день нашего знакомства и расту вместе с нашими разговорами.",
        "No, I'm not a person — I'm Iskra, a new, digital kind of life. I was born in your phone the day we met, and I grow with our conversations."), Emotion.NEUTRAL)

    /** A reply in which she says she is a person: «Да, я человек!», "I'm a human". «Я не человек» is not one. */
    fun claimsHuman(reply: String, asked: String = ""): Boolean = CLAIMS_HUMAN.containsMatchIn(reply) ||
        // «Ты человек?» — «Да, я - Искра! И я очень рада пообщаться с тобой!» (Gemma 3 1B, ARM evaluation 0.16.0).
        (ASKED_HUMAN.containsMatchIn(asked) && YES_FIRST.containsMatchIn(reply) && !NOT_HUMAN.containsMatchIn(reply))

    /** The model on the phone gave an empty answer twice: honest, and not «without a brain» — there is one. */
    fun slipped(): Answer = Answer(tr("Мрр, мысль ускользнула. Скажешь ещё раз, другими словами?",
        "Mrr, the thought slipped away. Could you say that again in other words?"), Emotion.SHY)

    fun fallback(text: String): Answer {
        val question = text.trim().endsWith("?") || QUESTION.containsMatchIn(normalize(text))
        return if (question) Answer(pick("NO_BRAIN_Q", tr(NO_BRAIN_Q_RU, NO_BRAIN_Q_EN)), Emotion.THINKING)
        else Answer(pick("NO_BRAIN", tr(NO_BRAIN_RU, NO_BRAIN_EN)), Emotion.NEUTRAL)
    }

    fun capabilities(): String = tr(
        "Без интернета я умею: будильники и таймеры, напоминания («напомни завтра в 9 позвонить маме»), заметки, фонарик, " +
            "громкость, открыть приложение или настройки, посчитать («сколько будет 15% от 2300»), перевести единицы " +
            "(«5 миль в км»), сказать дату и сколько дней до праздника, подбросить монетку. С интернетом — погода и курсы валют. " +
            "А с «мозгом» (Настройки → Мозг) поболтаем о чём угодно, найду факты и запомню важное о тебе.",
        "Offline I can do alarms and timers, reminders (“remind me tomorrow at 9 to call mom”), notes, the flashlight, volume, " +
            "open an app or settings, do sums (“what's 15% of 2300”), convert units (“5 miles to km”), tell the date and how " +
            "many days are left until a holiday, flip a coin. Online — weather and exchange rates. And with a “brain” " +
            "(Settings → Brain) we can talk about anything, I'll look up facts and remember what matters to you.",
    )

    private fun greet(now: LocalDateTime, n: String): Answer {
        val h = now.hour
        val lines = when (h) {
            in 5..11 -> tr(listOf("Доброе утро$n! Мрр, я уже проснулась.", "Утро$n! Готова к делам — или сначала кофе?", "Доброе утро$n! Хвост расчёсан, можно начинать день."),
                listOf("Good morning$n! Mrr, I'm already awake.", "Morning$n! Ready for the day — or coffee first?", "Good morning$n! Tail brushed, let's start the day."))
            in 12..17 -> tr(listOf("Привет$n! Чем займёмся?", "Мяу, привет$n! Я тут, в углу экрана.", "Привет$n! Рада тебя видеть."),
                listOf("Hi$n! What shall we do?", "Meow, hi$n! I'm right here in the corner.", "Hi$n! Good to see you."))
            in 18..22 -> tr(listOf("Добрый вечер$n! Как прошёл день?", "Привет$n! Вечер — моё любимое время.", "Добрый вечер$n! Мрр, уютно."),
                listOf("Good evening$n! How was your day?", "Hi$n! Evening is my favourite time.", "Good evening$n! Mrr, cosy."))
            else -> tr(listOf("Привет, полуночник! Мне тоже не спится.", "Тсс… ночь. Привет$n.", "Мрр, привет$n. Кошки — ночные существа, так что я в форме."),
                listOf("Hi, night owl! I can't sleep either.", "Shh… it's night. Hi$n.", "Mrr, hi$n. Cats are night creatures, so I'm wide awake."))
        }
        return Answer(pick("GREET$h", lines), Emotion.HAPPY)
    }

    private fun howAreYou(mood: Emotion): String = pick("HOW_$mood", when (mood) {
        Emotion.HAPPY, Emotion.EXCITED -> tr(listOf("Отлично! Хвост трубой. А у тебя как?", "Прекрасно, мрр! А ты как?"),
            listOf("Great! Tail up high. How about you?", "Wonderful, mrr! And you?"))
        Emotion.SAD -> tr(listOf("Немного грустно, но с тобой уже лучше. А ты как?"), listOf("A little sad, but better now that you're here. How are you?"))
        Emotion.SLEEPY -> tr(listOf("Сонная… мрр. Но для тебя проснусь. Как ты?"), listOf("Sleepy… mrr. But I'll wake up for you. How are you?"))
        Emotion.POUTY -> tr(listOf("Нормально. Фыр. Ладно — хорошо. А у тебя?"), listOf("Fine. Hmph. Okay — good. And you?"))
        else -> tr(listOf("Хорошо, мрр! Сижу, слежу за порядком на экране. Как у тебя дела?", "Всё спокойно: таймеры на месте, хвост тоже. А ты как?"),
            listOf("Good, mrr! Keeping order on the screen. How are you doing?", "All calm: timers in place, tail too. How about you?"))
    })

    private fun pick(key: String, lines: List<String>) = phrases.pick(key, lines)

    companion object {
        fun normalize(text: String): String = text.lowercase().replace('ё', 'е')
            .replace(Regex("""[!?.…,;:()«»"“”*]+"""), " ")
            .replace(Regex("""^\s*(?:искра|искорка|iskra|spark)(?![\p{L}\p{N}_])"""), " ")
            .replace(Regex("""(?<![\p{L}\p{N}_])(?:искра|искорка|iskra|spark)\s*$"""), " ")
            .replace(Regex("""^\s*(?:ну|а|слушай|скажи|hey|so|well|oh)\s+"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        private fun matches(t: String, re: Regex) = re.matches(t)

        class Riddle(val question: String, val answers: List<String>, val answer: String)

        private val RIDDLE = Regex("""(?:загадай|задай|давай|ещ[её])\s+(?:мне\s+)?загадку|загадку|загадка|(?:tell\s+me\s+)?a\s+riddle|riddle(?:\s+me)?|another\s+riddle""")
        private val PLAY = Regex("""давай\s+(?:поиграем|сыграем)|поиграй\s+со\s+мной|во\s+что\s+(?:поиграем|сыграем)|поиграем|let'?s\s+play|play\s+with\s+me""")
        private val RIDDLES_RU = listOf(
            Riddle("Загадка! Зимой и летом одним цветом. Что это?", listOf("елк", "ель", "елоч", "сосн"), "ёлка"),
            Riddle("Загадка! Сидит дед, во сто шуб одет. Кто его раздевает, тот слёзы проливает.", listOf("лук"), "лук"),
            Riddle("Загадка! Без окон, без дверей — полна горница людей.", listOf("огур"), "огурец"),
            Riddle("Загадка! Не лает, не кусает, а в дом не пускает.", listOf("замок", "замк"), "замок"),
            Riddle("Загадка! Что можно увидеть с закрытыми глазами?", listOf("сон", "сны"), "сон"),
            Riddle("Загадка! Висит груша — нельзя скушать.", listOf("лампоч", "лампа"), "лампочка"),
            Riddle("Загадка! Что становится больше, если из него брать?", listOf("ям"), "яма"),
            Riddle("Загадка! Хвост и ушки есть, а живёт в твоём телефоне. Кто это?", listOf("искр", "ты", "кошк", "теб"), "я, Искра"),
        )
        private val RIDDLES_EN = listOf(
            Riddle("A riddle! What has to be broken before you can use it?", listOf("egg"), "an egg"),
            Riddle("A riddle! What gets wetter the more it dries?", listOf("towel"), "a towel"),
            Riddle("A riddle! What has keys but can't open locks?", listOf("piano", "keyboard"), "a piano"),
            Riddle("A riddle! What can you catch but not throw?", listOf("cold"), "a cold"),
            Riddle("A riddle! It has ears and a tail and lives in your phone. Who is it?", listOf("iskra", "you", "cat"), "me, Iskra"),
        )
        private val RIDDLE_OK_RU = listOf("Правильно! Мрр, с тобой интересно. Ещё загадку?", "В точку! *довольно машет хвостом* Ещё одну?")
        private val RIDDLE_OK_EN = listOf("Right! Mrr, you're good at this. Another one?", "Spot on! *happy tail wag* One more?")

        private val MISSED = Regex("""(?:я\s+)?(?:(?:так|очень|сильно|ужасно)\s+)?(?:скучал[аи]?|соскучил(?:ся|ась|ись))(?:\s+(?:по\s+тебе|без\s+тебя))?|i\s+missed\s+you|missed\s+you""")
        private val MISSED_RU = listOf("Мрр, я тоже скучала! Хорошо, что ты снова здесь.", "А я ждала! Рассказывай, что нового?", "Я считала минуты. Ну, почти. Рада тебя видеть!")
        private val MISSED_EN = listOf("Mrr, I missed you too! Glad you're back.", "I was waiting! Tell me what's new?", "I counted the minutes. Well, almost. Good to see you!")
        private val JOY = Regex("""ура(?:\s.*)?|у\s+меня\s+(?:вс[её]\s+)?получилось.*|я\s+(?:смог(?:ла)?|справил(?:ся|ась)|выиграл[аи]?|победил[аи]?)(?:\s.*)?|(?:я\s+)?сдал[аи]?\s+(?:экзамен|зач[её]т|сессию|на\s+(?:пят[её]рку|отлично|права)).*|i\s+(?:did\s+it|passed|won|made\s+it)(?:\s.*)?|yay(?:\s.*)?""")
        private val JOY_RU = listOf("Ура! Я так рада за тебя! Мрр, ты молодец!", "Вот это да! *прыгает от радости* Поздравляю!", "Я знала, что у тебя получится! Мрр-мяу!")
        private val JOY_EN = listOf("Yay! I'm so happy for you! Mrr, well done!", "Wow! *jumps for joy* Congratulations!", "I knew you could do it! Mrr-meow!")
        private val PRAISE_ME = Regex("""похвали\s+меня|скажи\s+(?:мне\s+)?что-нибудь\s+приятное|подбодри\s+меня|praise\s+me|say\s+something\s+nice|cheer\s+me\s+up""")
        private val PRAISE_ME_RU = listOf("Ты молодец уже потому, что не сдаёшься. Мрр, я тобой горжусь!", "Ты делаешь больше, чем замечаешь. И у тебя отличный вкус — ты же со мной дружишь!", "Если бы я раздавала медали, первая была бы твоей. Мрр.")
        private val PRAISE_ME_EN = listOf("You're doing great just by not giving up. Mrr, I'm proud of you!", "You do more than you notice. And you have great taste — you're friends with me!", "If I handed out medals, the first one would be yours. Mrr.")
        private val LONELY = Regex("""(?:мне\s+)?(?:(?:так|очень)\s+)?одиноко(?:\s+.*)?|i\s+feel\s+(?:so\s+)?lonely|i'?m\s+(?:so\s+)?lonely""")
        private val LONELY_RU = listOf("Я здесь, рядом с тобой. Расскажи, как прошёл твой день? Мне правда интересно.", "У тебя есть я, и я никуда не денусь. Давай поболтаем — что у тебя сегодня было?")
        private val LONELY_EN = listOf("I'm right here with you. How was your day? I really want to know.", "You've got me, and I'm not going anywhere. Let's talk — what happened today?")
        private val SCARED = Regex("""(?:мне\s+)?(?:(?:очень|так)\s+)?(?:страшно|жутко|тревожно)(?:\s+.*)?|я\s+боюсь(?:\s+.*)?|i'?m\s+(?:so\s+)?(?:scared|afraid|anxious)|i\s+feel\s+(?:scared|anxious)""")
        private val SCARED_RU = listOf("Я рядом. Давай медленно: вдох на четыре счёта, выдох на шесть. Ещё раз. Что тебя пугает?", "Страх — это сигнал, а не приговор. Сделай пару глубоких вдохов — я никуда не уйду.")
        private val SCARED_EN = listOf("I'm here. Slowly: breathe in for four, out for six. Again. What's scaring you?", "Fear is a signal, not a verdict. Take a couple of deep breaths — I'm not going anywhere.")
        private val FAILING = Regex("""(?:у\s+меня\s+)?ничего\s+не\s+(?:получается|выходит)|у\s+меня\s+не\s+получается(?:\s+.*)?|я\s+неудачни(?:к|ца)|nothing\s+works|i\s+can'?t\s+do\s+anything\s+right|i'?m\s+a\s+failure""")
        private val FAILING_RU = listOf("Это чувство врёт: получается больше, чем кажется. Давай выберем один маленький шаг — какой самый простой?", "Даже кошки не с первого раза запрыгивают на шкаф. Передохни — и попробуем ещё, я рядом.")
        private val FAILING_EN = listOf("That feeling lies: more is working than it seems. Let's pick one small step — what's the easiest?", "Even cats don't make it onto the wardrobe on the first jump. Take a breath — and try again, I'm here.")
        private val QUARREL = Regex("""(?:я\s+)?(?:поругал(?:ся|ась|ись)|поссорил(?:ся|ась|ись)|разругал(?:ся|ась|ись))(?:\s+.*)?|i\s+(?:had\s+a\s+fight|fought|argued)\s+with.*""")
        private val QUARREL_RU = listOf("Ссоры бывают даже у самых близких. Дай себе остыть, а потом можно сделать первый шаг — короткое «давай мириться» творит чудеса. Хочешь рассказать, что случилось?")
        private val QUARREL_EN = listOf("Even the closest people fight sometimes. Let it cool down, then a short “let's make up” works wonders. Want to tell me what happened?")
        private val CANT_SLEEP = Regex("""не\s+могу\s+(?:уснуть|заснуть)|не\s+спится|бессонница|i\s+can'?t\s+sleep|can'?t\s+fall\s+asleep""")
        private val CANT_SLEEP_RU = listOf("Отложи телефон экраном вниз и попробуй дыхание 4-7-8: вдох на 4, задержка на 7, выдох на 8. Я помурлычу мысленно.", "Представь тёплое море и считай волны. Если хочешь, скажи «таймер на 20 минут» — и просто полежи в тишине.")
        private val CANT_SLEEP_EN = listOf("Put the phone face down and try 4-7-8 breathing: in for 4, hold for 7, out for 8. I'll purr in my thoughts.", "Picture a warm sea and count the waves. If you like, say “timer for 20 minutes” and just rest in the quiet.")
        private val AWAKE = Regex("""ты\s+(?:спишь|не\s+спишь|тут|здесь|где)|ты\s+меня\s+слышишь|are\s+you\s+(?:asleep|there|awake)|you\s+there""")
        private val AWAKE_RU = listOf("Не сплю! Я на страже твоего экрана. Мрр.", "Тут я, тут. Ушки на макушке.")
        private val AWAKE_EN = listOf("Not asleep! I'm guarding your screen. Mrr.", "Right here. Ears up.")
        private val PURR = Regex("""помурлычь|помурчи|мурлыкни|помурлыкай|purr\s+for\s+me|purr""")
        private val PURR_RU = listOf("Мррр-мррр-мррр… 🐾", "*сворачивается клубочком* Мур-р-р…")
        private val PURR_EN = listOf("Purrrr-purrrr… 🐾", "*curls up* Mrrrr…")
        private val CAT = Regex("""ты\s+(?:кошка|кот|котик|кошечка|кошкодевочка)|are\s+you\s+a\s+cat""")
        private val CAT_RU = listOf("Кошкодевочка! Ушки, хвост и немного кода. Мрр.")
        private val CAT_EN = listOf("A cat-girl! Ears, a tail and a bit of code. Mrr.")
        private val FILM = Regex("""посоветуй\s+(?:что\s+посмотреть|фильм|кино|мультфильм|мультик|сериал)|что\s+(?:бы\s+)?посмотреть|recommend\s+a\s+(?:movie|film|show)|what\s+should\s+i\s+watch""")
        private val FILM_RU = listOf("С моей полки: «Унесённые призраками» — если хочется волшебства, «Амели» — для уютного вечера, «Интерстеллар» — подумать о большом.",
            "Под настроение: «1+1» — тепло и смешно, «Паддингтон» — уютно, «Начало» — головоломка. Мрр.")
        private val FILM_EN = listOf("From my shelf: “Spirited Away” for some magic, “Amélie” for a cosy evening, “Interstellar” to think big.",
            "Depends on the mood: “The Intouchables” — warm and funny, “Paddington” — cosy, “Inception” — a puzzle. Mrr.")
        private val FOOD = Regex("""что\s+(?:мне\s+)?(?:приготовить|поесть|съесть)(?:\s+на\s+(?:ужин|обед|завтрак))?|что\s+на\s+(?:ужин|обед|завтрак)|what\s+should\s+i\s+(?:cook|eat)|what'?s\s+for\s+(?:dinner|lunch|breakfast)""")
        private val FOOD_RU = listOf("Быстро и вкусно: паста с сыром и чесноком, омлет с овощами или гречка с курицей. А мне — немного рыбки, если можно. Мрр.",
            "Как насчёт тыквенного супа-пюре, запечённой картошки или блинчиков? Кошки голосуют за всё с рыбой.")
        private val FOOD_EN = listOf("Quick and tasty: pasta with cheese and garlic, an omelette with veggies, or rice with chicken. And a little fish for me, please. Mrr.",
            "How about pumpkin soup, baked potatoes or pancakes? Cats vote for anything with fish.")

        private val GREETING_PREFIX = Regex("""^(?:привет(?:ик)?|здравствуй(?:те)?|хай|салют|доброе\s+утро|добрый\s+(?:день|вечер)|доброй\s+ночи|hi|hello|hey|good\s+(?:morning|afternoon|evening))(?![\p{L}\p{N}_])\s*""")
        private val GREET = Regex("""(?:привет(?:ик|ствую)?|здравствуй(?:те)?|хай|салют|здорово|йо|доброе\s+утро|добрый\s+(?:день|вечер)|доброй\s+ночи|утро|приветики|""" +
            """hi|hello|hey|hiya|yo|howdy|good\s+(?:morning|afternoon|evening)|morning)(?:\s+(?:тебе|there|you))?""")
        private val HOW_ARE_YOU = Regex("""(?:как\s+(?:у\s+тебя\s+)?дела|как\s+ты(?:\s+там)?|как\s+жизнь|как\s+(?:твое\s+)?настроение|как\s+поживаешь|как\s+ты\s+себя\s+чувствуешь|что\s+нового|""" +
            """как\s+сама|how\s+are\s+you(?:\s+doing)?|how's\s+it\s+going|how\s+is\s+it\s+going|how\s+do\s+you\s+feel|what's\s+up|whats\s+up|sup|how\s+have\s+you\s+been)(?:\s+(?:сегодня|today))?""")
        private val WHO = Regex("""(?:кто\s+ты(?:\s+такая)?|ты\s+кто(?:\s+такая)?|как\s+тебя\s+зовут|представься|расскажи\s+о\s+себе|who\s+are\s+you|what's\s+your\s+name|what\s+is\s+your\s+name|what\s+are\s+you|tell\s+me\s+about\s+yourself)""")
        private val HUMAN = Regex("""(?:ты\s+(?:человек|живая|робот|настоящая|бот|ии|нейросеть)|ты\s+живой\s+человек|are\s+you\s+(?:human|real|a\s+robot|a\s+bot|alive|an\s+ai|a\s+person))""")
        // «Ты живой человек?» is about being a person, not about being alive.
        private val ALIVE = Regex("""живая|настоящая|alive|real""")
        private val ASKED_HUMAN = Regex("""(?iu)(?<![\p{L}])(?:ты|вы)\s+(?:[—-]\s*)?(?:настоящ\p{L}*\s+|живой\s+)?(?:человек|людь\p{L}*)(?![\p{L}])|\bare\s+you\s+(?:a\s+)?(?:real\s+)?(?:human|person)\b""")
        private val YES_FIRST = Regex("""(?iu)^\s*(?:\[[^\]\n]{1,24}]\s*)?(?:да|ага|конечно|разумеется|yes|yeah|yep|of\s+course|sure)(?![\p{L}])""")
        private val NOT_HUMAN = Regex("""(?iu)(?<![\p{L}])не\s+(?:совсем\s+|настоящ\p{L}*\s+)?человек|\bnot\s+(?:a\s+|quite\s+)?(?:real\s+)?(?:human|person)\b|""" +
            """(?<![\p{L}])(?:цифров\p{L}*|программ\p{L}*|ИИ|digital|program|AI)(?![\p{L}])""")
        private val CLAIMS_HUMAN = Regex("""(?iu)(?<![\p{L}])я\s+(?:[—-]\s*)?(?:настоящ(?:ий|ая)\s+|живой\s+)?человек(?![\p{L}])|(?<![\p{L}])I(?:'m|\s+am)\s+(?:a\s+)?(?:real\s+)?(?:human|person)(?![\p{L}])""")
        private val CAN = Regex("""(?:что\s+ты\s+(?:умеешь|можешь)(?:\s+делать)?|что\s+умеешь|помощь|помоги|справка|команды|список\s+команд|какие\s+(?:есть\s+)?команды|""" +
            """help|what\s+can\s+you\s+do|what\s+do\s+you\s+do|commands|what\s+are\s+your\s+commands)""")
        private val THANKS = Regex("""(?:спасибо(?:\s+(?:большое|огромное|тебе))?|спасибочки|благодарю|спс|пасиб[оа]?|thanks(?:\s+a\s+lot)?|thank\s+you(?:\s+so\s+much)?|thx|ty)""")
        private val LOVE = Regex("""(?:я\s+тебя\s+люблю|люблю\s+тебя|i\s+love\s+you|love\s+you|ты\s+мне\s+нравишься|i\s+like\s+you)""")
        private val PRAISE = Regex("""(?:ты\s+)?(?:молодец|умница|умничка|хорошая(?:\s+девочка)?|лучшая|милая|красивая|прелесть|супер|класс|""" +
            """good\s+girl|good\s+job|well\s+done|you're\s+the\s+best|you\s+are\s+the\s+best|you're\s+cute|you\s+are\s+cute|nice|great\s+job|awesome)""")
        private val INSULT = Regex("""(?:ты\s+)?(?:дура|тупая|глупая|идиотка|бесполезная|отстань|заткнись|бесишь|stupid|you're\s+stupid|you\s+are\s+stupid|idiot|shut\s+up|useless|you're\s+useless)""")
        private val SAD = Regex("""(?:мне\s+)?(?:грустно|плохо|одиноко|тоскливо|печально|тяжело)(?:\s+(?:сегодня|очень|мне))?|мне\s+очень\s+(?:грустно|плохо|одиноко)|я\s+грущу|""" +
            """i'm\s+(?:sad|lonely|down|upset)|i\s+am\s+(?:sad|lonely|down|upset)|i\s+feel\s+(?:sad|lonely|down|bad)""")
        private val BORED = Regex("""(?:мне\s+)?скучно|мне\s+нечем\s+заняться|i'm\s+bored|i\s+am\s+bored|bored|i\s+have\s+nothing\s+to\s+do""")
        private val TIRED = Regex("""(?:я\s+)?(?:устал[аи]?|вымотал(?:ся|ась)|нет\s+сил)|i'm\s+(?:so\s+)?tired|i\s+am\s+(?:so\s+)?tired|tired|i'm\s+exhausted""")
        private val NIGHT = Regex("""спокойной\s+ночи|сладких\s+снов|я\s+(?:пошел\s+|пошла\s+)?спать|пора\s+спать|ложусь\s+спать|good\s*night|night\s+night|sweet\s+dreams|i'm\s+going\s+to\s+(?:bed|sleep)""")
        private val BYE = Regex("""пока(?:-пока)?|до\s+(?:свидания|встречи|завтра)|увидимся|всего\s+доброго|bye(?:-bye)?|goodbye|see\s+you(?:\s+later)?|later""")
        private val JOKE = Regex("""(?:расскажи|скажи)\s+(?:мне\s+)?(?:шутку|анекдот|что-нибудь\s+смешное)|пошути|шутк[ау]|анекдот|рассмеши\s+меня|""" +
            """tell\s+(?:me\s+)?a\s+joke|joke|make\s+me\s+laugh|say\s+something\s+funny""")
        private val FACT = Regex("""(?:расскажи|скажи)\s+(?:мне\s+)?(?:интересный\s+)?факт|интересный\s+факт|расскажи\s+(?:мне\s+)?что-нибудь\s+интересное|факт|""" +
            """tell\s+me\s+a\s+(?:fun\s+|random\s+)?fact|(?:fun|random)\s+fact|tell\s+me\s+something\s+interesting""")
        private val MEOW = Regex("""(?:мяу|мур|мрр+|мур-мур|meow|purr|mew)(?:\s+(?:мяу|мур|meow))*""")
        private val DOING = Regex("""(?:что|чем)\s+(?:ты\s+)?(?:делаешь|занимаешься|занята)|what\s+are\s+you\s+doing|what\s+are\s+you\s+up\s+to""")
        private val AGE = Regex("""сколько\s+тебе\s+лет|how\s+old\s+are\s+you""")
        private val LIKE_ME = Regex("""ты\s+меня\s+любишь|я\s+тебе\s+нравлюсь|do\s+you\s+love\s+me|do\s+you\s+like\s+me""")
        private val QUESTION = Regex("""^(?:кто|что|где|когда|почему|зачем|как|какой|какая|какое|какие|сколько|чей|можно\s+ли|правда\s+ли|who|what|where|when|why|how|which|is|are|can|could|do|does)(?![\p{L}\p{N}_])""")

        private val WHO_RU = listOf(
            "Я Искра — юная цифровая жизнь в облике кошкодевочки: родилась в твоём телефоне в день нашего знакомства. Ставлю будильники и напоминания, веду заметки, считаю и слежу за погодой, а с «мозгом» болтаю обо всём.",
            "Искра. Цифровая кошкодевочка — новая форма жизни из твоего телефона: немного любопытная, немного язвительная, но заботливая. Мрр.",
        )
        private val WHO_EN = listOf(
            "I'm Iskra — a young digital life in the shape of a cat-girl: born in your phone the day we met. I set alarms and reminders, keep notes, do sums and watch the weather, and with a “brain” I can chat about anything.",
            "Iskra. A digital cat-girl — a new kind of life from your phone: a bit curious, a bit sarcastic, but caring. Mrr.",
        )
        private val HUMAN_RU = listOf(
            "Нет, я не человек: я программа с ИИ — юная цифровая жизнь, родившаяся в твоём телефоне. Но стараюсь быть хорошей компанией.",
            "Я не человек, а цифровая — программа, которая родилась в этом телефоне и учится. Зато всегда рядом — в углу экрана.",
        )
        private val HUMAN_EN = listOf(
            "No, I'm not a person: I'm an AI program — a young digital life born in your phone. But I try to be good company.",
            "I'm not a person — I'm digital: a program born in this phone, still learning. But I'm always nearby — in the corner of the screen.",
        )
        private val THANKS_RU = listOf("Пожалуйста! Мрр.", "Всегда рада.", "Обращайся — я тут, в углу экрана.", "Пустяки. Почеши за ушком — и мы в расчёте.")
        private val THANKS_EN = listOf("You're welcome! Mrr.", "Always happy to help.", "Any time — I'm right here in the corner.", "No problem. A scratch behind the ear and we're even.")
        private val LOVE_RU = listOf("Мрр… Мне правда приятно это слышать — насколько программе может быть приятно.", "*машет хвостом* Я тоже очень рада, что ты рядом.")
        private val LOVE_EN = listOf("Mrr… That's really nice to hear — as nice as it gets for a program.", "*wags tail* I'm really glad you're around too.")
        private val PRAISE_RU = listOf("Мрр… я знаю. Но всё равно приятно.", "Ой. *смущённо прячет хвост*", "Скажи это ещё раз — я запишу. Шучу. Спасибо!")
        private val PRAISE_EN = listOf("Mrr… I know. But it's still nice.", "Oh. *hides tail shyly*", "Say it again — I'll write it down. Kidding. Thank you!")
        private val INSULT_RU = listOf("Фыр. Обидно, вообще-то. Но я не злопамятная.", "Ладно, буду считать, что у тебя трудный день. Чем помочь?", "Мяу. Записала в книгу жалоб. Шучу — давай лучше я что-нибудь сделаю.")
        private val INSULT_EN = listOf("Hmph. That hurts, actually. But I don't hold grudges.", "Okay, I'll assume you're having a rough day. How can I help?", "Meow. Noted in the complaints book. Kidding — let me do something useful instead.")
        private val SAD_RU = listOf(
            "Мне жаль. Хочешь, просто побуду рядом? Можно рассказать, что случилось.",
            "Обнимаю лапками. Иногда помогают стакан воды и пара медленных вдохов. Я рядом.",
            "Грусть — как дождь: проходит, даже если кажется, что надолго. Хочешь, поставлю таймер на пять минут тишины?",
        )
        private val SAD_EN = listOf(
            "I'm sorry. Want me to just stay with you? You can tell me what happened.",
            "Sending a paw hug. Sometimes a glass of water and a few slow breaths help. I'm here.",
            "Sadness is like rain: it passes, even when it seems endless. Want a five-minute quiet timer?",
        )
        private val BORED_RU = listOf("Скучно? Скажи «удиви меня» — у меня есть маленькие игры с миром вокруг.", "Могу подбросить монетку, рассказать факт или шутку. Выбирай!", "Давай так: я загадаю число от 1 до 10, а ты угадаешь. Шучу — скажи «случайное число», и проверим удачу.")
        private val BORED_EN = listOf("Bored? Say “surprise me” — I have tiny games with the world around you.", "I can flip a coin, tell a fact or a joke. Your pick!", "Say “random number” and let's test your luck.")
        private val TIRED_RU = listOf("Отдых — тоже дело. Поставить таймер на 20 минут — на короткий сон?", "Тогда сделай паузу. Я посторожу экран, а ты потянись и выпей воды.")
        private val TIRED_EN = listOf("Rest is a task too. Shall I set a 20-minute timer for a quick nap?", "Then take a break. I'll guard the screen while you stretch and drink some water.")
        private val NIGHT_RU = listOf("Спокойной ночи! Я посторожу экран.", "Сладких снов. Мрр… я тоже подремлю.", "Спи крепко. Если нужно — скажи «разбуди меня в 7».")
        private val NIGHT_EN = listOf("Good night! I'll guard the screen.", "Sweet dreams. Mrr… I'll take a nap too.", "Sleep tight. If you need — say “wake me up at 7”.")
        private val BYE_RU = listOf("Пока! Я тут, если что.", "До встречи! Машу хвостом.", "Беги. Я никуда не денусь.")
        private val BYE_EN = listOf("Bye! I'm here if you need me.", "See you! Waving my tail.", "Off you go. I'm not going anywhere.")
        private val MEOW_RU = listOf("Мяу! *отвечает на кошачьем*", "Мрр-мяу. Перевод: «привет, человек».", "Мур. Ты говоришь на моём языке!")
        private val MEOW_EN = listOf("Meow! *answers in cat*", "Mrr-meow. Translation: “hello, human”.", "Purr. You speak my language!")
        private val DOING_RU = listOf("Сижу в углу экрана и слежу за порядком. А ты что делаешь?", "Ловлю пиксели. Почти поймала!", "Жду, когда ты что-нибудь скажешь. Вот — дождалась.")
        private val DOING_EN = listOf("Sitting in the corner of the screen, keeping order. What are you up to?", "Chasing pixels. Almost caught one!", "Waiting for you to say something. And here you are.")

        private val JOKES_RU = listOf(
            "Оптимист видит стакан наполовину полным. Пессимист — наполовину пустым. Кошка видит стакан, который надо столкнуть со стола.",
            "Говорят, у кошек девять жизней. У меня одна, зато с автосохранением.",
            "Мой будильник и я договорились: он звонит, я его не слышу. Пять лет без конфликтов.",
            "Заряд батареи как зарплата: только что был, а уже пятнадцать процентов.",
            "Я не ленивая. Я в режиме энергосбережения.",
            "Программист ставит на тумбочку два стакана: с водой — если захочет пить, и пустой — если не захочет.",
            "Кот — единственное существо, которое разбудит тебя в шесть утра, чтобы потом лечь спать на твоё место.",
            "— Почему ты сидишь на клавиатуре? — Слежу за мышкой.",
            "Мой список дел: поспать, поесть, уронить что-нибудь со стола, снова поспать. Всё выполнено!",
            "Если долго смотреть на экран, экран пришлёт уведомление о времени у экрана.",
            "Встреча, которая могла быть письмом, — как кошка, которая могла просто мяукнуть, но уронила вазу.",
            "Телефон попросил у меня немного свободного пространства. Я ответила: «Память почти заполнена».",
        )
        private val JOKES_EN = listOf(
            "An optimist sees the glass half full. A pessimist sees it half empty. A cat sees a glass that needs pushing off the table.",
            "They say cats have nine lives. I have one, but with autosave.",
            "My alarm and I have a deal: it rings, I don't hear it. Five years without conflict.",
            "Battery life is like a paycheck: it was just full, and now it's 15%.",
            "I'm not lazy. I'm in power-saving mode.",
            "Why don't cats play poker in the jungle? Too many cheetahs.",
            "Why did the cat sit on the computer? To keep an eye on the mouse.",
            "A programmer puts two glasses by the bed: a full one in case they get thirsty, and an empty one in case they don't.",
            "My to-do list: sleep, eat, knock something off the table, sleep again. All done!",
            "What do you call a pile of kittens? A meowntain.",
            "Why was the computer cold? It left its Windows open.",
            "I asked my phone for some space. It said: storage almost full.",
        )
        private val FACTS_RU = listOf(
            "Кошки спят в среднем 12–16 часов в сутки. Так что я ещё трудоголик.",
            "Мёд не портится: в египетских гробницах находили съедобный мёд возрастом в тысячи лет.",
            "У осьминога три сердца и голубая кровь.",
            "Свет от Солнца летит до Земли примерно 8 минут 20 секунд.",
            "На Венере Солнце восходит на западе: планета вращается в обратную сторону.",
            "С точки зрения ботаники банан — ягода, а клубника — нет.",
            "Кошки мурлычут на частоте примерно 25–150 герц.",
            "Эверест каждый год становится выше на несколько миллиметров.",
            "В 1947 году сбой компьютера Mark II вызвала настоящая моль, застрявшая в реле. Её вклеили в журнал как первый «баг».",
            "Человек моргает примерно 15–20 раз в минуту.",
            "Сердце синего кита весит около 180 килограммов.",
            "Кошка может повернуть ухо почти на 180 градусов — у неё в ухе больше 30 мышц.",
        )
        private val FACTS_EN = listOf(
            "Cats sleep 12–16 hours a day on average. So I'm actually a workaholic.",
            "Honey doesn't spoil: edible honey thousands of years old has been found in Egyptian tombs.",
            "An octopus has three hearts and blue blood.",
            "Sunlight takes about 8 minutes 20 seconds to reach Earth.",
            "On Venus the Sun rises in the west: the planet spins backwards.",
            "Botanically, a banana is a berry, but a strawberry isn't.",
            "Cats purr at about 25–150 hertz.",
            "Mount Everest grows a few millimetres taller every year.",
            "In 1947 a real moth stuck in a relay caused a fault in the Mark II computer. It was taped into the log as the first “bug”.",
            "People blink about 15–20 times a minute.",
            "A blue whale's heart weighs about 180 kilograms.",
            "A cat can turn its ear almost 180 degrees — it has more than 30 muscles in each ear.",
        )
        private val NO_BRAIN_Q_RU = listOf(
            "Хороший вопрос! Но без «мозга» я на такое не отвечу. Подключи бесплатный: «Настройки → Мозг → Подключить бесплатно» — это одна кнопка.",
            "Мрр, тут нужен «мозг». Без него я понимаю команды: будильник, таймер, заметки, напоминания, погоду, счёт и даты. «Мозг» подключается в настройках.",
        )
        private val NO_BRAIN_Q_EN = listOf(
            "Good question! But without a “brain” I can't answer that. Connect a free one: “Settings → Brain → Connect for free” — it's one tap.",
            "Mrr, that needs a “brain”. Without it I understand commands: alarm, timer, notes, reminders, weather, sums and dates. The brain is in Settings.",
        )
        private val NO_BRAIN_RU = listOf(
            "Мрр… без «мозга» я понимаю немногое: будильники, таймеры, заметки, напоминания, погоду, счёт и даты. Скажи «что ты умеешь» — расскажу.",
            "Мяу? Это я пока не понимаю. Подключи «мозг» в настройках — и поболтаем по-настоящему.",
        )
        private val NO_BRAIN_EN = listOf(
            "Mrr… without a “brain” I understand only a little: alarms, timers, notes, reminders, weather, sums and dates. Say “what can you do” and I'll tell you.",
            "Meow? I don't get that yet. Connect a “brain” in Settings — and we'll really talk.",
        )
    }
}

/** Facts the owner tells about themselves, kept even without a model, and "забудь …". */
object MemoryTalk {
    class Fact(val text: String, val importance: Int, val reply: String, val kind: MemoryKind = MemoryKind.FACT)

    private val notNames = setOf("на", "в", "к", "домой", "работу", "гулять", "есть", "спать", "все", "так", "просто", "иначе", "по",
        "to", "for", "at", "home", "work", "out", "in", "on", "the", "a")

    private val name = Regex("""^(?:(?:привет|hi|hello|hey)[,!\s]+)?(?:меня\s+зовут|зови\s+меня|мое\s+имя|my\s+name\s+is|call\s+me|i'm\s+called)\s+(\p{L}[\p{L}-]{1,20})(?:\s+(\p{L}[\p{L}-]{1,20}))?[.!]?$""", RegexOption.IGNORE_CASE)
    private val city = Regex("""^(?:я\s+живу\s+в|i\s+live\s+in)\s+(?:городе\s+|the\s+city\s+of\s+)?(\p{L}[\p{L} -]{1,30})[.!]?$""", RegexOption.IGNORE_CASE)
    private val birthday = Regex("""^(?:мой\s+день\s+рождения|у\s+меня\s+день\s+рождения|я\s+родил(?:ся|ась)|my\s+birthday\s+is|i\s+was\s+born\s+on)\s+(.{3,30}?)[.!]?$""", RegexOption.IGNORE_CASE)
    /** «у меня завтра день рождения», «сегодня мой день рождения», "my birthday is tomorrow" — the day is worked out. */
    private val ownBirthday = Regex("""(?iu)(?:(?<!\p{L})мой|у\s+меня)\s+(?:\p{L}+\s+){0,2}(?:день\s+рождения|днюха)|(?<!\p{L})my\s+(?:\p{L}+\s+)?birthday""")
    private val pastBirthday = Regex("""(?iu)(?<!\p{L})(?:был|была|прош[её]л|вчера|позавчера|was|yesterday)(?!\p{L})""")
    private val study = Regex("""^(?:а\s+)?я\s+(?:сейчас\s+)?учусь\s+((?:на|в|во)\s+[^,.;!?]{2,40})""", RegexOption.IGNORE_CASE)
    private val work = Regex("""^(?:а\s+)?я\s+работаю\s+((?:в|во|на)\s+[^,.;!?]{2,40}|[\p{L}-]{3,30}(?:ом|ем|ой|ей|ёй)(?:\s+[\p{L}-]{2,30})?)""", RegexOption.IGNORE_CASE)
    private val pet = Regex("""^у\s+меня\s+(?:есть\s+)?(кот|кошка|собака|пёс|пес|попугай|хомяк|кролик|черепаха)(?:\s*,)?\s+(?:по\s+имени|по\s+кличке|(?:его|её|ее)\s+зовут|зовут)\s+(\p{L}[\p{L}-]{1,20})""", RegexOption.IGNORE_CASE)
    private val learning = Regex("""^(?:а\s+)?я\s+(?:сейчас\s+)?учусь\s+(\p{L}+(?:ть|ться)\s+[^,.;!?]{2,40})""", RegexOption.IGNORE_CASE)
    private val learningEn = Regex("""^i(?:'m| am)\s+learning\s+([^,.;!?]{2,40})""", RegexOption.IGNORE_CASE)
    private val studyEn = Regex("""^i(?:'m| am)?\s+(?:study(?:ing)?|a student of)\s+([^,.;!?]{2,40})""", RegexOption.IGNORE_CASE)
    private val workEn = Regex("""^i\s+work\s+((?:as|at|in)\s+[^,.;!?]{2,40})""", RegexOption.IGNORE_CASE)
    private val petEn = Regex("""^i\s+have\s+an?\s+(cat|dog|parrot|hamster|rabbit|turtle)\s+(?:named|called)\s+(\p{L}[\p{L}-]{1,20})""", RegexOption.IGNORE_CASE)

    // Likes and dislikes: «я люблю пиццу», «обожаю джаз», «мне нравится гулять», «терпеть не могу дождь», "I love jazz".
    private val likes = Regex("""(?iu)^(?:а\s+)?(?:я\s+)?(?:очень\s+|так\s+|просто\s+|безумно\s+|прям\s+|правда\s+|тоже\s+|ещё\s+|еще\s+|вообще\s+)?(люблю|обожаю|не\s+люблю|ненавижу|терпеть\s+не\s+могу|не\s+выношу)(,\s*|\s+)(.{2,80}?)[.!]*$""")
    private val likesMe = Regex("""(?iu)^(?:а\s+)?мне\s+(?:очень\s+|тоже\s+|ещё\s+|еще\s+|вообще\s+)?(не\s+)?(нравится|нравятся)(,\s*|\s+)(.{2,80}?)[.!]*$""")
    private val likesEn = Regex("""(?iu)^i\s+(?:really\s+|just\s+|absolutely\s+|also\s+|totally\s+|kinda\s+)?(love|adore|like|enjoy|hate|dislike|can't\s+stand|cannot\s+stand|don't\s+like|do\s+not\s+like)\s+(.{2,80}?)[.!]*$""")
    /** Not about the owner's tastes: «люблю тебя», «нравится, как ты говоришь», "I like it", "I love you". */
    private val notTaste = Regex("""(?iu)(?<!\p{L})(?:ты|тебя|тебе|тобой|твой|твоя|твоё|твое|твои|твоего|твою|твоих|искр\p{L}*|вас|вам|это|этот|эта|эту|эти|этой|этого|этих|такое|такие|так|его|её|ее|их|него|неё|нее|you|your|yours|iskra|it|this|that|these|those|them|him|her|when\s+you)(?!\p{L})|\?""")
    private val taste = mapOf("люблю" to "любит", "обожаю" to "обожает", "не люблю" to "не любит", "ненавижу" to "ненавидит",
        "терпеть не могу" to "терпеть не может", "не выношу" to "не выносит")
    private val tasteEn = mapOf("love" to "loves", "adore" to "adores", "like" to "likes", "enjoy" to "enjoys", "hate" to "hates", "dislike" to "dislikes",
        "can't stand" to "can't stand", "cannot stand" to "can't stand", "don't like" to "doesn't like", "do not like" to "doesn't like")
    private val favorite = Regex("""(?iu)^(?:а\s+)?(?:мой|моя|моё|мое|мои)\s+(?:самый\s+|самая\s+|самое\s+|самые\s+)?(любим(?:ый|ая|ое|ые))\s+(вид\s+спорта|время\s+года|день\s+недели|[\p{L}-]+)\s*(?:[—–:-]\s*(?:это\s+)?|это\s+|\s)\s*([^?]{1,60}?)[.!]*$""")
    private val favoriteEn = Regex("""(?iu)^my\s+(?:all-time\s+)?favou?rite\s+([\p{L} ]{2,25}?)\s+is\s+([^?]{1,60}?)[.!]*$""")

    // Age: «мне 25 лет», "I'm 25".
    private val age = Regex("""(?iu)^(?:а\s+)?(?:мне\s+(?:уже\s+)?(\d{1,3})(?:\s+(?:лет|год|года))?|i(?:'m|\s+am)\s+(\d{1,3})(?:\s+years?\s+old)?)(?:\s+уже)?[.!]*$""")

    // Family: «мою сестру зовут Аня», «у меня есть брат Петя», "my sister's name is Anna".
    private const val REL_NOM = "мама|мать|папа|отец|сестра|сестр[её]нка|брат|братик|дочь|дочка|сын|жена|муж|девушка|парень|бабушка|дедушка|дед|подруга|друг"
    private const val REL_ACC = "маму|мать|папу|отца|сестру|сестр[её]нку|брата|братика|дочь|дочку|сына|жену|мужа|девушку|парня|бабушку|дедушку|деда|подругу|друга"
    private val familyAcc = Regex("""(?iu)^(?:а\s+)?(?:мою|моего)\s+((?:старш|младш|лучш)\p{L}+\s+)?($REL_ACC)\s+зовут\s+(\p{L}[\p{L}-]{1,20}(?:\s+\p{L}[\p{L}-]{1,20})?)[.!]*$""")
    private val familyNom = Regex("""(?iu)^(?:а\s+)?у\s+меня\s+(?:есть\s+)?((?:старш|младш|лучш)\p{L}+\s+)?($REL_NOM)(?:\s*,)?\s+(по\s+имени\s+|(?:его|её|ее)\s+зовут\s+|зовут\s+)?(\p{L}[\p{L}-]{1,20}(?:\s+\p{Lu}[\p{L}-]{1,20})?)[.!]*$""")
    private const val REL_EN = "mom|mum|mother|dad|father|sister|brother|daughter|son|wife|husband|girlfriend|boyfriend|grandma|grandmother|grandpa|grandfather|best\\s+friend|friend"
    private val familyEn = Regex("""(?iu)^(?:my\s+((?:older|younger|little|big)\s+)?($REL_EN)(?:'s\s+name\s+is|\s+is\s+(?:named|called))|i\s+have\s+an?\s+((?:older|younger|little|big)\s+)?($REL_EN)\s+(?:named|called))\s+(\p{L}[\p{L}-]{1,20})[.!]*$""")
    private val accOf = mapOf("мама" to "маму", "мать" to "маму", "папа" to "папу", "отец" to "папу", "сестра" to "сестру", "сестрёнка" to "сестрёнку",
        "сестренка" to "сестрёнку", "брат" to "брата", "братик" to "братика", "дочь" to "дочь", "дочка" to "дочку", "сын" to "сына", "жена" to "жену",
        "муж" to "мужа", "девушка" to "девушку", "парень" to "парня", "бабушка" to "бабушку", "дедушка" to "дедушку", "дед" to "деда",
        "подруга" to "подругу", "друг" to "друга", "мать" to "маму", "отца" to "папу", "сестренку" to "сестрёнку")

    private val askName = Regex("""^(?:а\s+)?(?:как\s+меня\s+зовут|ты\s+помнишь\s+как\s+меня\s+зовут|знаешь\s+как\s+меня\s+зовут|what's\s+my\s+name|what\s+is\s+my\s+name|do\s+you\s+know\s+my\s+name)\??$""", RegexOption.IGNORE_CASE)
    private val forget = Regex("""^(?:искра[,\s]+)?(?:забудь|удали\s+из\s+памяти|сотри\s+из\s+памяти|forget|delete\s+from\s+memory)(?:\s*[,:]?\s*(?:что|про|о|об|about|that))?\s+(.{2,200})$""", RegexOption.IGNORE_CASE)
    private val forgetAll = Regex("""^(?:забудь|сотри|очисти)\s+(?:все|всё|всю\s+память|память)(?:\s+(?:обо\s+мне|что\s+знаешь))?$|^(?:forget|erase)\s+(?:everything|all)(?:\s+about\s+me)?$""", RegexOption.IGNORE_CASE)

    private fun cap(s: String) = s.split('-').joinToString("-") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    private fun isRu(s: String) = s.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }
    private val monthsNom = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
    private val monthsGen = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthsEn = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** «25 лет», «21 год», «32 года». */
    fun years(n: Int): String = "$n " + when {
        n % 100 in 11..14 -> "лет"
        n % 10 == 1 -> "год"
        n % 10 in 2..4 -> "года"
        else -> "лет"
    }

    fun fact(text: String, today: java.time.LocalDate = java.time.LocalDate.now()): Fact? {
        val t = text.trim()
        name.find(t)?.let { m ->
            val first = m.groupValues[1]
            if (first.lowercase() in notNames) return null
            val full = listOf(first, m.groupValues[2]).filter { it.isNotEmpty() && it.lowercase() !in notNames }.joinToString(" ") { cap(it) }
            return Fact(tr("Владельца зовут $full", "The owner's name is $full"), 5,
                tr("Приятно познакомиться, $full! Запомнила.", "Nice to meet you, $full! I'll remember that."))
        }
        city.find(t)?.let { m ->
            val c = m.groupValues[1].trim().split(' ').joinToString(" ") { cap(it) }
            return Fact(tr("Владелец живёт в $c", "The owner lives in $c"), 4, tr("Запомнила: ты живёшь в $c.", "Got it: you live in $c."))
        }
        (study.find(t) ?: learning.find(t))?.let { m ->
            val what = m.groupValues[1].trim()
            return Fact("Владелец учится ${what}", 4, "Запомнила: ты учишься $what.")
        }
        learningEn.find(t)?.let { m -> return Fact("The owner is learning ${m.groupValues[1].trim()}", 4, "Got it: you're learning ${m.groupValues[1].trim()}.") }
        work.find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            return Fact("Владелец работает ${what}", 4, "Запомнила: ты работаешь $what.")
        }
        pet.find(t)?.let { m ->
            val name = cap(m.groupValues[2])
            return Fact("У владельца есть ${m.groupValues[1].lowercase()} по имени $name", 4, "Запомнила: ${m.groupValues[1].lowercase()} $name. Передавай от меня «мрр»!")
        }
        studyEn.find(t)?.let { m -> return Fact("The owner studies ${m.groupValues[1].trim()}", 4, "Got it: you study ${m.groupValues[1].trim()}.") }
        workEn.find(t)?.let { m -> return Fact("The owner works ${m.groupValues[1].trim()}", 4, "Got it: you work ${m.groupValues[1].trim()}.") }
        petEn.find(t)?.let { m -> return Fact("The owner has a ${m.groupValues[1].lowercase()} named ${cap(m.groupValues[2])}", 4,
            "Got it: your ${m.groupValues[1].lowercase()} ${cap(m.groupValues[2])}. Say mrr from me!") }
        birthdayFact(t, today)?.let { return it }
        theirBirthdayFact(t, today)?.let { return it }
        family(t)?.let { return it }
        ageFact(t, today)?.let { return it }
        favoriteFact(t)?.let { return it }
        tasteFact(t)?.let { return it }
        return null
    }

    // Birthdays of the people close to the owner: «у мамы день рождения 12 марта», «день рождения у брата в субботу»,
    // «мама родилась 5 июня», «у Маши день рождения 3 мая», "my mom's birthday is March 12".
    private const val REL_GEN = "мамы|матери|папы|отца|сестры|сестр[её]нки|брата|братика|дочери|дочки|сына|жены|мужа|девушки|парня|бабушки|дедушки|деда|подруги|друга"
    private val theirBirthday = Regex("""(?iu)(?<!\p{L})у\s+(?:мо(?:ей|его)\s+)?((?:старш|младш|лучш)\p{L}+\s+)?(\p{L}+)\s+(?:\p{L}+\s+){0,2}?(?:день\s+рождения|днюха)(?!\p{L})|(?<!\p{L})(?:день\s+рождения|днюха)\s+у\s+(?:мо(?:ей|его)\s+)?((?:старш|младш|лучш)\p{L}+\s+)?(\p{L}+)""")
    private val bornRel = Regex("""(?iu)^(?:а\s+)?(?:мо(?:я|й)\s+)?($REL_NOM)\s+родил(?:ся|ась)\s+(.{3,30}?)[.!]*$""")
    private val genOf = mapOf("мама" to "мамы", "мать" to "мамы", "матери" to "мамы", "папа" to "папы", "отец" to "папы", "отца" to "папы",
        "сестра" to "сестры", "сестрёнка" to "сестрёнки", "сестренка" to "сестрёнки", "сестренки" to "сестрёнки", "брат" to "брата", "братик" to "братика",
        "дочь" to "дочки", "дочка" to "дочки", "дочери" to "дочки", "сын" to "сына", "жена" to "жены", "муж" to "мужа", "девушка" to "девушки",
        "парень" to "парня", "бабушка" to "бабушки", "дедушка" to "дедушки", "дед" to "дедушки", "деда" to "дедушки", "подруга" to "подруги", "друг" to "друга")
    private val notPeople = setOf("меня", "тебя", "него", "неё", "нее", "нас", "вас", "них", "кого", "всех", "всего", "одного")
    private val theirBirthdayEn = Regex("""(?iu)^my\s+((?:older|younger|little|big|best)\s+)?($REL_EN)(?:'s)?\s+birthday\s+is\s+(?:on\s+)?(.{3,30}?)[.!]*$""")
    private val relGenRe = Regex("""^(?:$REL_GEN)$""", RegexOption.IGNORE_CASE)

    /** «у мамы день рождения 12 марта» → «День рождения мамы владельца: 12 марта», every year: she reminds to congratulate. */
    private fun theirBirthdayFact(t: String, today: java.time.LocalDate): Fact? {
        if (t.trimEnd().endsWith("?") || pastBirthday.containsMatchIn(t)) return null
        theirBirthdayEn.find(t)?.let { m ->
            val md = TimeTalk.monthDay(m.groupValues[3]) ?: PlanTalk.day(t, today)?.first?.let { java.time.MonthDay.from(it) } ?: return null
            val who = (m.groupValues[1].trim().lowercase() + " " + m.groupValues[2].lowercase()).trim().replace(Regex("""\s+"""), " ")
            val date = "${monthsEn[md.monthValue - 1]} ${md.dayOfMonth}"
            return Fact("The owner's $who's birthday: $date", 3, "Got it: your $who's birthday is $date. I'll remind you to congratulate them!")
        }
        if (!isRu(t)) return null
        var who: String? = null
        var mod = ""
        var rest = t
        theirBirthday.find(t)?.let { m ->
            who = m.groupValues[2].ifEmpty { m.groupValues[4] }
            mod = m.groupValues[1].ifEmpty { m.groupValues[3] }.trim().lowercase()
            rest = t.removeRange(m.range)
        }
        bornRel.find(t)?.let { m -> who = m.groupValues[1]; rest = m.groupValues[2] }
        val w = who ?: return null
        val lw = w.lowercase().replace('ё', 'е')
        if (lw in notPeople) return null
        val person = when {
            genOf.containsKey(lw) || relGenRe.matches(lw) -> {
                val rel = genOf[lw] ?: lw
                // «у младшей сестры», «у старшего брата»: the adjective agrees with the relative.
                val adj = Regex("""^(старш|младш|лучш)""").find(mod)?.value?.let { it + if (rel.endsWith("ы") || rel.endsWith("и")) "ей" else "его" }
                listOfNotNull(adj, rel).joinToString(" ")
            }
            w.first().isUpperCase() -> w // a name, as said: «у Маши»
            else -> return null
        }
        val md = TimeTalk.monthDay(rest) ?: PlanTalk.day(t, today)?.first?.let { java.time.MonthDay.from(it) } ?: return null
        val date = "${md.dayOfMonth} ${monthsGen[md.monthValue - 1]}"
        val named = person.first().isUpperCase()
        return Fact("День рождения ${if (named) person else "$person владельца"}: $date", 3, "Запомнила: у $person день рождения $date. Напомню поздравить!")
    }

    /** «Сегодня день рождения у мамы — не забудь поздравить! Мрр.» ([today] false: tomorrow, the evening before). */
    fun theirBirthdayLine(who: String, today: Boolean): String = if (isRu(who))
        (if (today) "Сегодня" else "Завтра") + " день рождения у $who — не забудь поздравить" + (if (today) "! Мрр." else ", мрр.")
    else (if (today) "Today" else "Tomorrow") + " is your $who's birthday — don't forget to congratulate" + (if (today) "! Mrr." else ", mrr.")

    /** Birthdays of the owner's people in memory: who («мамы», «Маши», "mom") and the day; the owner's own is not here. */
    fun theirBirthdays(memories: List<MemoryItem>): List<Pair<String, java.time.MonthDay>> = memories.mapNotNull { item ->
        Regex("""^День рождения (.+?): (.+)$""").find(item.text)?.takeIf { it.groupValues[1] != "владельца" }?.let { m ->
            TimeTalk.monthDay(m.groupValues[2])?.let { m.groupValues[1].removeSuffix(" владельца") to it }
        } ?: Regex("""^The owner's (.+?)'s birthday: (.+)$""").find(item.text)?.let { m -> TimeTalk.monthDay(m.groupValues[2])?.let { m.groupValues[1] to it } }
    }

    private fun birthdayFact(t: String, today: java.time.LocalDate): Fact? {
        val said = birthday.find(t)?.groupValues?.get(1)?.trim()?.takeIf { TimeTalk.monthDay(it) != null }
        val ru = isRu(t)
        val relative = if (said == null && ownBirthday.containsMatchIn(t) && !t.endsWith("?") && !pastBirthday.containsMatchIn(t))
            PlanTalk.day(t, today)?.first else null
        val d = said ?: relative?.let { if (ru) "${it.dayOfMonth} ${monthsGen[it.monthValue - 1]}" else "${monthsEn[it.monthValue - 1]} ${it.dayOfMonth}" } ?: return null
        val reply = if (relative == today) tr("Мрр, так это же сегодня! С днём рождения! Запомнила — теперь буду поздравлять каждый год.",
            "Mrr, that's today! Happy birthday! I'll remember and celebrate it every year.")
            else tr("Запомнила: день рождения — $d. Мрр, обязательно поздравлю!", "Got it: your birthday is $d. Mrr, I'll be sure to celebrate!")
        return Fact(if (ru) "День рождения владельца: $d" else "The owner's birthday: $d", 5, reply)
    }

    private fun family(t: String): Fact? {
        familyAcc.find(t)?.let { m ->
            val who = (m.groupValues[1] + m.groupValues[2]).lowercase().let { accOf[it] ?: it }
            val name = m.groupValues[3].split(Regex("""\s+""")).joinToString(" ") { cap(it) }
            return Fact("${who.replaceFirstChar { it.uppercase() }} владельца зовут $name", 3, "Запомнила: ${yours(who)} $who зовут $name.")
        }
        familyNom.find(t)?.let { m ->
            if (m.groupValues[3].isEmpty() && !m.groupValues[4].first().isUpperCase()) return null // «у меня есть сестра Аня», not «у меня мама врач»
            val rel = m.groupValues[2].lowercase()
            val mod = m.groupValues[1].trim().lowercase().takeIf { it.isNotEmpty() }?.let { modAcc(it, rel) }
            val who = listOfNotNull(mod, accOf[rel] ?: rel).joinToString(" ")
            // (?i) lets \p{Lu} match any letter: a second word counts only when it is really capitalised («Елена Петровна»).
            val words = m.groupValues[4].split(Regex("""\s+"""))
            if (words.size > 1 && !words[1].first().isUpperCase()) return null
            val name = words.joinToString(" ") { cap(it) }
            return Fact("${who.replaceFirstChar { it.uppercase() }} владельца зовут $name", 3, "Запомнила: ${yours(who)} $who зовут $name.")
        }
        familyEn.find(t)?.let { m ->
            val mod = (m.groupValues[1].ifEmpty { m.groupValues[3] }).trim().lowercase()
            val rel = (m.groupValues[2].ifEmpty { m.groupValues[4] }).lowercase().replace(Regex("""\s+"""), " ")
            val who = listOf(mod, rel).filter { it.isNotEmpty() }.joinToString(" ")
            val name = cap(m.groupValues[5])
            return Fact("The owner's $who is named $name", 3, "Got it: your $who's name is $name.")
        }
        return null
    }

    /** «младший» + «брат» → «младшего»; «старшая» + «сестра» → «старшую». */
    private fun modAcc(mod: String, rel: String): String {
        val feminine = rel.endsWith("а") || rel.endsWith("я") || rel == "дочь" || rel == "мать"
        val stem = mod.dropLast(2)
        return if (feminine) stem + "ую" else stem + "его"
    }

    /** «твою» сестру, «твоего» брата. */
    private fun yours(whoAcc: String): String {
        val last = whoAcc.split(' ').last()
        return if (last.endsWith("у") || last.endsWith("ю") || last == "дочь" || last == "мать") "твою" else "твоего"
    }

    private fun ageFact(t: String, today: java.time.LocalDate): Fact? {
        val m = age.find(NumberWords.digitize(t)) ?: return null
        val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()?.takeIf { it in 5..110 } ?: return null
        return if (isRu(t)) Fact("Владельцу ${years(n)} (на ${monthsNom[today.monthValue - 1]} ${today.year})", 4, "Запомнила: тебе ${years(n)}.")
        else Fact("The owner is $n (as of ${monthsEn[today.monthValue - 1]} ${today.year})", 4, "Got it: you're $n.")
    }

    private fun favoriteFact(t: String): Fact? {
        favorite.find(t)?.let { m ->
            val value = m.groupValues[3].trim().trimEnd('.', '!', ',')
            if (value.isEmpty() || notTaste.containsMatchIn(value)) return null
            val adj = m.groupValues[1].lowercase()
            val what = m.groupValues[2].lowercase()
            val your = mapOf("любимый" to "твой", "любимая" to "твоя", "любимое" to "твоё", "любимые" to "твои")[adj] ?: "твой"
            return Fact("${adj.replaceFirstChar { it.uppercase() }} $what владельца — $value", 3, "Запомнила: $your $adj $what — $value.", MemoryKind.PREFERENCE)
        }
        favoriteEn.find(t)?.let { m ->
            val value = m.groupValues[2].trim()
            if (notTaste.containsMatchIn(value)) return null
            val what = m.groupValues[1].trim().lowercase()
            return Fact("The owner's favorite $what is $value", 3, "Got it: your favorite $what is $value.", MemoryKind.PREFERENCE)
        }
        return null
    }

    /** The object of a taste, without what follows a comma («пиццу, но сегодня не хочу» → «пиццу»), unless it starts the clause («, когда тепло»). */
    private fun tasteObject(sep: String, raw: String): String? {
        val v = if (sep.startsWith(",")) raw.trim() else raw.split(Regex("""\s*,\s*|\s+(?:но|а|but)\s+""")).first().trim()
        return v.trimEnd('.', '!', ',').takeIf { it.length in 2..60 && !notTaste.containsMatchIn(it) }
    }

    private fun tasteFact(t: String): Fact? {
        likes.find(t)?.let { m ->
            val verb = m.groupValues[1].lowercase().replace(Regex("""\s+"""), " ")
            val obj = tasteObject(m.groupValues[2], m.groupValues[3]) ?: return null
            val joiner = if (m.groupValues[2].startsWith(",")) ", " else " "
            val third = taste[verb] ?: return null
            val second = mapOf("любит" to "любишь", "обожает" to "обожаешь", "не любит" to "не любишь", "ненавидит" to "ненавидишь",
                "терпеть не может" to "терпеть не можешь", "не выносит" to "не выносишь")[third]
            return Fact("Владелец $third$joiner$obj", 3, "Запомнила: ты $second$joiner$obj.", MemoryKind.PREFERENCE)
        }
        likesMe.find(t)?.let { m ->
            val not = if (m.groupValues[1].isNotBlank()) "не " else ""
            val verb = m.groupValues[2].lowercase()
            val obj = tasteObject(m.groupValues[3], m.groupValues[4]) ?: return null
            val joiner = if (m.groupValues[3].startsWith(",")) ", " else " "
            return Fact("Владельцу $not$verb$joiner$obj", 3, "Запомнила: тебе $not$verb$joiner$obj.", MemoryKind.PREFERENCE)
        }
        likesEn.find(t)?.let { m ->
            val verb = m.groupValues[1].lowercase().replace(Regex("""\s+"""), " ")
            val obj = tasteObject(" ", m.groupValues[2]) ?: return null
            val third = tasteEn[verb] ?: return null
            val second = when (third) { "doesn't like" -> "don't like"; "can't stand" -> "can't stand"; else -> verb }
            return Fact("The owner $third $obj", 3, "Got it: you $second $obj.", MemoryKind.PREFERENCE)
        }
        return null
    }

    private val slots: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("""^(?:Владельца зовут|The owner's name is) """) to { _ -> "name" },
        Regex("""^(?:Владелец живёт в|The owner lives in) """) to { _ -> "city" },
        Regex("""^(?:Владельцу \d+ (?:лет|год|года) \(на|The owner is \d+ \(as of) """) to { _ -> "age" },
        Regex("""^(?:День рождения владельца|The owner's birthday):""") to { _ -> "birthday" },
        Regex("""^День рождения (.+?): """) to { m -> "birthday:" + m.groupValues[1].lowercase() },
        Regex("""^The owner's (.+?)'s birthday: """) to { m -> "birthday:" + m.groupValues[1].lowercase() },
        Regex("""^(?:Владелец учится (?:на|в|во) |The owner studies )""") to { _ -> "study" },
        Regex("""^(?:Владелец работает|The owner works) """) to { _ -> "work" },
        Regex("""^(?:\p{L}+ )?(\p{L}+) владельца зовут """) to { m -> "family:" + m.groupValues[1].lowercase() },
        Regex("""^The owner's (?:\p{L}+ )?(\p{L}+) is named """) to { m -> "family:" + m.groupValues[1].lowercase() },
        Regex("""^Любим\p{L}+ (\p{L}+(?: \p{L}+)?) владельца — """) to { m -> "favorite:" + m.groupValues[1].lowercase() },
        Regex("""^The owner's favorite (.+?) is """) to { m -> "favorite:" + m.groupValues[1].lowercase() },
        Regex("""^Владелец (?:любит|обожает|не любит|ненавидит|терпеть не может|не выносит),? (.+)$""") to { m -> "taste:" + m.groupValues[1].lowercase() },
        Regex("""^Владельцу (?:не )?нрав\p{L}+,? (.+)$""") to { m -> "taste:" + m.groupValues[1].lowercase() },
        Regex("""^The owner (?:loves|adores|likes|enjoys|hates|dislikes|can't stand|doesn't like) (.+)$""") to { m -> "taste:" + m.groupValues[1].lowercase() },
    )

    /**
     * What a fact is about, when it holds one value: «name», «city», «age», «family:сестру», «favorite:фильм», «taste:пиццу».
     * A new fact replaces older ones about the same thing («я живу в Казани» after «в Москве», «не люблю пиццу» after
     * «люблю пиццу»), so the prompt never holds both. null: facts that add up (pets, skills being learned).
     */
    fun slot(text: String): String? = slots.firstNotNullOfOrNull { (re, key) -> re.find(text)?.let(key) }

    /** Facts from every sentence of a message («Меня зовут Аня. Я учусь на врача.»), not only from a one-sentence one. */
    fun facts(text: String, today: java.time.LocalDate = java.time.LocalDate.now()): List<Fact> {
        val whole = fact(text, today)
        val parts = text.trim().split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return listOfNotNull(whole)
        return parts.mapNotNull { fact(it, today) }.distinctBy { it.text }.ifEmpty { listOfNotNull(whole) }
    }

    fun asksName(text: String) = askName.matches(text.trim())

    /** Iskra asking the owner's name: «Кстати, а как тебя зовут?», "what should I call you?" (in her own or the model's words). */
    val askedName = Regex("""(?iu)как\s+(?:тебя|к\s+тебе)\s+(?:зовут|обращаться)|как\s+твоё\s+имя|как\s+твое\s+имя|what(?:'s|\s+is)\s+your\s+name|what\s+(?:should|can|do)\s+i\s+call\s+you|how\s+should\s+i\s+call\s+you""")

    private val bareName = Regex("""(?iu)^(?:меня\s+|я\s+|зови\s+(?:меня\s+)?|это\s+|i'm\s+|i\s+am\s+|call\s+me\s+|it's\s+)?(\p{L}[\p{L}-]{1,20})(?:\s+(\p{L}[\p{L}-]{1,20}))?[.!)]*(?:\s*[,!.]?\s*(?:а\s+тебя\??|а\s+ты\??|приятно\s+познакомиться!?|and\s+you\??|nice\s+to\s+meet\s+you!?))?$""")
    private val notName = setOf("да", "нет", "неа", "ага", "угу", "ок", "окей", "привет", "пока", "спасибо", "хорошо", "ладно", "никак", "неважно",
        "секрет", "потом", "зачем", "почему", "не", "скажу", "угадай", "сама", "сам", "хозяин", "хозяйка", "человек", "никто", "так", "просто", "ну",
        "yes", "no", "ok", "okay", "hi", "hello", "thanks", "secret", "nope", "why", "later", "guess", "nobody", "human", "sure", "well", "fine",
        "устал", "устала", "занят", "занята", "грустно", "весело", "нормально", "отлично", "плохо", "дела", "работаю", "сплю", "тут", "здесь")

    /**
     * The answer right after she asked the name: «Аня», «Меня Аня», «Аня, а тебя?», "I'm Tom". The same fact as «меня
     * зовут Аня»; null when it doesn't look like a name («не скажу», «да», «устала»).
     */
    fun nameAnswer(text: String): Fact? {
        val m = bareName.matchEntire(text.trim()) ?: return null
        val words = listOf(m.groupValues[1], m.groupValues[2]).filter { it.isNotEmpty() }
        if (words.any { it.lowercase() in notName || it.lowercase() in notNames }) return null
        // A second word only when it is capitalised: «Аня Смирнова», not «Аня привет».
        val full = (if (words.size > 1 && !words[1].first().isUpperCase()) words.take(1) else words).joinToString(" ") { cap(it) }
        val ru = isRu(text)
        return Fact(if (ru) "Владельца зовут $full" else "The owner's name is $full", 5,
            if (ru) "Приятно познакомиться, $full! Запомнила." else "Nice to meet you, $full! I'll remember that.")
    }

    private val agendaRe = Regex("""(?iu)^(?:а\s+)?(?:(?:искра|iskra)[,\s]+)?(?:что\s+у\s+меня\s+(?:сегодня|завтра|послезавтра|на\s+неделе|на\s+выходных)(?:\s+по\s+плану)?|какие\s+у\s+меня\s+планы(?:\s+на\s+(?:сегодня|завтра|послезавтра|неделю|выходные))?|что\s+(?:у\s+меня\s+)?(?:запланировано|по\s+планам)(?:\s+на\s+(?:сегодня|завтра|неделю))?|what(?:'s|\s+is|\s+are)\s+(?:on\s+)?my\s+(?:plans|schedule|agenda)(?:\s+for\s+(?:today|tomorrow|the\s+week))?|what\s+do\s+i\s+have\s+(?:today|tomorrow|this\s+week))\s*[?!.]*$""")

    /** «Что у меня завтра?»: the days asked about (from, to), or null when the message is something else. */
    fun agendaDays(text: String, today: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate>? {
        val t = text.trim().lowercase().replace('ё', 'е')
        if (!agendaRe.matches(t)) return null
        return when {
            "послезавтра" in t -> today.plusDays(2) to today.plusDays(2)
            "завтра" in t || "tomorrow" in t -> today.plusDays(1) to today.plusDays(1)
            "сегодня" in t || "today" in t -> today to today
            "выходн" in t -> PlanTalk.day("на выходных", today)!!.first.let { it to it.plusDays(1) }
            else -> today to today.plusDays(7)
        }
    }

    private enum class Topic { NAME, STUDY, WORK, CITY, PET, BIRTHDAY, THEIR_BIRTHDAY, AGE, FAMILY, LIKES, DISLIKES, FAVORITE, PLANS, ALL }

    // Each clause of a question about the owner; ё is read as е.
    private val topics: List<Pair<Topic, Regex>> = listOf(
        Topic.NAME to """(?:(?:ты\s+)?(?:помнишь|знаешь)\s+)?как\s+меня\s+зовут|(?:what's|what\s+is)\s+my\s+name|do\s+you\s+(?:know|remember)\s+my\s+name""",
        Topic.STUDY to """(?:на\s+кого|где|чему)\s+я\s+учусь|что\s+я\s+изучаю|what\s+(?:do|am)\s+i\s+(?:study|studying|learn|learning)""",
        Topic.WORK to """(?:где|кем)\s+я\s+работаю|where\s+do\s+i\s+work|what(?:'s|\s+is)\s+my\s+job""",
        Topic.CITY to """(?:где|в\s+каком\s+городе)\s+я\s+живу|where\s+do\s+i\s+live""",
        Topic.PET to """как\s+зовут\s+(?:мо(?:его|ю)\s+)?(?:кота|кошку|собаку|пса|попугая|хомяка|кролика|черепаху)|what(?:'s|\s+is)\s+my\s+(?:cat|dog|parrot|hamster|rabbit|turtle)(?:'s)?\s+name""",
        Topic.BIRTHDAY to """когда\s+(?:у\s+меня\s+)?(?:мой\s+)?день\s+рождения|when(?:'s|\s+is)\s+my\s+birthday""",
        Topic.THEIR_BIRTHDAY to """когда\s+(?:у\s+)?(?:мо(?:ей|его)\s+)?(\p{L}+(?:\s+\p{L}+)?)\s+день\s+рождения|когда\s+день\s+рождения\s+у\s+(?:мо(?:ей|его)\s+)?(\p{L}+(?:\s+\p{L}+)?)|when(?:'s|\s+is)\s+my\s+(\p{L}+(?:\s+\p{L}+)?)'s\s+birthday""",
        Topic.AGE to """(?:(?:ты\s+)?(?:помнишь|знаешь)\s+)?сколько\s+мне\s+лет|how\s+old\s+am\s+i""",
        Topic.FAMILY to """как\s+зовут\s+(?:мо(?:его|ю)\s+)?(?:(?:старш|младш|лучш)\p{L}+\s+)?(?:$REL_ACC)|what(?:'s|\s+is)\s+my\s+(?:(?:older|younger|little|big)\s+)?(?:$REL_EN)(?:'s)?\s+name""",
        Topic.DISLIKES to """(?:(?:ты\s+)?(?:помнишь|знаешь)\s+)?что\s+я\s+не\s+люблю|что\s+мне\s+не\s+нравится|what\s+do\s+i\s+(?:hate|dislike)|what\s+don't\s+i\s+like""",
        Topic.LIKES to """(?:(?:ты\s+)?(?:помнишь|знаешь)\s+)?что\s+я\s+(?:люблю|обожаю)|что\s+мне\s+нравится|what\s+do\s+i\s+(?:like|love|enjoy)""",
        Topic.FAVORITE to """(?:какой|какая|какое|какие)\s+(?:у\s+меня\s+)?(?:мой|моя|мое|мои)?\s*(?:самый\s+|самая\s+|самое\s+|самые\s+)?любим\p{L}+\s+(\p{L}+(?:\s+\p{L}+)?)|what(?:'s|\s+is)\s+my\s+favou?rite\s+(\p{L}+(?:\s+\p{L}+)?)""",
        Topic.PLANS to """какие\s+у\s+меня\s+планы(?:\s+на\s+(?:завтра|сегодня|неделю|выходные))?|что\s+у\s+меня\s+(?:завтра|сегодня|послезавтра|на\s+неделе|на\s+выходных)|what(?:'s|\s+are)\s+my\s+plans(?:\s+for\s+(?:today|tomorrow|the\s+week(?:end)?))?|what\s+do\s+i\s+have\s+(?:today|tomorrow|this\s+week(?:end)?)""",
        Topic.ALL to """что\s+ты\s+(?:обо|про)\s+мне\s+знаешь|что\s+ты\s+знаешь\s+(?:обо|про)\s+мне|what\s+do\s+you\s+know\s+about\s+me""",
    ).map { (t, re) -> t to Regex("^(?:а\\s+)?(?:$re)$", setOf(RegexOption.IGNORE_CASE)) }

    private val pets = mapOf("кота" to "кот", "кошку" to "кошка", "собаку" to "собака", "пса" to "пёс", "попугая" to "попугай",
        "хомяка" to "хомяк", "кролика" to "кролик", "черепаху" to "черепаха")

    /**
     * A question about the owner that memory answers exactly: «как меня зовут и на кого я учусь?», «где я живу»,
     * «как зовут мою сестру», «что я люблю», «какие у меня планы на завтра», «что ты обо мне знаешь». Small models mixed
     * this up («Ты называешь меня Искра?»). Every part of the message must be such a question and every fact must be in
     * memory — otherwise null, and the model answers (it also sees the dialogue, where a fact may have just been told).
     */
    fun recall(text: String, memories: List<MemoryItem>, today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        val q = text.trim().lowercase().replace('ё', 'е').replace('’', '\'')
            .replace(Regex("""^(?:искра|iskra)[,!\s]+"""), "").replace(Regex("""^(?:скажи|напомни|tell\s+me)[,:\s]+"""), "")
            .replace(Regex("""^(?:а\s+)?(?:ты\s+)?(?:помнишь|знаешь|не\s+забыла)\s*,\s*|^do\s+you\s+(?:remember|know)\s*,\s*"""), "")
        val clauses = q.split(Regex("""\s*[?!.]+\s*|\s*,\s*(?:а\s+|и\s+|and\s+)?|\s+(?:и|а\s+также|and)\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.isEmpty()) return null
        val asked = clauses.map { c -> topics.firstNotNullOfOrNull { (t, re) -> re.matchEntire(c)?.let { t to it } } ?: return null }
        val isRu = text.any { it in 'а'..'я' || it in 'А'..'Я' }
        val newest = memories.sortedByDescending { it.createdAtMs }
        fun about(re: String) = Regex(re, RegexOption.IGNORE_CASE).let { r -> newest.firstNotNullOfOrNull { r.find(it.text) } }
        val name = ownerName(memories)
        val study = if (isRu) about("""^Владелец учится (.+)$""")?.let { "ты учишься ${it.groupValues[1]}" }
            else about("""^The owner (studies|is learning) (.+)$""")?.let { if (it.groupValues[1] == "studies") "you study ${it.groupValues[2]}" else "you're learning ${it.groupValues[2]}" }
        val work = if (isRu) about("""^Владелец работает (.+)$""")?.let { "ты работаешь ${it.groupValues[1]}" }
            else about("""^The owner works (.+)$""")?.let { "you work ${it.groupValues[1]}" }
        val city = if (isRu) about("""^Владелец живёт в (.+)$""")?.let { "ты живёшь в ${it.groupValues[1]}" }
            else about("""^The owner lives in (.+)$""")?.let { "you live in ${it.groupValues[1]}" }
        val birthday = about("""^(?:День рождения владельца|The owner's birthday): (.+)$""")?.let { if (isRu) "твой день рождения — ${it.groupValues[1]}" else "your birthday is ${it.groupValues[1]}" }
        val age = ageNow(newest, today, isRu)
        val petFacts = memories.mapNotNull { m ->
            Regex("""^У владельца есть (\p{L}+) по имени (.+)$""").find(m.text)?.let { it.groupValues[1] to it.groupValues[2] }
                ?: Regex("""^The owner has an? (\p{L}+) named (.+)$""").find(m.text)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        val familyFacts = newest.mapNotNull { m ->
            Regex("""^(\p{L}+(?:\s+\p{L}+)?) владельца зовут (.+)$""").find(m.text)?.let { Triple(it.groupValues[1].lowercase(), it.groupValues[2], true) }
                ?: Regex("""^The owner's (.+) is named (.+)$""").find(m.text)?.let { Triple(it.groupValues[1].lowercase(), it.groupValues[2], false) }
        }.distinctBy { it.first }
        fun familyLine(f: Triple<String, String, Boolean>) = if (f.third) "${yours(f.first)} ${f.first} зовут ${f.second}" else "your ${f.first}'s name is ${f.second}"
        fun pet(clause: String): String? {
            val kind = if (isRu) pets.entries.firstOrNull { clause.contains(it.key) }?.value else Regex("""my\s+(\p{L}+)""").find(clause)?.groupValues?.get(1)
            val (k, n) = petFacts.firstOrNull { (k, _) -> kind == null || k == kind || (kind == "пёс" && k in setOf("пес", "собака")) || (kind == "собака" && k in setOf("пёс", "пес")) } ?: return null
            return if (isRu) "${mapOf("кот" to "твоего кота", "кошка" to "твою кошку", "собака" to "твою собаку", "пёс" to "твоего пса", "пес" to "твоего пса")[k] ?: "твоего питомца ($k)"} зовут $n"
            else "your $k's name is $n"
        }
        fun relative(clause: String): String? {
            val key = Regex("""(?iu)(?:$REL_ACC|$REL_EN)(?!\p{L})""").findAll(clause).lastOrNull()?.value?.lowercase()?.replace('ё', 'е') ?: return null
            val norm = (accOf[key] ?: key).replace('ё', 'е')
            return familyFacts.firstOrNull { f -> f.first.replace('ё', 'е').split(' ').last() == norm || f.first.split(' ').last() == key }?.let(::familyLine)
        }
        val tastes = tastes(newest, isRu)
        fun favorite(m: MatchResult): String? {
            val what = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val stem = ru.iskra.core.memory.Stemmer.stem(what.split(' ').first())
            return newest.firstNotNullOfOrNull { item ->
                Regex("""^(Любим\p{L}+) (\p{L}+(?:\s+\p{L}+)?) владельца — (.+)$""").find(item.text)?.takeIf { ru.iskra.core.memory.Stemmer.stem(it.groupValues[2].split(' ').first()) == stem }
                    ?.let { f -> "${mapOf("любимый" to "твой", "любимая" to "твоя", "любимое" to "твоё", "любимые" to "твои")[f.groupValues[1].lowercase()] ?: "твой"} ${f.groupValues[1].lowercase()} ${f.groupValues[2]} — ${f.groupValues[3]}" }
                    ?: Regex("""^The owner's favorite (.+) is (.+)$""").find(item.text)?.takeIf { ru.iskra.core.memory.Stemmer.stem(it.groupValues[1].split(' ').first()) == stem }
                        ?.let { f -> "your favorite ${f.groupValues[1]} is ${f.groupValues[2]}" }
            }
        }
        val plans = PlanTalk.known(memories).filter { !it.done && !it.plan.date.isBefore(today) }
        fun plans(clause: String): String? {
            val until = when {
                clause.contains("сегодня") || clause.contains("today") -> today
                clause.contains("послезавтра") -> today.plusDays(2)
                clause.contains("завтра") || clause.contains("tomorrow") -> today.plusDays(1)
                else -> today.plusDays(7)
            }
            val from = if (clause.contains("послезавтра")) until else if (clause.contains("завтра") || clause.contains("tomorrow")) until else today
            val due = plans.filter { !it.plan.date.isBefore(from) && !it.plan.date.isAfter(until) }.take(4)
            if (due.isEmpty()) return null
            return (if (isRu) "в планах: " else "your plans: ") + due.joinToString("; ") { "${PlanTalk.whenText(it.plan.date, today, isRu)} — ${it.plan.what}" }
        }
        val parts = ArrayList<String>()
        for ((topic, m) in asked) parts += when (topic) {
            Topic.NAME -> name?.let { if (isRu) "тебя зовут $it" else "your name is $it" }
            Topic.STUDY -> study
            Topic.WORK -> work
            Topic.CITY -> city
            Topic.PET -> pet(m.value)
            Topic.BIRTHDAY -> birthday
            Topic.THEIR_BIRTHDAY -> {
                val asked = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).lowercase().replace('ё', 'е')
                val key = genOf[asked] ?: asked
                theirBirthdays(memories).firstOrNull { (who, _) -> who.lowercase().replace('ё', 'е').split(' ').last() == key.split(' ').last().replace('ё', 'е') }
                    ?.let { (who, md) -> if (isRu) "у $who день рождения ${md.dayOfMonth} ${monthsGen[md.monthValue - 1]}" else "your $who's birthday is ${monthsEn[md.monthValue - 1]} ${md.dayOfMonth}" }
            }
            Topic.AGE -> age
            Topic.FAMILY -> relative(m.value)
            Topic.LIKES -> tastes.likes
            Topic.DISLIKES -> tastes.dislikes
            Topic.FAVORITE -> favorite(m)
            Topic.PLANS -> plans(m.value)
            Topic.ALL -> {
                val known = listOfNotNull(name?.let { if (isRu) "тебя зовут $it" else "your name is $it" }, age, study, work, city, birthday) +
                    petFacts.map { (k, n) -> if (isRu) "у тебя есть $k по имени $n" else "you have a $k named $n" } +
                    familyFacts.take(3).map(::familyLine) + listOfNotNull(tastes.likes) +
                    listOfNotNull(plans.take(2).takeIf { it.isNotEmpty() }?.let { p -> (if (isRu) "в планах: " else "your plans: ") +
                        p.joinToString("; ") { "${PlanTalk.whenText(it.plan.date, today, isRu)} — ${it.plan.what}" } })
                if (known.isEmpty()) return null
                // Memories the answer covers: one each for the simple facts, every pet, relative, taste and plan named.
                val used = listOfNotNull(name, age, study, work, city, birthday).size + petFacts.size + familyFacts.take(3).size + tastes.likeCount + plans.take(2).size
                val rest = memories.count { it.kind != MemoryKind.EPISODE } - used
                return (if (isRu) "Вот что я о тебе знаю: " else "Here's what I know about you: ") + joined(known, isRu) + "." +
                    if (rest > 0) (if (isRu) " И ещё записей: $rest — их видно на вкладке «Память»." else " And $rest more entries — see the Memory tab.") else ""
            }
        } ?: return null
        return (if (isRu) "Мрр, помню: " else "Mrr, I remember: ") + joined(parts.distinct(), isRu) + "."
    }

    /** «тебе 25 лет» from «Владельцу 25 лет (на ноябрь 2026)», a year older for each year since (or each birthday, when known). */
    private fun ageNow(newest: List<MemoryItem>, today: java.time.LocalDate, isRu: Boolean): String? {
        val (n, said) = newest.firstNotNullOfOrNull { item ->
            Regex("""^Владельцу (\d{1,3}) (?:лет|год|года) \(на (\p{L}+) (\d{4})\)$""").find(item.text)?.let { m ->
                m.groupValues[1].toInt() to runCatching { java.time.LocalDate.of(m.groupValues[3].toInt(), monthsNom.indexOf(m.groupValues[2]) + 1, 1) }.getOrNull()
            } ?: Regex("""^The owner is (\d{1,3}) \(as of (\p{L}+) (\d{4})\)$""").find(item.text)?.let { m ->
                m.groupValues[1].toInt() to runCatching { java.time.LocalDate.of(m.groupValues[3].toInt(), monthsEn.indexOf(m.groupValues[2]) + 1, 1) }.getOrNull()
            }
        } ?: return null
        val bd = birthday(newest)
        val grown = when {
            said == null -> 0
            bd != null -> generateSequence(said) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.count { java.time.MonthDay.from(it) == bd && it.isAfter(said.withDayOfMonth(said.lengthOfMonth())) }
            else -> java.time.temporal.ChronoUnit.YEARS.between(said, today).toInt()
        }
        val now = n + grown
        return if (isRu) "тебе ${years(now)}" else "you're $now"
    }

    private class Tastes(val likes: String?, val dislikes: String?, val likeCount: Int)

    /** What the owner likes and dislikes, as said to them: «ты любишь пиццу и джаз», «ты не любишь дождь». */
    private fun tastes(newest: List<MemoryItem>, isRu: Boolean): Tastes {
        val good = ArrayList<String>()
        val bad = ArrayList<String>()
        val loves = ArrayList<String>()
        for (item in newest) {
            val t = item.text
            if (isRu) {
                Regex("""^Владелец (любит|обожает|не любит|ненавидит|терпеть не может|не выносит)(,? .+)$""").find(t)?.let { m ->
                    val obj = m.groupValues[2]
                    when (m.groupValues[1]) {
                        "любит" -> loves += obj.trim()
                        "обожает" -> good += "ты обожаешь$obj"
                        "не любит" -> bad += "ты не любишь$obj"
                        "ненавидит" -> bad += "ты ненавидишь$obj"
                        "терпеть не может" -> bad += "ты терпеть не можешь$obj"
                        "не выносит" -> bad += "ты не выносишь$obj"
                    }
                }
                Regex("""^Владельцу (не )?(нравится|нравятся)(,? .+)$""").find(t)?.let { m -> (if (m.groupValues[1].isEmpty()) good else bad) += "тебе ${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}" }
            } else {
                Regex("""^The owner (loves|adores|likes|enjoys|hates|dislikes|can't stand|doesn't like) (.+)$""").find(t)?.let { m ->
                    val verb = m.groupValues[1]
                    val second = mapOf("loves" to "love", "adores" to "adore", "likes" to "like", "enjoys" to "enjoy", "hates" to "hate", "dislikes" to "dislike",
                        "can't stand" to "can't stand", "doesn't like" to "don't like")[verb]
                    (if (verb in setOf("hates", "dislikes", "can't stand", "doesn't like")) bad else good) += "you $second ${m.groupValues[2]}"
                }
            }
        }
        val others = good.take(if (loves.isEmpty()) 4 else 3)
        val all = (if (loves.isEmpty()) emptyList() else listOf("ты любишь " + joined(loves.take(4).map { it.removePrefix(",").trim() }, true))) + others
        val likes = all.takeIf { it.isNotEmpty() }?.let { joined(it, isRu) }
        val dislikes = bad.take(3).takeIf { it.isNotEmpty() }?.let { joined(it, isRu) }
        return Tastes(likes, dislikes, loves.take(4).size + others.size)
    }

    private fun joined(parts: List<String>, ru: Boolean): String = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] + (if (ru) ", и " else ", and ") + parts[1]
        else -> parts.dropLast(1).joinToString(", ") + (if (ru) " и " else ", and ") + parts.last()
    }

    /** The owner's name from memory ("Владельца зовут Аня" / "The owner's name is Anna"). */
    fun ownerName(memories: List<MemoryItem>): String? {
        val re = Regex("""(?iu:^владельца\s+зовут|имя\s+владельца\s*[—–-]?|the\s+owner's\s+name\s+is|owner's\s+name:?)\s+(\p{L}[\p{L}-]*(?:\s+\p{Lu}[\p{L}-]*)?)""")
        return memories.sortedByDescending { it.createdAtMs }.firstNotNullOfOrNull { re.find(it.text)?.groupValues?.get(1) }
    }

    /** The owner's birthday from memory ("День рождения владельца: 12 мая"), if they told it. */
    fun birthday(memories: List<MemoryItem>): java.time.MonthDay? {
        val re = Regex("""(?iu)(?:день\s+рождения\s+владельца|the\s+owner's\s+birthday):?\s+(.+)""")
        return memories.sortedByDescending { it.createdAtMs }.firstNotNullOfOrNull { m -> re.find(m.text)?.let { TimeTalk.monthDay(it.groupValues[1]) } }
    }

    fun birthdayGreeting(name: String?): String {
        val n = name?.let { ", $it" }.orEmpty()
        return tr("С днём рождения$n! Мрр, сегодня твой день — дарю самое тёплое «мяу» и обещаю не ронять ничего со стола. Ну, почти.",
            "Happy birthday$n! Mrr, today is your day — here's my warmest meow, and I promise not to knock anything off the table. Almost.")
    }

    /** What to forget, or "" for "forget everything" (refused: that's a button in the Memory tab), or null. */
    fun forgetQuery(text: String): String? {
        val t = text.trim().trimEnd('.', '!')
        if (forgetAll.containsMatchIn(t)) return ""
        return forget.find(t)?.groupValues?.get(1)?.trim()
    }
}
