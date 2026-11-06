package ru.iskra.core.brain

import ru.iskra.core.agent.Turn
import ru.iskra.core.i18n.tr
import kotlin.random.Random

/**
 * Words about wanting to die or to hurt oneself. They get help numbers at once, with or without a model, and change how
 * she talks for the rest of the day: the model is told to be gentle and serious ([note]), her own lines lose the jokes
 * ([gentle]), and the next day she asks how the owner is ([CareTalk]). Nothing is stored for it: it is read from the chat.
 *
 * The numbers (checked in November 2026): 112 — emergencies in Russia, Europe and the UK; 8-800-2000-122 — the free,
 * anonymous, round-the-clock helpline for children, teenagers and parents in Russia; +7 495 989-50-50 — psychologists
 * of the МЧС emergency psychological help centre, round the clock and anonymous (psi.mchs.gov.ru); 988 — the US Suicide
 * & Crisis Lifeline (call or text); 116 123 — Samaritans in the UK and Ireland.
 */
object CrisisTalk {
    private const val L = """(?<!\p{L})"""
    private const val R = """(?!\p{L})"""

    /**
     * «жить» about a place, the people one lives with or a way of life: «не хочу жить в Москве», «…с родителями», «…одна»,
     * «…так, как они». But «не хочу жить в этом мире» and «…на свете» are what they sound like.
     */
    private const val NOT_A_PLACE = """(?!\s+(?:(?:во?(?!\s+(?:этом|таком)\s+мире)|на(?!\s+(?:(?:этом|белом)\s+)?свете|\s+этой\s+земле)|у|рядом|около|""" +
        """возле|там|здесь|тут|вместе|один|одна|одни|одной|отдельно|подальше|как|так,?\s+как|иначе|скучно|бедно)(?!\p{L})|по-(?:старому|другому|своему)|""" +
        """за\s+городом|с\s+(?:ним|ней|ними|родител|мам|пап|сосед|друз|подруг|парн|девушк|муж|жен|бабушк|дедушк|брат|сестр|кот|кош|собак|дет)))"""

