# Iskra — Privacy Policy · Политика конфиденциальности

*Effective / Действует с: 5 November 2026 · 5 ноября 2026*

[English](#english) · [Русский](#русский)

## English

Iskra is a free, open-source Android app (GNU GPL v3). There is no company behind it, no server of its own,
no account and no analytics. This policy describes exactly what the app does with data; the source code at
<https://github.com/Expireduser-source/Iskra> is the final proof.

### What stays on your phone
Everything Iskra knows is stored only in the app's private storage on your phone:
memory entries, notes, reminders, the chat history, her mood, your settings, the voice cache, the model of the
«brain on the phone» if you downloaded one, with its conversation cache (what the model has already read, so it doesn't read the dialogue again after a pause; deleted together with the chat history or a forgotten memory), and the diagnostics journal (the journal contains events and timings, never API keys or chat text). API keys are encrypted with the
Android Keystore. Android backup of the app is turned off, so this data is not uploaded to cloud backups.

You can delete any entry in «Memory», or everything at once with «Erase everything». Uninstalling the app deletes
all of it. Backup files are created only when you ask (a file you choose) and never contain API keys or the chat.

### What leaves your phone — only when you use the feature
| Feature | Goes to | What is sent |
|---|---|---|
| Conversation with a cloud model («brain») | the service you choose: OpenRouter, DeepSeek, OpenAI, Anthropic or your own server | your message, recent turns of the chat, memory notes relevant to it, her mood and the time; the API key you entered |
| Free brain through OpenRouter | openrouter.ai | sign-in happens on their site in your browser; the app receives your personal key. When the chosen free model is busy, the same message may go to another free model there. **Free models may store conversations and use them for training** — don't share passwords or private details with them |
| Brain on the phone | huggingface.co (download only) | a file download (0.7–2.5 GB). After that the model runs on the phone itself: **nothing you say to it leaves the phone** |
| Fact lookups by the cloud model | wikipedia.org | the few search words the model chose (e.g. “Eiffel Tower height”); never the chat or memory |
| Exchange rates | open.er-api.com (ExchangeRate-API) | the currency code (e.g. USD); nothing about you |
| Cloud voice | Yandex SpeechKit, ElevenLabs, OpenAI or your server | only the text of Iskra's reply |
| Weather | open-meteo.com | the city name you typed (to find it) and the city's coordinates; never your device location |
| Speech model download | alphacephei.com | a file download (≈ 40–45 MB) |
| Update check (copies from GitHub/the site only) | api.github.com | one request a day asking for the latest version; nothing about you |
| Chat microphone with system recognition | your phone's speech service (e.g. Google) | your voice, handled by that service; switch on «Chat microphone — on the phone only» to keep audio on the phone |
| Diagnostics report | wherever **you** send the file | the journal (keys masked, no chat text) |

Each service has its own privacy policy and age requirements. Iskra never sells or shares data with anyone else,
shows no ads and contains no trackers.

### Permissions
- **Display over other apps** — so Iskra can stay on your screen. Optional.
- **Microphone** — only when you tap 🎤, or when you turn on «Hearing» (recognized on the phone, nothing recorded or sent).
- **Notifications** — reminders and the on-screen indicator.
- **Alarms & reminders**, **run at startup** — reminders arrive on time and after a reboot.
- **Accessibility service «Iskra · control»** — optional; used only for the system actions you ask for (Home, Back,
  Recents, notifications, quick settings, lock screen, screenshot). It does not read the screen or tap anything in other apps.
- **Internet** — only for the features above.

### Children
Iskra is a general-audience app and collects no personal data itself. Cloud services you connect have their own
age limits.

### Changes and contact
Changes to this policy are published in this file in the repository. Questions: <https://github.com/Expireduser-source/Iskra/issues>.

## Русский

Искра — бесплатное Android-приложение с открытым кодом (GNU GPL v3). За ним не стоит компания, у него нет своего
сервера, аккаунтов и аналитики. Здесь написано, что именно приложение делает с данными; окончательное доказательство —
исходный код: <https://github.com/Expireduser-source/Iskra>.

### Что остаётся на телефоне
Всё, что знает Искра, хранится только в закрытом хранилище приложения на телефоне: память, заметки, напоминания,
переписка, её настроение, настройки, кэш голоса, модель «мозга на телефоне», если ты её скачал, с её кэшем разговора (то, что модель уже прочитала, чтобы после паузы не читать переписку заново; стирается вместе с перепиской или забытой записью памяти), и журнал диагностики (в журнале события и время, но никогда — ключи API
и текст переписки). Ключи API зашифрованы Android Keystore. Резервное копирование приложения в облако Android выключено.

Любую запись можно удалить в «Памяти», всё сразу — кнопкой «Стереть всё». При удалении приложения удаляется всё.
Файлы резервных копий создаются только по твоей просьбе (в выбранный тобой файл) и никогда не содержат ключей и переписки.

### Что уходит с телефона — только когда ты пользуешься функцией
| Функция | Куда | Что отправляется |
|---|---|---|
| Разговор с облачной моделью («мозг») | выбранный тобой сервис: OpenRouter, DeepSeek, OpenAI, Anthropic или свой сервер | твоё сообщение, последние реплики переписки, подходящие заметки памяти, её настроение и время; введённый тобой ключ API |
| Бесплатный «мозг» через OpenRouter | openrouter.ai | вход происходит на их сайте в браузере; приложение получает твой личный ключ. Если выбранная бесплатная модель занята, то же сообщение может уйти другой бесплатной модели там же. **Бесплатные модели могут сохранять переписку и учиться на ней** — не пиши им пароли и личное |
| «Мозг на телефоне» | huggingface.co (только скачивание) | скачивание файла (0,7–2,5 ГБ). Дальше модель работает на самом телефоне: **ничего из сказанного ей не уходит с телефона** |
| Поиск фактов облачной моделью | wikipedia.org | несколько слов запроса, которые выбрала модель (например, «высота Эйфелевой башни»); переписка и память — никогда |
| Курсы валют | open.er-api.com (ExchangeRate-API) | код валюты (например, USD); ничего о тебе |
| Облачный голос | Yandex SpeechKit, ElevenLabs, OpenAI или твой сервер | только текст ответа Искры |
| Погода | open-meteo.com | название города, которое ты ввёл (чтобы его найти), и координаты города; местоположение телефона — никогда |
| Скачивание модели распознавания | alphacephei.com | скачивание файла (≈ 40–45 МБ) |
| Проверка обновлений (только копии с GitHub/сайта) | api.github.com | раз в день вопрос «какая версия последняя»; ничего о тебе |
| Микрофон в чате с системным распознаванием | служба распознавания телефона (например, Google) | твой голос, его обрабатывает эта служба; включи «Микрофон в чате — тоже только на телефоне», чтобы звук оставался на телефоне |
| Отчёт диагностики | куда **ты сам** отправишь файл | журнал (ключи замаскированы, текста переписки нет) |

У каждого сервиса своя политика конфиденциальности и возрастные ограничения. Искра никому не продаёт и не передаёт
данные, не показывает рекламу и не содержит трекеров.

### Разрешения
- **Поверх других приложений** — чтобы Искра жила на экране. Необязательно.
- **Микрофон** — только когда ты нажимаешь 🎤 или включаешь «Слух» (распознавание на телефоне, ничего не записывается и не отправляется).
- **Уведомления** — напоминания и значок «Искра на экране».
- **Будильники и напоминания**, **запуск после включения** — чтобы напоминания приходили вовремя и после перезагрузки.
- **Служба спецвозможностей «Искра · управление»** — необязательно; только для системных действий, о которых ты попросил
  («Домой», «Назад», «Недавние», шторка, быстрые настройки, блокировка, скриншот). Она не читает экран и ничего не нажимает в других приложениях.
- **Интернет** — только для функций выше.

### Дети
Искра — приложение для широкой аудитории и сама не собирает персональных данных. У подключаемых облачных сервисов свои возрастные ограничения.

### Изменения и связь
Изменения политики публикуются в этом файле в репозитории. Вопросы: <https://github.com/Expireduser-source/Iskra/issues>.
