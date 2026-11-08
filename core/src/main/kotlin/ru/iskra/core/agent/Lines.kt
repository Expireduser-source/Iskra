package ru.iskra.core.agent

import ru.iskra.core.i18n.tr
import kotlin.random.Random

/** Pre-written persona lines for offline mode and instant feedback on local commands. */
object Lines {
    private fun pick(rnd: Random, vararg options: String) = options[rnd.nextInt(options.size)]

    /**
     * What she did, in a line: «Есть! Таймер на 2 мин.». A sentence begins with a capital («Есть! таймер…» was seen in the
     * 0.16.0 screenshots), and no «хозяин» — the owner may well be a woman.
     */
    fun actionDone(rnd: Random, what: String): String {
        val w = what.trim().trimEnd('.')
        val cap = w.replaceFirstChar { it.uppercase() }
        return pick(rnd, tr("Готово: $w. Мрр.", "Done: $w. Mrr."), tr("Сделала — $w.", "Did it — $w."), tr("Есть! $cap.", "Got it! $cap."),
            tr("$cap — обращайся!", "$cap — any time!"))
    }

    /**
     * A timer she set, when the model said nothing after it (Gemma 4 did so about half the time in the ARM evaluations of
     * 0.16.3, with the app's own words for the result): «Засекла 10 минут — таймер прозвенит, когда пора.» It rings by
     * itself, in the clock app, so that's what she promises.
     */
    fun timerSet(rnd: Random, seconds: Int): String {
        val time = if (seconds % 60 == 0) ru.iskra.core.i18n.Plural.count((seconds / 60).toLong(), "минуту", "минуты", "минут", "minute", "minutes")
            else ru.iskra.core.i18n.Plural.count(seconds.toLong(), "секунду", "секунды", "секунд", "second", "seconds")
        return pick(rnd, tr("Засекла $time — таймер прозвенит, когда пора. Мрр.", "Timer's on for $time — it'll ring when it's time. Mrr."),
            tr("Поставила таймер на $time — он прозвенит, когда будет пора.", "I set a timer for $time — it'll ring when it's time."))
    }

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
