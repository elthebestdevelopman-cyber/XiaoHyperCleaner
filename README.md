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
- **AdbClient** — собственная реализация протокола ADB поверх TCP-сокета с обработкой ошибок сети
- **OptimizationEngine** — трёхэтапная оптимизация с fallback-методами и финальной проверкой
- **AdbEnablerService** — AccessibilityService для автоматического включения беспроводной отладки и выполнения настроек
- **AppDependencies** — ручная Dependency Injection для тестируемости

## Тестирование

Unit-тесты в `app/src/test/java/`:

```bash
./gradlew test

## Покрыто:

- Успешная оптимизация через системные ключи
- Fallback на отключение пакетов при неуспешных ключах
- Обработка ошибок ADB
- Восстановление всех параметров и сервисов

## Приватность

Никаких данных не собирается. Все команды выполняются локально на устройстве.

## Для разработчиков

Kotlin · Jetpack Compose · Material 3 · собственная реализация ADB поверх TCP

Gradle 9.5.0 · AGP 9.3.1 · Kotlin 2.4.10 · Compose BOM 2026.06.01 · JDK 21

Встроенный Kotlin от AGP 9 (без отдельного плагина `kotlin-android`).

Сборка:

```bash
./gradlew assembleDebug
```

Тесты:

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
- **AdbClient** — custom ADB protocol over TCP socket with network error handling
- **OptimizationEngine** — three-stage optimization with fallback methods and final verification
- **AdbEnablerService** — AccessibilityService for automatic wireless debugging enablement
- **AppDependencies** — manual Dependency Injection for testability

### Testing

Unit tests in `app/src/test/java/`. Run with `./gradlew test`.

### For developers

Kotlin · Jetpack Compose · Material 3 · custom ADB over TCP

Gradle 9.5.0 · AGP 9.3.1 · Kotlin 2.4.10 · Compose BOM 2026.06.01 · JDK 21

Built-in Kotlin from AGP 9 (no separate `kotlin-android` plugin).

Build:

```bash
./gradlew assembleDebug
```

Tests:

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
