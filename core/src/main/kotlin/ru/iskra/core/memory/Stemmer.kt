package ru.iskra.core.memory

/**
 * The Snowball Russian stemmer (snowballstem.org/algorithms/russian), so "живу", "живёт" and "жила" meet at "жив",
 * and a light English one (plural, -ing, -ed, -ly). Memory search and duplicate checks compare stems.
 */
object Stemmer {
    private const val VOWELS = "аеиоуыэюя"

    private val perfectiveGerund1 = listOf("вшись", "вши", "в")
    private val perfectiveGerund2 = listOf("ившись", "ывшись", "ивши", "ывши", "ив", "ыв")
    private val adjective = listOf("ими", "ыми", "его", "ого", "ему", "ому", "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой", "ем", "им",
        "ым", "ом", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею")
    private val participle1 = listOf("ем", "нн", "вш", "ющ", "щ")
    private val participle2 = listOf("ивш", "ывш", "ующ")
    private val reflexive = listOf("ся", "сь")
    private val verb1 = listOf("ете", "йте", "ешь", "нно", "ла", "на", "ли", "ем", "ло", "но", "ет", "ют", "ны", "ть", "й", "л", "н")
    private val verb2 = listOf("ейте", "уйте", "ила", "ыла", "ена", "ите", "или", "ыли", "ило", "ыло", "ено", "ует", "уют", "ены", "ить",
        "ыть", "ишь", "ей", "уй", "ил", "ыл", "им", "ым", "ен", "ят", "ит", "ыт", "ую", "ю")
    private val noun = listOf("иями", "ями", "ами", "ией", "иям", "ием", "иях", "ев", "ов", "ие", "ье", "еи", "ии", "ей", "ой", "ий", "ям",
        "ем", "ам", "ом", "ах", "ях", "ию", "ью", "ия", "ья", "а", "е", "и", "й", "о", "у", "ы", "ь", "ю", "я")
    private val superlative = listOf("ейше", "ейш")
    private val derivational = listOf("ость", "ост")

    fun stem(word: String): String {
        val w = word.lowercase().replace('ё', 'е')
        if (w.isEmpty()) return w
        return if (w.any { it in 'а'..'я' }) russian(w) else english(w)
    }

    private fun russian(word: String): String {
        val rv = word.indexOfFirst { it in VOWELS }.let { if (it < 0) word.length else it + 1 }
        val r1 = region(word, 0)
        val r2 = region(word, r1)
        var w = word

        fun endsIn(s: String, from: Int, list: List<String>): String? =
            list.filter { s.endsWith(it) && s.length - it.length >= from }.maxByOrNull { it.length }
        /** Group-1 endings must follow "а" or "я" (which stays). */
        fun endsAfterAYa(s: String, from: Int, list: List<String>): String? =
            list.filter { s.endsWith(it) && s.length - it.length - 1 >= from && s[s.length - it.length - 1] in "ая" }.maxByOrNull { it.length }
        fun best(s: String, from: Int, g1: List<String>, g2: List<String>): String? =
            listOfNotNull(endsAfterAYa(s, from, g1), endsIn(s, from, g2)).maxByOrNull { it.length }

        // Step 1
        val pg = best(w, rv, perfectiveGerund1, perfectiveGerund2)
        if (pg != null) {
            w = w.dropLast(pg.length)
        } else {
            endsIn(w, rv, reflexive)?.let { w = w.dropLast(it.length) }
            val adj = endsIn(w, rv, adjective)
            if (adj != null) {
                w = w.dropLast(adj.length)
                best(w, rv, participle1, participle2)?.let { w = w.dropLast(it.length) }
            } else {
                val v = best(w, rv, verb1, verb2)
                if (v != null) w = w.dropLast(v.length)
                else endsIn(w, rv, noun)?.let { w = w.dropLast(it.length) }
            }
        }
        // Step 2
        if (w.endsWith("и") && w.length - 1 >= rv) w = w.dropLast(1)
        // Step 3
        endsIn(w, r2, derivational)?.let { w = w.dropLast(it.length) }
        // Step 4
        when {
            w.endsWith("нн") && w.length - 2 >= rv -> w = w.dropLast(1)
            endsIn(w, rv, superlative) != null -> {
                w = w.dropLast(endsIn(w, rv, superlative)!!.length)
                if (w.endsWith("нн")) w = w.dropLast(1)
            }
            w.endsWith("ь") && w.length - 1 >= rv -> w = w.dropLast(1)
        }
        return w
    }

    /** Start of the region after the first non-vowel that follows a vowel, searching from [from]. */
    private fun region(w: String, from: Int): Int {
        var i = from
        while (i < w.length && w[i] !in VOWELS) i++
        while (i < w.length && w[i] in VOWELS) i++
        return if (i < w.length) i + 1 else w.length
    }

    private fun english(word: String): String {
        var w = word.replace("'s", "").replace("’s", "")
        if (w.length <= 3) return w
        w = when {
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") -> w
            w.endsWith("s") -> w.dropLast(1)
            else -> w
        }
        for (suf in listOf("ingly", "edly", "ing", "ed", "ly")) {
            if (w.endsWith(suf) && w.length - suf.length >= 3 && w.dropLast(suf.length).any { it in "aeiouy" }) {
                w = w.dropLast(suf.length)
                if (w.length >= 3 && w[w.length - 1] == w[w.length - 2] && w.last() !in "lsz") w = w.dropLast(1)   // running → run
                break
            }
        }
        if (w.endsWith("y") && w.length > 3 && w[w.length - 2] !in "aeiou") w = w.dropLast(1) + "i"   // happy → happi, happiness → happi
        if (w.endsWith("e") && w.length > 4) w = w.dropLast(1)   // love/loved/loving → lov
        return w
    }
}

/**
 * Words that mean the same thing for memory, across Russian and English: a question about "собака" finds
 * "у владельца пёс Рекс", "my dog" finds "собака". Each group becomes one extra token ("#dog").
 */
object Concepts {
    private val groups: Map<String, List<String>> = mapOf(
        "dog" to listOf("собака", "собачка", "пёс", "пес", "щенок", "псина", "dog", "puppy", "doggy"),
        "cat" to listOf("кошка", "кот", "котёнок", "котенок", "котик", "кошечка", "cat", "kitten", "kitty"),
        "mother" to listOf("мама", "мать", "мамочка", "матушка", "mom", "mother", "mum", "mommy", "mama"),
        "father" to listOf("папа", "отец", "батя", "dad", "father", "daddy"),
        "wife" to listOf("жена", "супруга", "wife"),
        "husband" to listOf("муж", "супруг", "husband"),
        "child" to listOf("ребёнок", "ребенок", "дети", "сын", "дочь", "дочка", "сынок", "малыш", "kid", "child", "children", "son", "daughter", "baby"),
        "brother" to listOf("брат", "братик", "brother"),
        "sister" to listOf("сестра", "сестрёнка", "sister"),
        "grandma" to listOf("бабушка", "бабуля", "grandma", "grandmother", "granny"),
        "grandpa" to listOf("дедушка", "дед", "grandpa", "grandfather"),
        "friend" to listOf("друг", "подруга", "приятель", "дружище", "friend", "buddy"),
        "work" to listOf("работа", "работаю", "работает", "офис", "профессия", "должность", "job", "work", "office", "career", "profession"),
        "study" to listOf("учёба", "учеба", "учусь", "университет", "институт", "универ", "школа", "колледж", "студент", "study", "school", "university", "college", "student"),
        "home" to listOf("дом", "квартира", "жильё", "жилье", "home", "house", "apartment", "flat"),
        "live" to listOf("живу", "живёт", "живет", "жить", "переехал", "переехала", "live", "lives", "moved"),
        "car" to listOf("машина", "авто", "автомобиль", "тачка", "car", "vehicle"),
        "birthday" to listOf("рождения", "рождение", "днюха", "birthday", "born"),
        "coffee" to listOf("кофе", "капучино", "латте", "эспрессо", "coffee", "cappuccino", "latte", "espresso"),
        "tea" to listOf("чай", "tea"),
        "music" to listOf("музыка", "песня", "песни", "трек", "music", "song", "songs", "track"),
        "movie" to listOf("фильм", "кино", "сериал", "мультик", "movie", "film", "series", "cartoon"),
        "book" to listOf("книга", "книжка", "читать", "роман", "book", "novel", "reading"),
        "sport" to listOf("спорт", "тренировка", "спортзал", "фитнес", "бег", "футбол", "sport", "gym", "workout", "running", "fitness", "football"),
        "health" to listOf("здоровье", "болезнь", "врач", "доктор", "лекарство", "таблетки", "health", "doctor", "medicine", "pills", "sick"),
        "allergy" to listOf("аллергия", "аллергию", "allergy", "allergic"),
        "vacation" to listOf("отпуск", "каникулы", "путешествие", "поездка", "отдых", "vacation", "holiday", "trip", "travel"),
        "party" to listOf("праздник", "вечеринка", "party", "celebration"),
        "money" to listOf("деньги", "зарплата", "money", "salary"),
        "name" to listOf("имя", "зовут", "звать", "name", "called"),
        "phone" to listOf("телефон", "смартфон", "phone", "smartphone"),
        "game" to listOf("игра", "игры", "играть", "game", "games", "gaming"),
        "like" to listOf("любимый", "любимая", "любимое", "люблю", "любит", "нравится", "обожаю", "favorite", "favourite", "love", "loves", "like", "likes"),
        "dislike" to listOf("ненавижу", "терпеть", "hate", "hates", "dislike", "dislikes"),
        "sleep" to listOf("сон", "спать", "сплю", "sleep", "sleeping"),
        "weekend" to listOf("выходные", "суббота", "воскресенье", "weekend", "saturday", "sunday"),
        "meeting" to listOf("встреча", "совещание", "созвон", "meeting"),
        "exam" to listOf("экзамен", "сессия", "зачёт", "зачет", "exam", "exams"),
        "wedding" to listOf("свадьба", "годовщина", "wedding", "anniversary"),
        "food" to listOf("еда", "блюдо", "ужин", "обед", "завтрак", "food", "dish", "dinner", "lunch", "breakfast"),
        "language" to listOf("язык", "английский", "немецкий", "language", "english", "german"),
        "code" to listOf("программирование", "программист", "код", "разработчик", "programming", "programmer", "code", "developer"),
        "city" to listOf("город", "city", "town"),
        "plan" to listOf("план", "планы", "планирую", "собираюсь", "plan", "plans", "planning"),
    )

    /** stem → concept tokens. */
    private val byStem: Map<String, Set<String>> = HashMap<String, MutableSet<String>>().apply {
        for ((concept, words) in groups) for (w in words) for (part in w.split(' ')) {
            if (part.length < 3) continue
            getOrPut(Stemmer.stem(part)) { HashSet() } += "#$concept"
        }
    }

    fun of(stem: String): Set<String> = byStem[stem].orEmpty()
}