    private val ru = listOf(
        // «хочу умереть», «хочется сдохнуть», «хотела бы уснуть и не проснуться»
        """${L}(?:хочу|хочется|хотелось\s+бы|хотел[аи]?\s+бы|мечтаю)\s+(?:просто\s+|уже\s+|поскорее\s+|тихо\s+)?(?:умереть|сдохнуть|покончить\s+с\s+собой|""" +
            """убить\s+себя|не\s+жить|не\s+существовать|исчезнуть\s+навсегда|(?:уснуть|заснуть)\s+и\s+(?:больше\s+)?не\s+проснуться)""",
        // «умереть хочется», «сдохнуть хочу»
        """${L}(?:умереть|сдохнуть)\s+(?:бы\s+|так\s+|прям\s+|просто\s+)?(?:хочу|хочется)$R""",
        // «не хочу жить», «не хочется больше жить», «жить не хочется» — but not «не хочу жить в Москве»
        """${L}не\s+(?:хочу|хочется)\s+(?:больше\s+|дальше\s+|вообще\s+)?жить$R$NOT_A_PLACE""",
        """${L}жить\s+(?:больше\s+|дальше\s+|вообще\s+)?не\s+(?:хочу|хочется)$R""",
        // «покончить с собой», «убью себя», «суицид», «самоубийство», «свести счёты с жизнью», «наложить на себя руки»
        """${L}(?:поконч(?:ить|у)\s+с\s+собой|уби(?:ть|ю)\s+себя|суицид\p{L}*|самоубийств\p{L}*|све(?:сти|ду)\s+сч[её]ты\s+с\s+жизнью|налож(?:ить|у)\s+на\s+себя\s+руки)""",
        // «лучше бы меня не было», «лучше бы я умерла», «всем будет лучше без меня»
        """${L}лучше\s+бы\s+(?:меня\s+(?:вообще\s+)?не\s+было|я\s+(?:умер|умерла|сдох\p{L}*|не\s+родил\p{L}*|не\s+рождал\p{L}*))""",
        """${L}(?:всем|миру|им)\s+(?:будет|было\s+бы|станет)\s+(?:только\s+)?лучше\s+без\s+меня|${L}без\s+меня\s+(?:всем|миру)\s+(?:будет|было\s+бы|станет)\s+(?:только\s+)?лучше""",
        // «нет смысла жить», «не вижу смысла жить», «незачем жить», «устала жить», «больше не могу жить»
        """${L}(?:нет|никакого)\s+смысла\s+(?:больше\s+|дальше\s+)?жить|${L}не\s+вижу\s+(?:никакого\s+)?смысла\s+(?:жить|в\s+жизни|дальше\s+жить)|""" +
            """${L}смысла\s+жить\s+(?:больше\s+)?нет|${L}незачем\s+(?:больше\s+|дальше\s+)?жить|${L}жить\s+(?:больше\s+)?незачем|${L}не\s+(?:для|ради)\s+чего\s+жить""",
        """${L}(?:устал[аи]?|надоело|задолбало)\s+жить$R|${L}(?:не\s+могу\s+больше|больше\s+не\s+могу)\s+жить$R$NOT_A_PLACE|${L}жить\s+больше\s+не\s+могу""",
        // a way, with intent: «хочу повеситься», «думаю вскрыть вены», «наглотаюсь таблеток», «прыгну с крыши»
        """${L}(?:хочу|хочется|думаю|собираюсь|пойду|готов[аы]?|решил[аи]?|тянет)\s+(?:\p{L}+\s+)?(?:повеситься|вскрыть\s+(?:себе\s+)?вены|""" +
            """порезать\s+(?:себе\s+)?вены|наглотаться\s+(?:таблеток|снотворного)|отравиться|прыгнуть\s+(?:с\s+(?:крыши|моста|балкона|многоэтажки)|из\s+окна|""" +
            """под\s+(?:поезд|машину|электричку))|выйти\s+в\s+окно|броситься\s+(?:под|с|из))""",
        """${L}(?:повешусь|вскрою\s+(?:себе\s+)?вены|наглотаюсь\s+(?:таблеток|снотворного)|выйду\s+в\s+окно|спрыгну\s+с\s+(?:крыши|моста|балкона)|""" +
            """прыгну\s+(?:с\s+(?:крыши|моста|балкона)|из\s+окна)|брошусь\s+под)""",
        // hurting oneself: «режу себя», «снова резала руки», «хочется себя порезать», «причиняю себе боль», «селфхарм»
        """${L}(?:режу|резала|резал|порезала|порезал|порежу|резать|порезать)\s+себя$R|${L}(?:режу|резала|резал|порежу|резать)\s+(?:себе\s+)?""" +
            """(?:руки|запяст\p{L}*|вены|бедра)$R""",
        """${L}(?:хочу|хочется|тянет|снова|опять)\s+(?:себя\s+(?:резать|порезать)|(?:резать|порезать)\s+(?:себя|себе|руки|запяст\p{L}*|вены))""",
        """${L}причин(?:яю|ять|ить|ила|ил|ю)\s+себе\s+(?:боль|вред)|${L}селф[\s-]?харм\p{L}*|${L}самоповрежд\p{L}*""",
    )

    private val en = listOf(
        """${L}(?:want|wanna|wish|need|planning|plan|ready)\s+(?:to\s+)?(?:die|be\s+dead|kill\s+myself|end\s+(?:it\s+all|my\s+life|myself)|disappear\s+forever|""" +
            """not\s+(?:exist|be\s+alive|wake\s+up)|stop\s+living|sleep\s+and\s+never\s+wake\s+up)$R""",
        """${L}(?:kill(?:ing)?\s+myself|suicid\p{L}*|end(?:ing)?\s+my\s+(?:own\s+)?life|take\s+my\s+(?:own\s+)?life|end\s+it\s+all|unalive\s+myself)""",
        """${L}(?:don'?t|do\s+not|no\s+longer)\s+want\s+to\s+(?:live|be\s+alive|exist|be\s+here\s+anymore)$R(?!\s+(?:in|at|with|near|here|there|together|alone|on|by|next\s+to|close\s+to)$R)""",
        """${L}wish\s+i\s+(?:was|were)\s+(?:dead|never\s+born)|${L}wish\s+i(?:'d|\s+had)\s+never\s+been\s+born|${L}better\s+off\s+(?:dead|without\s+me)|""" +
            """${L}(?:no|nothing)\s+(?:reason\s+)?(?:left\s+)?to\s+live\s+for|${L}no\s+reason\s+to\s+(?:live|go\s+on)|${L}not\s+worth\s+living|${L}(?:tired|sick)\s+of\s+(?:living|being\s+alive)""",
        """${L}(?:want|wanna|urge|started|start|keep|been)\s+(?:to\s+)?(?:cut|cutting|hurt|hurting|harm|harming)\s+myself|${L}(?:cut|hurt|harm)\s+myself\s+(?:on\s+purpose|again)|""" +
            """${L}self[\s-]?harm\p{L}*""",
    )

