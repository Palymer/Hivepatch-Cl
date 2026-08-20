# HivePatch — Android

Репозиторий клиента: [Palymer/Hivepatch-Cl](https://github.com/Palymer/Hivepatch-Cl).

Мини-клиент: кнопка вкл/выкл, текущий узел / пинг / трафик / доступность, выбор самого быстрого сервера, настройки с обновлением по ссылке, лог на отдельном экране. Группа инбаундов (Mobile `:443` xHTTP, Throne `:2054` xHTTP, Stable `:31095` Vision), url-test через ядро **Xray**.

## Сборка на GitHub

Каждый push в `main` (и каждый PR) собирает debug и release APK в [Actions](https://github.com/Palymer/Hivepatch-Cl/actions).

- Артефакт `hivepatch-apk` у прогона
- На `main` обновляется prerelease [nightly](https://github.com/Palymer/Hivepatch-Cl/releases/tag/nightly)
- Тег `v*` (например `v1.0.1`) публикует обычный Release

Debug APK подписан debug-ключом Android и ставится на устройство. Release без секретов — unsigned. Чтобы подписывать release, задайте в Settings → Secrets:

- `HIVEPATCH_RELEASE_KEYSTORE_BASE64`
- `HIVEPATCH_RELEASE_STORE_PASSWORD`
- `HIVEPATCH_RELEASE_KEY_ALIAS`
- `HIVEPATCH_RELEASE_KEY_PASSWORD`

## Локальная сборка

Нужны Android Studio (SDK 35) и JDK 17.

```bash
./gradlew :app:assembleRelease
```

APK: `app/build/outputs/apk/release/`. Без keystore файл будет `app-release-unsigned.apk`.

Ядро: [AndroidLibXrayLite v26.7.28](https://github.com/2dust/AndroidLibXrayLite/releases/tag/v26.7.28) (`libv2ray.aar`, качается при первой сборке).

## Первый запуск

1. Панель → Клиенты → **URL Android HivePatch** (`/sub/{token}?fmt=hive`).
2. **Настройки** → вставить URL или токен → **Обновить**.
3. Большая кнопка — вкл/выкл. Под ней: текущий узел, пинг, трафик, доступно/недоступно узлов.
4. **Выбрать самый быстрый сервер** — url-test всех узлов, переключение на минимальный пинг.
5. **Лог** — отдельный экран. Нажатие на текущий узел — ручной выбор.
6. **Настройки → приложения** — все / не проксировать выбранные / только выбранные.

Токен и UUID в APK не зашиваются.

## Почему не NekoBox / Clash

На LTE нужны Xray xHTTP (`type=xhttp`) и порт 443. Stock sing-box это не умеет: ping зелёный, сайты мёртвые. Это приложение использует то же ядро, что v2rayNG / Throne.
