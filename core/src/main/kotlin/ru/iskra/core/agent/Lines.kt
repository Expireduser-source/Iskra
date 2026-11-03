package ru.iskra.core.agent

import kotlin.random.Random

/** Pre-written persona lines for offline mode and instant feedback on local commands. */
object Lines {
    private fun pick(rnd: Random, vararg options: String) = options[rnd.nextInt(options.size)]

    fun actionDone(rnd: Random, what: String) = pick(rnd,
        "Готово: $what. Мрр.", "Сделала — $what.", "Есть! $what.", "$what — пожалуйста, хозяин моего телефона.")

    fun actionFailed(rnd: Random, why: String) = pick(rnd,
        "Фыр… не вышло: $why", "Не получилось: $why", "Хм, споткнулась: $why")

    fun remembered(rnd: Random, text: String) = pick(rnd,
        "Запомнила: «$text». Удалить можно в разделе «Память».",
        "Записала себе: «$text».",
        "Мрр, «$text» — теперь это знаю и я.")

    fun offline(rnd: Random) = pick(rnd,
        "Я сейчас без облачного мозга, поэтому понимаю только простые команды: будильник, таймер, фонарик, «открой …», «запомни: …». Подключи модель в настройках — и поболтаем.",
        "Мяу? Без подключённой модели я умею только команды телефона и память. Загляни в настройки.",
    )

    fun petted(rnd: Random) = pick(rnd, "Мрр…", "Ещё. В смысле… ну ладно, можно.", "Хорошо гладишь.", "*довольно щурится*")
    fun dragged(rnd: Random) = pick(rnd, "Эй, аккуратнее!", "Куда ты меня несёшь?", "Фыр!")
    fun flung(rnd: Random) = pick(rnd, "Ай! Я тебе не мячик!", "Фыр! Так нельзя с кошками!", "Вот обижусь…")
}
