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
