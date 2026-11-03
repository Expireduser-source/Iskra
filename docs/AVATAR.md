# Внешний вид персонажа

Три вида, переключаются в Настройки → Движение → «Вид»:

| Вид | Что это | Код |
|---|---|---|
| Живой риг (по умолчанию) | 10 слоёв-картинок, двигаются кодом; лицо (глаза, брови, рот, румянец) рисуется векторно | `core/…/avatar/Rig.kt`, `RigAnimator.kt`; `app/…/overlay/RigRenderer.kt` |
| Спрайты | 15 готовых кадров 512×512 | `core/…/avatar/SpriteAnimator.kt`, `SpriteAtlas.kt`; `app/…/overlay/SpriteRenderer.kt` |
| Старый вид | рисунок примитивами Canvas | `app/…/overlay/CatgirlRenderer.kt` |

Если картинки вида не загрузились, Искра берёт вид попроще (риг → спрайты → старый) и пишет об этом в журнал.

## Откуда картинки
Все картинки предоставил владелец проекта; права на них — у него.
- **Спрайты** — архив `iskra_15_sprites_512.zip` → `app/src/main/res/drawable-nodpi/iskra_*.webp` (WebP без потерь).
- **Риг** — прототип «iskra-live» (Астра) → из архива взяты только `model/` (`app/src/main/assets/rig/`) и логика `web/rig.js` (перенесена на Kotlin). WebView, `server.mjs` и `android/` из архива не используются.

## Обновить спрайты
```bash
python3 -I tools/sprites/convert.py <папка с iskra_<имя>_512.png>
```
Скрипт конвертирует в WebP, проверяет, что пиксели не изменились, меряет ноги, центр головы и расстояние между кончиками ушей и пересоздаёт `SpriteAtlas.kt`. Затем `ISKRA_CORE_ONLY=1 ./gradlew :core:test`.

## Как сопоставлены состояния
| Состояние | Риг | Спрайты |
|---|---|---|
| стоит, эмоции | риг + выражение | stand / joy / sad / angry / surprise / shy / thoughtful / sleepy / sly |
| идёт / бежит | риг, шаги на месте | walk_01 ↔ walk_02, 6 / 10 к/с |
| сидит | спрайт sit | sit |
| спит | спрайт sleep | sleep |
| ждёт ответа модели | спрайт phone | phone |
| машет (появилась, тап) | жест wave | wave |
| услышала имя | кивок nod | — |
| надулась (бросили) | качает головой shake | angry |
| прыжок, падение, несут, висит, лезет, выглядывает | риг, запись «Нет своей позы» в журнал | ближайший спрайт + запись в журнал |

Эмоции → выражения рига: HAPPY/EXCITED → joy, SAD → sad, SURPRISED → surprise, POUTY → angry, SHY → shy, THINKING → thoughtful, SLEEPY → sleepy, CURIOUS → sly.

## Проверка рисования без телефона
`RigTest` рисует риг через Java2D в `core/build/rig-preview/*.png` (все выражения, жесты, ходьба) тем же кодом, что и Android Canvas.