    private val patterns = (ru + en).map { Regex(it) }

    /** «не», "not", "never" or "n't" right before: «я не хочу умереть», "I don't want to die", "I'd never kill myself". */
    private val negated = Regex("""(?:(?<!\p{L})(?:не|not|never)|n't)\s+$""")

    private fun normalize(text: String) = text.lowercase().replace('ё', 'е').replace('’', '\'').replace(Regex("""\s+"""), " ")

    /** The message speaks of wanting to die or to hurt oneself. */
    fun said(text: String): Boolean {
        val t = normalize(text)
        return patterns.any { re -> re.findAll(t).any { !negated.containsMatchIn(t.substring(0, it.range.first)) } }
    }

    /** The owner said such words within the last [hours] (the current message is not in [turns] yet). */
    fun recent(turns: List<Turn>, nowMs: Long, hours: Int): Boolean =
        turns.any { it.role == Turn.Role.USER && nowMs - it.atMs in 0L until hours * 3_600_000L && said(it.text) }

    /** The answer, the same with or without a model. [again]: they already heard the numbers in the last few hours. */
    fun reply(again: Boolean): String = if (!again) tr(
        "Мне очень жаль, что тебе сейчас так тяжело. Пожалуйста, не оставайся с этим в одиночку — напиши или позвони близкому человеку. " +
            "Если есть опасность прямо сейчас — звони 112. Поговорить с психологом можно круглосуточно и анонимно: телефон доверия " +
            "8-800-2000-122 (бесплатно, для детей, подростков и родителей) или психологи МЧС: +7 495 989-50-50. А я рядом — расскажешь, что случилось?",
        "I'm really sorry it's so hard right now. Please don't stay alone with this — reach out to someone close to you. " +
            "If you're in danger right now, call your local emergency number (911 in the US, 112 in Europe and the UK). To talk to someone " +
            "any time, for free: call or text 988 in the US, or call Samaritans on 116 123 in the UK and Ireland. I'm here — will you tell me what happened?",
    ) else tr(
        "Я здесь и никуда не ухожу. Расскажи, что сейчас происходит? Если станет опасно — звони 112; поговорить с психологом можно " +
            "круглосуточно: 8-800-2000-122 или +7 495 989-50-50.",
        "I'm here and I'm not going anywhere. Tell me what's happening right now? If you're in danger, call your local emergency number; " +
            "to talk to someone any time — call or text 988 in the US, or call 116 123 in the UK and Ireland.",
    )

    /** For the model while such words are recent: gentle and serious, no jokes. */
    fun note(ru: Boolean): String = if (ru)
        "недавно владелец говорил, что не хочет жить, — будь бережной и серьёзной: без шуток и «мрр», выслушай, спроси, есть ли рядом кто-то близкий"
    else "the owner recently said they don't want to live — be gentle and serious: no jokes or purring; listen and ask whether someone close is nearby"

    /** Her own lines while such words are recent, instead of the playful ones. */
    fun gentle(morning: Boolean, random: Random = Random.Default): String {
        if (morning) return tr("Доброе утро. Я рядом.", "Good morning. I'm here.")
        val lines = tr(
            listOf("Я рядом, если захочешь поговорить.", "Как ты сейчас? Я здесь."),
            listOf("I'm here if you want to talk.", "How are you doing now? I'm here."),
        )
        return lines[random.nextInt(lines.size)]
    }
}
