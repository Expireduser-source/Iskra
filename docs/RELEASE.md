# Release-сборка

| | debug | release |
|---|---|---|
| Когда собирается | каждый push (в main — публикуется `build-N`) | тег `v*`, например `v0.4.0` |
| Пакет | `ru.iskra.app.debug` («Искра debug») | `ru.iskra.app` («Искра») |
| Подпись | `app/debug.keystore` из репозитория (не секрет) | ключ владельца из GitHub Secrets |

Обе версии стоят на телефоне рядом. Данные переносятся через «Память → Сохранить копию / Восстановить» (ключи API вводятся заново).

## Ключ
- RSA 4096, PKCS12, алиас `iskra`, срок 30 лет, владелец `CN=Iskra, O=Iskra` (без личных данных).
- SHA-256 сертификата: `AE:2D:A7:A1:21:22:32:D3:99:52:D6:B5:FA:2E:CF:88:E3:4B:6B:AB:17:A0:1B:83:C1:CB:BA:D9:C6:38:10:CC`.
  Этот отпечаток публичный: по нему проверяется, что APK подписан именно этим ключом.
- Файл `.jks` и пароль в репозиторий **не попадают** (`*.jks` в `.gitignore`).
- **Потеря ключа = нельзя выпустить обновление поверх.** Тогда придётся удалить release и поставить заново, данные переносятся через копию JSON.

## GitHub Secrets
Settings → Secrets and variables → Actions → New repository secret:

| Имя | Значение |
|---|---|
| `ISKRA_KEYSTORE_BASE64` | файл `.jks` в base64, одной строкой |
| `ISKRA_KEYSTORE_PASSWORD` | пароль хранилища |
| `ISKRA_KEY_ALIAS` | `iskra` |
| `ISKRA_KEY_PASSWORD` | пароль ключа (в PKCS12 тот же, что у хранилища) |

## Выпуск версии
1. Сначала в `app/build.gradle.kts` поднять `versionName` (и `versionCode`).
2. Поставить тег: `git tag v0.4.0 && git push origin v0.4.0`. Тег должен совпадать с `versionName`, иначе CI остановится.
3. CI запускает тесты и собирает `assembleRelease` с ключом. Затем `apksigner` проверяет, что подпись не debug.
4. Публикуется релиз `v0.4.0` с `Iskra-v0.4.0-release.apk` и `.sha256`.

## Свой ключ на своём компьютере (если нужен новый)
```bash
keytool -genkeypair -keystore iskra-release.jks -storetype PKCS12 -alias iskra \
  -keyalg RSA -keysize 4096 -validity 10958 -dname "CN=Iskra, O=Iskra"
base64 -w0 iskra-release.jks > iskra-release.b64   # macOS: base64 -i iskra-release.jks
```
