# XiaoHyperCleaner

[![License: CC BY-NC 4.0](https://img.shields.io/badge/License-CC%20BY--NC%204.0-yellow.svg)](https://creativecommons.org/licenses/by-nc/4.0/)
[![Beta](https://img.shields.io/github/v/release/elthebestdevelopman-cyber/XiaoHyperCleaner?include_prereleases&label=beta&color=orange)](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases)

## Скачать

Последняя бета для тестирования — в разделе
[Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases).
Стабильная версия появится в RuStore и GetApps после модерации.

**Download:** the latest beta is in
[Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases).
Stable version will be published in RuStore and GetApps after store review.

Настройка Xiaomi, Redmi и Poco: отключение сервисов аналитики и применение
параметров MIUI / HyperOS через локальный ADB. Без root-прав.

> Не аффилировано с Xiaomi. Все изменения обратимы.
> Результат зависит от модели и версии прошивки.

## Что делает

- Отключает системные сервисы аналитики (7 пакетов)
- Применяет системные параметры MIUI / HyperOS
- Динамическое обнаружение порта ADB через mDNS
- Работает без root
- Откат изменений одной кнопкой, с подтверждением
- Перезагрузка через ADB с подтверждением

## Как начать

1. Запустите приложение и нажмите «Оптимизировать».
2. Включите службу специальных возможностей — приложение откроет нужный экран.
3. Разрешите показ поверх других окон — переключатель включится автоматически.
4. Дождитесь завершения.

## Архитектура

- **AdbPortResolver** — mDNS-обнаружение порта ADB (`_adb-tls._tcp.`) с фолбэком на 5555
- **AdbClient** — собственная реализация протокола ADB поверх TCP-сокета с обработкой ошибок сети и
  автопереподключением
- **OptimizationEngine** — трёхэтапная оптимизация с fallback-методами, финальной верификацией и
  логированием неотключённых пакетов
- **AdbEnablerService** — AccessibilityService для автоматического включения беспроводной отладки и
  выполнения настроек
- **AppDependencies** — ручная Dependency Injection для тестируемости
- **PreferencesManager** — типобезопасные ключи предпочтений через sealed interface

## Тестирование

Unit-тесты в `app/src/test/java/`:

```bash
./gradlew test
```

**Покрыто:**

- Успешная оптимизация через системные ключи
- Fallback на отключение пакетов при неуспешных ключах
- Частичное применение настроек не прерывает работу
- Автоматическое переподключение при обрыве соединения
- Обработка ошибок ADB
- Восстановление всех параметров и сервисов
- mDNS-обнаружение с корректным фолбэком

## Приватность

Никаких данных не собирается. Все команды выполняются локально на устройстве.

## FAQ

**Работает ли на устройствах не Xiaomi?**
Нет. Приложение заточено под MIUI / HyperOS: системные ключи и пакеты
специфичны для прошивок Xiaomi, Redmi и Poco.

**Нужен ли root?**
Нет. Все команды выполняются через локальный ADB с правами shell.

**Почему нужна служба специальных возможностей?**
Она один раз включает беспроводную отладку и нажимает системные
переключатели. После завершения служба отключает сама себя.

**Что делать, если оптимизация не прошла?**
Проверьте, что беспроводная отладка включена, и повторите. Если не помогло —
откройте Issue и укажите модель, версию прошивки и версию Android.

**Как всё вернуть?**
Кнопка «Отменить оптимизацию» с подтверждением возвращает все параметры
и сервисы в исходное состояние.

**Влияет ли это на гарантию и обновления по воздуху?**
Нет. Приложение не меняет прошивку и не устанавливает бинарники —
только системные настройки и состояние пакетов. OTA-обновления приходят
как обычно, после них оптимизацию можно повторить.

**А если мне нужен какой-то отключённый сервис?**
Кнопка отката возвращает всё. Если хотите выборочно оставить один сервис
включённым — сообщите в Issue, добавим такую возможность.

**Это безопасно? Приложение просит доступ к спецвозможностям.**
Да, это намеренно: доступ нужен только на время первичной настройки.
Код открыт, можно проверить, что именно делает служба.
После завершения она отключается, и доступ больше не используется.

**Зачем показ поверх других окон?**
Чтобы вы видели прогресс оптимизации поверх системных экранов.
Разрешение нужно только во время работы — после отключается.

## Для разработчиков

Kotlin · Jetpack Compose · Material 3 · собственная реализация ADB поверх TCP

Gradle 9.5.0 · AGP 9.3.1 · Kotlin 2.4.10 · Compose BOM 2026.06.01 · JDK 21

Встроенный Kotlin от AGP 9 (без отдельного плагина `kotlin-android`).

CI/CD через GitHub Actions (`.github/workflows/android.yml`): автоматическая
сборка и тесты на каждый push и pull request.

**Сборка:**

```bash
./gradlew assembleDebug
```

**Тесты:**

```bash
./gradlew test
```

## Поддержать проект

- ЮMoney: https://yoomoney.ru/to/410011379195150
- CloudTips: https://pay.cloudtips.ru/p/90614cff

## Лицензия

CC BY-NC 4.0 — бесплатно использовать, изменять и делиться
в некоммерческих целях с указанием авторства (ElthebestDevelopman).
Любое коммерческое использование запрещено без письменного разрешения автора.

## Автор

ElthebestDevelopman

---

## English

A configuration tool for Xiaomi / Redmi / Poco devices. Disables system
analytics services and applies MIUI / HyperOS parameters via local ADB —
no root access required.

### Features

- Disables system analytics services (7 packages)
- Applies MIUI / HyperOS system parameters
- Dynamic ADB port discovery via mDNS
- No root required
- One-click rollback with confirmation
- Reboot via ADB with confirmation

### How to start

1. Launch the app and tap **Optimize**.
2. Enable the accessibility service — the app opens the right screen.
3. Grant **Display over other apps** — the switch is toggled automatically.
4. Wait until completion.

### Architecture

- **AdbPortResolver** — mDNS discovery of ADB port (`_adb-tls._tcp.`) with fallback to 5555
- **AdbClient** — custom ADB protocol over TCP socket with network error handling and
  auto-reconnection
- **OptimizationEngine** — three-stage optimization with fallback methods, final verification and
  logging of still-enabled packages
- **AdbEnablerService** — AccessibilityService for automatic wireless debugging enablement
- **AppDependencies** — manual Dependency Injection for testability
- **PreferencesManager** — type-safe preference keys via sealed interface

### Testing

Unit tests in `app/src/test/java/`. Run with:

```bash
./gradlew test
```

**Coverage:**

- Successful optimization via system keys
- Fallback to package disabling when keys fail
- Partial application does not interrupt the workflow
- Automatic reconnection on connection loss
- ADB error handling
- Restoring all parameters and services
- mDNS discovery with correct fallback

### FAQ

**Does it work on non-Xiaomi devices?**
No. The app targets MIUI / HyperOS: system keys and packages
are specific to Xiaomi, Redmi and Poco firmware.

**Is root required?**
No. All commands run via local ADB with shell privileges.

**Why is the accessibility service needed?**
It enables wireless debugging once and toggles system switches.
After completion, it disables itself.

**What if optimization didn't complete?**
Make sure wireless debugging is enabled and try again. If it still fails,
open an Issue with device model, firmware version and Android version.

**How to revert everything?**
The "Undo optimization" button with confirmation restores all parameters
and services to their original state.

**Does it affect warranty or OTA updates?**
No. The app doesn't modify firmware or install binaries — only system
settings and package states. OTA updates work as usual; optimization
can be re-applied afterwards.

**What if I need a disabled service?**
The rollback button restores everything. If you want to keep one specific
service enabled, let us know in an Issue and we'll add that option.

**Is it safe? The app requests accessibility permission.**
Yes, intentionally: the permission is used only during initial setup.
The code is open source, so you can verify what the service does.
After completion it disables itself and is no longer used.

**Why the "display over other apps" permission?**
To show the optimization progress overlay on top of system screens.
The permission is needed only while working and is released afterwards.

### For developers

Kotlin · Jetpack Compose · Material 3 · custom ADB over TCP

Gradle 9.5.0 · AGP 9.3.1 · Kotlin 2.4.10 · Compose BOM 2026.06.01 · JDK 21

Built-in Kotlin from AGP 9 (no separate `kotlin-android` plugin).

CI/CD via GitHub Actions (`.github/workflows/android.yml`): automatic
build and tests on every push and pull request.

**Build:**

```bash
./gradlew assembleDebug
```

**Tests:**

```bash
./gradlew test
```

### Support

- YooMoney: https://yoomoney.ru/to/410011379195150
- CloudTips: https://pay.cloudtips.ru/p/90614cff

### License

CC BY-NC 4.0 — free to use, modify and share for **noncommercial purposes**
with attribution to ElthebestDevelopman. Any commercial use is prohibited
without separate written permission from the author.

### Author

ElthebestDevelopman

### Disclaimer

The app is provided "as is". Results depend on the device model and firmware
version. Not affiliated with Xiaomi Inc.

```

---