# Как рассказать миру об Искре

Всё, что можно было сделать из кода, сделано: сайт, README на двух языках, политика конфиденциальности, тексты и картинки для магазинов (`fastlane/metadata/android`), постоянная ссылка на APK, QR-код, «Рассказать другу» в приложении, проверка обновлений.
Ниже — то, что делает владелец: это его аккаунты и его имя. Порядок — от самого быстрого к самому долгому.

## 1. GitHub — 3 минуты (сильнее всего влияет на поиск)
1. **Settings → General**, справа от «About» нажми ⚙ и вставь:
   - Description: `Iskra — a cat-girl AI companion for Android that lives on your screen: timers, reminders, notes, weather, voice and chat. English & Russian, offline commands, free brain, private by design.`
   - Website: `https://expireduser-source.github.io/Iskra/`
   - Topics: `android` `ai-companion` `virtual-assistant` `kotlin` `jetpack-compose` `llm` `offline-first` `privacy` `vosk` `speech-recognition` `anime` `overlay` `openrouter` `russian`
2. **Settings → Pages**: Source — *Deploy from a branch*, Branch — `main`, папка `/docs` → Save. Через минуту сайт откроется по адресу выше.
3. **Settings → General → Social preview**: загрузи `fastlane/metadata/android/en-US/images/featureGraphic.png` — эта картинка будет показываться в ссылках в Telegram, ВК и других.
4. Можно удалить старые пустые релизы `v0.9.1`, `v0.9.2` и лишние `build-N`, чтобы страница релизов выглядела аккуратно.

## 2. Каталоги приложений
| Каталог | Что нужно | Стоимость | Срок |
|---|---|---|---|
| **Obtainium** | ничего — ссылка уже в README | 0 | сразу |
| **IzzyOnDroid** (F-Droid-совместимый) | заявка-issue на gitlab.com/IzzyOnDroid/repo: название, ссылка на репозиторий, лицензия GPL-3.0, APK в релизах GitHub. Пометки: *NonFreeNet* (облачные модели по желанию), *NonFreeAssets* (рисунки персонажа — все права у владельца) | 0 | дни |
| **F-Droid** | заявка на gitlab.com/fdroid/rfp; F-Droid собирает сам из исходников и подписывает своим ключом (обновления с GitHub поверх не встанут) | 0 | недели |
| **Google Play** | аккаунт разработчика $25 и проверка личности; для новых личных аккаунтов — закрытое тестирование: 12 тестировщиков 14 дней подряд. Готово: targetSdk 36, тексты и картинки, политика `PRIVACY.md`. Ответы для форм — ниже. Регистрация и оплата из России могут быть недоступны | $25 | 3–4 недели |
| **RuStore** | по сообщениям СМИ, физлица и самозанятые сейчас не могут загружать приложения — нужен ИП или юрлицо. Уточни в консоли RuStore | 0 | — |
| Huawei AppGallery, Xiaomi GetApps, Samsung Galaxy Store | аккаунты разработчика, те же тексты и картинки | 0 | недели |

### Ответы для форм Google Play
- **Data safety:** приложение само ничего не собирает. Данные уходят только в сервисы, которые выбрал пользователь (модель ИИ, облачный голос, погода). Шифрование при передаче — да (только HTTPS). Удаление данных — да («Стереть всё» в приложении, удаление приложения).
- **Accessibility API:** служба «Искра · управление» выполняет только системные действия по просьбе пользователя («Домой», «Назад», «Недавние», шторка, быстрые настройки, блокировка, скриншот), не читает экран. Включается вручную, в приложении есть объяснение до включения.
- **Foreground service (specialUse):** «Interactive on-screen companion character that the user explicitly enabled; it stays visible over other apps until the user turns it off.» Тип microphone — только для «Слуха», который пользователь включает сам.
- **Exact alarms:** напоминания, которые пользователь поставил на конкретное время.
- **Целевая аудитория:** 13+ (облачные модели ИИ требуют 13+); без рекламы.

## 3. Готовые тексты
Правило: честно говори, что ты автор, не покупай и не выдумывай отзывы. К постам прикладывай `featureGraphic.png` и 2–3 скриншота из `fastlane/metadata/android/*/images/phoneScreenshots/`.

