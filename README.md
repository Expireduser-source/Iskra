# Искра — ИИ-компаньон для Android

Анимированная чиби-кошкодевочка поверх экрана: разговаривает (текст/голос), выполняет разрешённые
действия на телефоне, помнит важное о владельце, имеет устойчивый характер и настроение.

Первое целевое устройство — Xiaomi Redmi Note 14 (HyperOS, Android 14/15). Работает на Android 8.0+.

* Аудит прототипа ChatGPT, архитектура и план: [`docs/AUDIT.md`](docs/AUDIT.md)
* Ручной чек-лист проверки на телефоне: [`docs/TESTING.md`](docs/TESTING.md)

## Структура

```
iskra/
├── core/                      чистый Kotlin/JVM, без Android — тестируется на любом ПК
│   └── src/main/kotlin/ru/iskra/core/
│       ├── llm/               LlmProvider + OpenAI-совместимый и Anthropic клиенты
│       ├── persona/           ядро личности, настроение (MoodEngine), адаптация стиля
│       ├── memory/            долговременная память, поиск, фильтр секретов
│       ├── tools/             контракты инструментов, SafetyPolicy, офлайн-парсер команд, AppMatcher
│       ├── agent/             CompanionAgent (цикл решения), PromptComposer, ReplyParser
│       └── avatar/            AvatarBrain — поведение/анимация персонажа (физика, моргание, сон…)
├── app/                       Android-приложение
│   └── src/main/java/ru/iskra/app/
│       ├── IskraApp.kt        сборка зависимостей
│       ├── overlay/           OverlayService (поверх приложений), AvatarView, CatgirlRenderer
│       ├── ui/                Compose: чат, память, настройки, разрешения, быстрый чат
│       ├── tools/             реализации инструментов через Intent/CameraManager/AudioManager
│       ├── system/            AccessibilityService (только глобальные действия), Xiaomi-хелперы
│       ├── voice/             TTS и распознавание речи
│       └── data/              SQLite (память, диалог), настройки, KeyVault (Android Keystore)
└── .github/workflows/android.yml   сборка APK в GitHub Actions
```

## Сборка APK

Нужны JDK 17 и Android SDK (платформа 35). Три способа — от самого простого:

### 1. GitHub Actions (ничего не ставить локально)
1. Создай **приватный** репозиторий на GitHub и загрузи туда эту папку.
2. Вкладка **Actions** → workflow **Build APK** запустится сам (или нажми *Run workflow*).
3. Через ~5–8 минут скачай артефакт `iskra-debug-apk` → внутри `app-debug.apk`.

### 2. Android Studio
Открой папку `iskra` (File → Open), дождись синхронизации Gradle, подключи телефон с включённой
отладкой по USB и нажми **Run**. Либо *Build → Build APK(s)*.

### 3. Командная строка
```bash
export ANDROID_HOME=$HOME/Android/Sdk      # путь к SDK
./gradlew :core:test :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Только ядро (без Android SDK): `ISKRA_CORE_ONLY=1 ./gradlew :core:test`.

Release-сборка (`ru.iskra.app`, ставится рядом с debug `ru.iskra.app.debug`) собирается в CI по тегу `v*`
и подписывается ключом владельца из GitHub Secrets — см. [docs/RELEASE.md](docs/RELEASE.md). Без ключа release
остаётся неподписанным, debug-ключом он не подписывается никогда.

## Первый запуск на Redmi Note 14
1. Установи APK (разреши установку из этого источника).
2. Вкладка **Разрешения**: «Поверх других приложений» → включить → переключатель «Показать Искру на экране».
3. Для «Домой/Назад/шторки»: *О приложении → ⋮ → Разрешить ограниченные настройки*, затем
   *Специальные возможности → Искра · управление*.
4. HyperOS: Автозапуск — вкл; Контроль активности — «Нет ограничений»; закрепить карточку в недавних.
5. Вкладка **Настройки**: выбери провайдера модели и вставь свой ключ API. Без ключа работают
   команды телефона и память («запомни: …»), но не свободная беседа.
6. **Голос** (по желанию): Yandex SpeechKit / ElevenLabs / OpenAI / свой сервер — отдельный ключ.
   В сервис уходит только текст ответа Искры.
7. **Слух** (по желанию): скачать офлайн-модель Vosk (45 МБ) → «Слушать постоянно». Распознавание на
   телефоне, звук никуда не уходит. Работает, пока Искра на экране; после включения открой приложение
   хотя бы раз — Android даёт доступ к микрофону в фоне только так.

## Лицензии
Код проекта — твой. Зависимости: Kotlin, kotlinx (Apache-2.0), OkHttp (Apache-2.0), AndroidX/Compose
(Apache-2.0), Vosk + модель small-ru (Apache-2.0), JNA (Apache-2.0/LGPL, двойная лицензия). Графика персонажа рисуется кодом — сторонних ассетов нет.
