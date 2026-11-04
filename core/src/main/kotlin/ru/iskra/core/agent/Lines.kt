package ru.iskra.core.agent

import ru.iskra.core.i18n.tr
import kotlin.random.Random

/** Pre-written persona lines for offline mode and instant feedback on local commands. */
object Lines {
    private fun pick(rnd: Random, vararg options: String) = options[rnd.nextInt(options.size)]

    fun actionDone(rnd: Random, what: String) = pick(rnd,
        tr("Готово: $what. Мрр.", "Done: $what. Mrr."), tr("Сделала — $what.", "Did it — $what."), tr("Есть! $what.", "Got it! $what."), tr("$what — пожалуйста, хозяин моего телефона.", "$what — you're welcome, keeper of my phone."))

    fun actionFailed(rnd: Random, why: String) = pick(rnd,
        tr("Фыр… не вышло: $why", "Hmph… didn't work: $why"), tr("Не получилось: $why", "Couldn't do it: $why"), tr("Хм, споткнулась: $why", "Hm, I tripped: $why"))

    fun remembered(rnd: Random, text: String) = pick(rnd,
        tr("Запомнила: «$text». Удалить можно в разделе «Память».", "Remembered: “$text”. You can delete it in “Memory”."),
        tr("Записала себе: «$text».", "Noted for myself: “$text”."),
        tr("Мрр, «$text» — теперь это знаю и я.", "Mrr, “$text” — now I know it too."))

    fun offline(rnd: Random) = pick(rnd,
        tr("Я сейчас без облачного мозга, поэтому понимаю только простые команды: будильник, таймер, фонарик, «открой …», «запомни: …». Подключи модель в настройках — и поболтаем.", "Right now I have no cloud brain, so I only understand simple commands: alarm, timer, flashlight, “open …”, “remember: …”. Connect a model in Settings — and we'll chat."),
        tr("Мяу? Без подключённой модели я умею только команды телефона и память. Загляни в настройки.", "Meow? Without a connected model I can only do phone commands and memory. Take a look at Settings."),
    )

    fun petted(rnd: Random) = pick(rnd, tr("Мрр…", "Mrr…"), tr("Ещё. В смысле… ну ладно, можно.", "More. I mean… fine, you may."), tr("Хорошо гладишь.", "You're good at petting."), tr("*довольно щурится*", "*squints contentedly*"))
    fun dragged(rnd: Random) = pick(rnd, tr("Эй, аккуратнее!", "Hey, careful!"), tr("Куда ты меня несёшь?", "Where are you taking me?"), tr("Фыр!", "Hmph!"))
    fun flung(rnd: Random) = pick(rnd, tr("Ай! Я тебе не мячик!", "Ow! I'm not a ball!"), tr("Фыр! Так нельзя с кошками!", "Hmph! That's no way to treat cats!"), tr("Вот обижусь…", "I'm going to sulk…"))
}
