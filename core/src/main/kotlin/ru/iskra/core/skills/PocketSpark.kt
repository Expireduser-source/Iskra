package ru.iskra.core.skills

import ru.iskra.core.i18n.tr
import kotlin.random.Random

/** Tiny, explicitly requested offline invitations to notice or imagine something new. */
class PocketSpark(random: Random = Random.Default) {
    private val phrases = PhraseBank(random)

    fun next(): String = phrases.pick("POCKET_SPARK", invitations)

    companion object {
        private val command = Regex("^(?:(?:искра|искорка|iskra|spark)[,\\s]+)?(?:удиви меня|искорка дня|surprise me|spark of the day)[.!?…]*$", RegexOption.IGNORE_CASE)

        fun matches(text: String): Boolean = command.matches(text.trim())

        internal val invitations: List<String> get() = tr(RU, EN)

        private val RU = listOf(
            "Найди рядом предмет, которому подошло бы имя космического корабля. Как его назовём?",
            "Выбери обычную вещь рядом. Если это музейный экспонат из будущего, что на табличке?",
            "Маленькая охота: найди три разных оттенка одного цвета. Я ставлю на зелёный.",
            "Прислушайся на десять секунд. Какой самый тихий звук удалось заметить?",
            "Посмотри на ближайшую тень. На что она похожа, если дать воображению порулить?",
            "Придумай название сегодняшней главы своей жизни. Можно совершенно несерьёзное.",
            "Если бы у твоей кружки был выходной, куда бы она отправилась? Моя — к морю чая.",
            "Найди что-нибудь крошечное, чего раньше не замечал. Иногда мир прячется в деталях.",
            "Выбери два предмета рядом и придумай, о чём они спорят. Я буду на стороне лампы.",
            "Как звучал бы дождь, если бы капли были из дерева? Придумай для него одно слово.",
            "Придумай созвездие из трёх вещей вокруг. Какую легенду про него рассказывают?",
            "Назови обычную вещь так, будто видишь её впервые. Ложка — карманное весло для супа?",
        )

        private val EN = listOf(
            "Find something nearby that deserves a spaceship's name. What shall we call it?",
            "Pick an ordinary thing nearby. If it were a museum piece from the future, what would its label say?",
            "A tiny hunt: find three different shades of one color. I bet on green.",
            "Listen for ten seconds. What's the quietest sound you could notice?",
            "Look at the nearest shadow. What does it look like if you let your imagination drive?",
            "Name today's chapter of your life. It can be totally unserious.",
            "If your mug had a day off, where would it go? Mine — to the sea of tea.",
            "Find something tiny you've never noticed before. Sometimes the world hides in details.",
            "Pick two things nearby and invent what they're arguing about. I'm on the lamp's side.",
            "What would rain sound like if the drops were made of wood? Invent one word for it.",
            "Make a constellation out of three things around you. What legend do they tell about it?",
            "Name an ordinary thing as if you were seeing it for the first time. A spoon — a pocket paddle for soup?",
        )
    }
}