### 4PDA (раздел «Android — Программы»)
> **Искра — котодевочка-компаньон на экране**
> Анимированный персонаж живёт поверх приложений: гуляет, спит, выглядывает из-за края. Офлайн понимает команды — будильники, таймеры, напоминания («напомни завтра в 9 позвонить маме»), заметки, погоду, фонарик, приложения. По желанию — свободная беседа: бесплатный «мозг» одной кнопкой через OpenRouter или свой ключ (DeepSeek, OpenAI, Claude). Голос: Yandex SpeechKit, ElevenLabs, OpenAI или системный; отзывается на имя, распознавание полностью на телефоне.
> «Поделиться → Искре» и «Спросить Искру» в меню выделенного текста; плитка в шторке; ярлыки. Подсказки для HyperOS, One UI, EMUI/MagicOS, ColorOS, Funtouch, HiOS. Русский и английский. Android 8–16.
> Без рекламы, трекеров и аккаунтов, данные на телефоне. Открытый код (GPL-3.0).
> Скачать: https://github.com/Expireduser-source/Iskra/releases/latest/download/Iskra.apk · Сайт: https://expireduser-source.github.io/Iskra/
> Я автор — буду рад отзывам и багам (в приложении: Настройки → Диагностика → Отправить отчёт).

### Telegram / ВК (коротко)
> 🐾 Сделал Искру — котодевочку-компаньона для Android. Живёт на экране, ставит таймеры и напоминания, ведёт заметки, рассказывает погоду, а если подключить бесплатный «мозг» — болтает. Без рекламы, данные на телефоне, код открыт.
> 📥 https://expireduser-source.github.io/Iskra/

### Хабр (план статьи)
«Котодевочка поверх всех приложений: как я сделал ИИ-компаньона для Android без своего сервера»
1. Идея и ограничения: приватность, никакого бэкенда, работает без ключа.
2. Персонаж поверх приложений: оверлей, 120 Гц только в движении, борьба с выгрузкой у разных производителей.
3. Офлайн-команды на двух языках без ML: разбор времени для напоминаний.
4. Слух на телефоне: Vosk, грамматика из имени, экономный режим.
5. Бесплатный «мозг»: PKCE-вход OpenRouter, выбор бесплатной модели, лимиты расходов.
6. Как CI запускает приложение на эмуляторах Android 8/11/16 и делает скриншоты.

### Reddit r/androidapps (English; read the subreddit's self-promotion rules first)
> **[Dev] Iskra — a free, open-source cat-girl companion that lives on your screen**
> She walks around on top of your apps, naps and peeks from the edges. Offline she handles alarms, timers, reminders ("remind me tomorrow at 9 to call mom"), notes, weather, the flashlight and apps. Want real chat? Connect a free brain in one tap (OpenRouter sign-in, nothing to copy) or bring your own key (DeepSeek, OpenAI, Claude). Share any text to her or select it → "Ask Iskra" to explain, summarize or translate. Wake word "Iskra"/"Spark" is recognized fully on the device.
> No ads, no trackers, no accounts; data stays on the phone. GPL-3.0. Android 8–16.
> APK: https://github.com/Expireduser-source/Iskra/releases/latest/download/Iskra.apk — site: https://expireduser-source.github.io/Iskra/
> I'm the developer — feedback and bug reports are very welcome.

### Короткое видео (TikTok / Shorts / Reels, 15–20 с)
1. Рабочий стол, Искра гуляет по краю экрана (2 с).
2. «Искра, поставь таймер на 5 минут» → таймер (4 с).
3. Выделить текст в браузере → «Спросить Искру» → «Кратко» (6 с).
4. Её реакция на зарядку / тап (3 с). Надпись: «Бесплатно, без рекламы. Ссылка в профиле».

## 4. Что поможет дальше
- Отвечать на отзывы и issues в первые дни — это сильнее всего влияет на то, советуют ли приложение.
- Каждый выпуск — коротко «что нового» в канале и на 4PDA.
- Следующие языки — по просьбам пользователей: все тексты собраны парами `tr("рус", "eng")`, так что новый язык — это перевод к каждой паре, без поиска строк по коду.
