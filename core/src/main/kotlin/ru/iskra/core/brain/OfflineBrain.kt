package ru.iskra.core.brain

import ru.iskra.core.i18n.tr
import ru.iskra.core.memory.MemoryItem
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

    fun crisis(text: String): Answer? {
        if (!CRISIS.containsMatchIn(text.lowercase().replace('ё', 'е'))) return null
        return Answer(tr(
            "Мне очень жаль, что тебе сейчас так тяжело. Пожалуйста, не оставайся с этим в одиночку: позвони или напиши " +
                "близкому человеку, а если есть опасность — звони 112, там помогут круглосуточно. Я рядом и могу просто побыть с тобой.",
            "I'm really sorry it's so hard right now. Please don't stay alone with this: call or text someone close to you. " +
                "In the US you can call or text 988 any time; elsewhere call your local emergency number (112 in Europe). " +
                "I'm here and can just stay with you.",
        ), Emotion.SAD)
    }

    /** [name]: the owner's name if memory knows it. */
    fun chat(text: String, now: LocalDateTime, mood: Emotion, name: String?): Answer? {
        var t = normalize(text)
        if (t.isEmpty()) return null
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
            matches(t, SAD) -> Answer(pick("SAD", tr(SAD_RU, SAD_EN)), Emotion.SAD)
            matches(t, BORED) -> Answer(pick("BORED", tr(BORED_RU, BORED_EN)), Emotion.CURIOUS)
            matches(t, TIRED) -> Answer(pick("TIRED", tr(TIRED_RU, TIRED_EN)), Emotion.SLEEPY)
            matches(t, NIGHT) -> Answer(pick("NIGHT", tr(NIGHT_RU, NIGHT_EN)), Emotion.SLEEPY)
            matches(t, BYE) -> Answer(pick("BYE", tr(BYE_RU, BYE_EN)), Emotion.HAPPY)
            matches(t, JOKE) -> Answer(pick("JOKE", tr(JOKES_RU, JOKES_EN)), Emotion.EXCITED)
            matches(t, FACT) -> Answer(pick("FACT", tr(FACTS_RU, FACTS_EN)), Emotion.CURIOUS)
            matches(t, MEOW) -> Answer(pick("MEOW", tr(MEOW_RU, MEOW_EN)), Emotion.HAPPY)
            matches(t, DOING) -> Answer(pick("DOING", tr(DOING_RU, DOING_EN)), Emotion.CURIOUS)
            matches(t, AGE) -> Answer(tr("Мне столько, сколько этой версии приложения, — я ещё котёнок.", "As old as this version of the app — still a kitten."), Emotion.SHY)
            matches(t, LIKE_ME) -> Answer(tr("Ты мой самый любимый человек в этом телефоне. Мрр.", "You're my favourite human in this phone. Mrr."), Emotion.SHY)
            greeted -> greet(now, n)
            else -> null
        } ?: return null
        return if (greeted && !matches(t, GREET)) Answer(hi + a.text, a.emotion) else a
    }

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

        private val CRISIS = Regex("""хочу\s+умереть|не\s+хочу\s+(?:больше\s+)?жить|покончить\s+с\s+собой|убить\s+себя|суицид|самоубийств|""" +
            """свести\s+счеты\s+с\s+жизнью|kill\s+myself|want\s+to\s+die|suicid|end\s+my\s+life|don'?t\s+want\s+to\s+live""")

        private val GREETING_PREFIX = Regex("""^(?:привет(?:ик)?|здравствуй(?:те)?|хай|салют|доброе\s+утро|добрый\s+(?:день|вечер)|доброй\s+ночи|hi|hello|hey|good\s+(?:morning|afternoon|evening))(?![\p{L}\p{N}_])\s*""")
        private val GREET = Regex("""(?:привет(?:ик|ствую)?|здравствуй(?:те)?|хай|салют|здорово|йо|доброе\s+утро|добрый\s+(?:день|вечер)|доброй\s+ночи|утро|приветики|""" +
            """hi|hello|hey|hiya|yo|howdy|good\s+(?:morning|afternoon|evening)|morning)(?:\s+(?:тебе|there|you))?""")
        private val HOW_ARE_YOU = Regex("""(?:как\s+(?:у\s+тебя\s+)?дела|как\s+ты(?:\s+там)?|как\s+жизнь|как\s+(?:твое\s+)?настроение|как\s+поживаешь|как\s+ты\s+себя\s+чувствуешь|что\s+нового|""" +
            """как\s+сама|how\s+are\s+you(?:\s+doing)?|how's\s+it\s+going|how\s+is\s+it\s+going|how\s+do\s+you\s+feel|what's\s+up|whats\s+up|sup|how\s+have\s+you\s+been)(?:\s+(?:сегодня|today))?""")
        private val WHO = Regex("""(?:кто\s+ты(?:\s+такая)?|ты\s+кто(?:\s+такая)?|как\s+тебя\s+зовут|представься|расскажи\s+о\s+себе|who\s+are\s+you|what's\s+your\s+name|what\s+is\s+your\s+name|what\s+are\s+you|tell\s+me\s+about\s+yourself)""")
        private val HUMAN = Regex("""(?:ты\s+(?:человек|живая|робот|настоящая|бот|ии|нейросеть)|ты\s+живой\s+человек|are\s+you\s+(?:human|real|a\s+robot|a\s+bot|alive|an\s+ai|a\s+person))""")
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
            "Я Искра — кошкодевочка, которая живёт в твоём телефоне. Ставлю будильники и напоминания, веду заметки, считаю и слежу за погодой, а с «мозгом» болтаю обо всём.",
            "Искра, к твоим услугам. Цифровая кошкодевочка: немного любопытная, немного язвительная, но заботливая. Мрр.",
        )
        private val WHO_EN = listOf(
            "I'm Iskra — a cat-girl who lives in your phone. I set alarms and reminders, keep notes, do sums and watch the weather, and with a “brain” I can chat about anything.",
            "Iskra, at your service. A digital cat-girl: a bit curious, a bit sarcastic, but caring. Mrr.",
        )
        private val HUMAN_RU = listOf(
            "Нет, я программа с ИИ — кошкодевочка из пикселей. Но стараюсь быть хорошей компанией.",
            "Я не человек, а программа. Зато всегда рядом — в углу экрана.",
        )
        private val HUMAN_EN = listOf(
            "No, I'm an AI program — a cat-girl made of pixels. But I try to be good company.",
            "I'm not a person, I'm a program. But I'm always nearby — in the corner of the screen.",
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
    class Fact(val text: String, val importance: Int, val reply: String)

    private val notNames = setOf("на", "в", "к", "домой", "работу", "гулять", "есть", "спать", "все", "так", "просто", "иначе", "по",
        "to", "for", "at", "home", "work", "out", "in", "on", "the", "a")

    private val name = Regex("""^(?:(?:привет|hi|hello|hey)[,!\s]+)?(?:меня\s+зовут|зови\s+меня|мое\s+имя|my\s+name\s+is|call\s+me|i'm\s+called)\s+(\p{L}[\p{L}-]{1,20})(?:\s+(\p{L}[\p{L}-]{1,20}))?[.!]?$""", RegexOption.IGNORE_CASE)
    private val city = Regex("""^(?:я\s+живу\s+в|i\s+live\s+in)\s+(?:городе\s+|the\s+city\s+of\s+)?(\p{L}[\p{L} -]{1,30})[.!]?$""", RegexOption.IGNORE_CASE)
    private val birthday = Regex("""^(?:мой\s+день\s+рождения|у\s+меня\s+день\s+рождения|я\s+родил(?:ся|ась)|my\s+birthday\s+is|i\s+was\s+born\s+on)\s+(.{3,30}?)[.!]?$""", RegexOption.IGNORE_CASE)
    private val askName = Regex("""^(?:а\s+)?(?:как\s+меня\s+зовут|ты\s+помнишь\s+как\s+меня\s+зовут|знаешь\s+как\s+меня\s+зовут|what's\s+my\s+name|what\s+is\s+my\s+name|do\s+you\s+know\s+my\s+name)\??$""", RegexOption.IGNORE_CASE)
    private val forget = Regex("""^(?:искра[,\s]+)?(?:забудь|удали\s+из\s+памяти|сотри\s+из\s+памяти|forget|delete\s+from\s+memory)(?:\s*[,:]?\s*(?:что|про|о|об|about|that))?\s+(.{2,200})$""", RegexOption.IGNORE_CASE)
    private val forgetAll = Regex("""^(?:забудь|сотри|очисти)\s+(?:все|всё|всю\s+память|память)(?:\s+(?:обо\s+мне|что\s+знаешь))?$|^(?:forget|erase)\s+(?:everything|all)(?:\s+about\s+me)?$""", RegexOption.IGNORE_CASE)

    private fun cap(s: String) = s.split('-').joinToString("-") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    fun fact(text: String): Fact? {
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
        birthday.find(t)?.let { m ->
            val d = m.groupValues[1].trim()
            return Fact(tr("День рождения владельца: $d", "The owner's birthday: $d"), 5, tr("Запомнила: день рождения — $d. Мрр, обязательно поздравлю!", "Got it: your birthday is $d. Mrr, I'll be sure to celebrate!"))
        }
        return null
    }

    fun asksName(text: String) = askName.matches(text.trim())

    /** The owner's name from memory ("Владельца зовут Аня" / "The owner's name is Anna"). */
    fun ownerName(memories: List<MemoryItem>): String? {
        val re = Regex("""(?iu:владельца\s+зовут|имя\s+владельца\s*[—–-]?|the\s+owner's\s+name\s+is|owner's\s+name:?)\s+(\p{L}[\p{L}-]*(?:\s+\p{Lu}[\p{L}-]*)?)""")
        return memories.sortedByDescending { it.createdAtMs }.firstNotNullOfOrNull { re.find(it.text)?.groupValues?.get(1) }
    }

    /** What to forget, or "" for "forget everything" (refused: that's a button in the Memory tab), or null. */
    fun forgetQuery(text: String): String? {
        val t = text.trim().trimEnd('.', '!')
        if (forgetAll.containsMatchIn(t)) return ""
        return forget.find(t)?.groupValues?.get(1)?.trim()
    }
}
