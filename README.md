# 🤖 XiaoHyperCleaner

**Без root. Без лишнего. Без данных.**

[![Version](https://img.shields.io/badge/version-1.0--beta2-blue)](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen)](https://www.android.com/)
[![License](https://img.shields.io/badge/license-CC%20BY--NC%204.0-orange)](https://creativecommons.org/licenses/by-nc/4.0/)
[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-success)](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)](https://kotlinlang.org/)

<div align="center">

**🇷🇺 Русский** | 🇬🇧 [English](README.en.md)

</div>

Инструмент для отключения системной аналитики и рекламы на устройствах **Xiaomi**, **Redmi** и *
*Poco** через локальный ADB. Работает без root-прав, все изменения полностью обратимы.

---

## 📋 Содержание

- [О проекте](#-о-проекте)
- [Возможности](#-возможности)
- [Требования](#-требования)
- [Установка](#-установка)
- [Использование](#-использование)
- [Структура проекта](#-структура-проекта)
- [Разработка](#-разработка)
- [Тестирование](#-тестирование)
- [FAQ](#-faq)
- [Roadmap](#-roadmap)
- [Лицензия](#-лицензия)
- [Автор](#-автор)
- [Поддержка проекта](#-поддержка-проекта)

---

## 🎯 О проекте

**XiaoHyperCleaner** — приложение для владельцев устройств Xiaomi, Redmi и Poco, которые устали от
системной аналитики и рекламы, встроенной в MIUI / HyperOS.

Приложение подключается к устройству через **локальный ADB** (`127.0.0.1`) и выполняет настройки,
которые раньше приходилось делать вручную через консоль:

- ✅ **Без root-прав** — работает через стандартный ADB
- ✅ **Без модификации системы** — не трогает системные разделы
- ✅ **Без сбора данных** — всё работает локально на устройстве
- ✅ **Полная обратимость** — одним нажатием кнопки
- ✅ **Транзакционный rollback** — при любом сбое изменения откатываются автоматически

---

## ✨ Возможности

### Основные функции

| Функция                         | Описание                                                                     |
|---------------------------------|------------------------------------------------------------------------------|
| 🚫 **Отключение аналитики**     | `com.miui.analytics`, `com.xiaomi.ab`, `com.miui.bugreport` и др.            |
| 🛑 **Отключение рекламы**       | `com.xiaomi.ad`, `com.miui.ad`, `com.miui.systemAdSolution`                  |
| 🤖 **Отключение рекомендаций**  | `com.miui.msa.core`, `com.miui.personalassistant`, `com.miui.smartassistant` |
| ⚙️ **Оптимизация параметров**   | Анимации, лимит фоновых процессов, энергосбережение                          |
| 🌐 **DNS-фильтр (опционально)** | Блокировка рекламных доменов через AdGuard DNS                               |
| 🔄 **Полный откат**             | Все изменения можно вернуть одной кнопкой                                    |

### Особенности

- 📚 **Онбординг** при первом запуске (3 коротких экрана)
- 🎛️ **Диалог опций** перед оптимизацией
- ⚠️ **Предупреждение о DNS** — объяснение возможных нюансов
- 📊 **Детальный отчёт** после оптимизации:

```
✅ Отключено сервисов: 7
✅ Применено параметров: 12
⚠️ Не удалось: 2 (защищены системой)
```

- 🤖 **Милый робокот** на сплеш-экране, перекатывающий клубок ниток
- 📝 **Шаринг логов** — в меню есть кнопка «Поделиться логом»
- 🌍 **Локализация** — RU и EN, всегда синхронно
- 🌙 **Тёмная тема** — следует за системной

---

## 📱 Требования

| Параметр                 | Значение                |
|--------------------------|-------------------------|
| **Устройства**           | Xiaomi / Redmi / Poco   |
| **Прошивка**             | MIUI 12+ или HyperOS    |
| **Android**              | 10+ (API 29+)           |
| **Root**                 | ❌ Не требуется          |
| **Беспроводная отладка** | Включится автоматически |

---

## 📥 Установка

### Вариант 1: APK из Releases

1. Скачайте `XiaoHyperCleaner-v1.0-beta2.apk`
   из [Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases)
2. Разрешите установку из неизвестных источников
3. Установите и запустите
4. Пройдите онбординг (3 экрана)

### Вариант 2: Сборка из исходников

```bash
git clone https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner.git
cd XiaoHyperCleaner
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`

### ⚠️ Важно для Android 13+ (MIUI 14 / HyperOS)

На Android 13+ система блокирует sideload-приложениям доступ к «ограниченным настройкам» (в
некоторых прошивках называется «Запрещённые настройки»). Это **системное ограничение Android**, не
баг приложения.

**Как обойти:**

1. Откройте **Настройки → Приложения → XiaoHyperCleaner**
2. Нажмите **⋮** в правом верхнем углу
3. Выберите **«Разрешить ограниченные настройки»** (или «Запрещённые настройки» в HyperOS)
4. Подтвердите отпечатком/паролем
5. Вернитесь в приложение

Это делается **один раз**. Приложение само предложит это сделать при необходимости.

---

## 🚀 Использование

### Первый запуск

1. Запустите приложение
2. Пройдите **онбординг** (3 экрана)
3. Нажмите **«Оптимизировать»** на главном экране
4. Выберите параметры (вкл/выкл DNS-фильтр)
5. Включите **службу специальных возможностей** (нужна один раз)
6. Приложение **автоматически**:
    - Включит беспроводную отладку
    - Подключится к ADB
    - Применит все настройки
    - Покажет **детальный отчёт**

### Откат изменений

1. Главный экран → кнопка **«Отменить оптимизацию»**
2. Подтвердите действие
3. Все пакеты включатся обратно
4. Все системные параметры вернутся к исходным значениям
5. DNS вернётся к системному (если был включён)

### Поделиться логом

Если что-то пошло не так:

1. Откройте **меню ⋮** в правом верхнем углу
2. Нажмите **«Поделиться логом»**
3. Отправьте файл `xhc.log`
   в [Issues](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/issues)

Все чувствительные данные в логе маскируются: IP-адреса (кроме 127.0.0.1), длинные токены, пути к
пользовательским данным, значения системных настроек.

---

## 🏗 Структура проекта

```
app/src/main/java/com/xiaohypercleaner/
├── data/
│   ├── AdbClient.kt              # ADB over TCP (%04x заголовок, shell до EOF)
│   ├── AdbExecutor.kt            # Интерфейс для DI/тестов
│   ├── AdbPortResolver.kt        # mDNS _adb-tls._tcp
│   ├── OptimizationEngine.kt     # 4 метода + DNS + транзакционный rollback
│   ├── PreferencesManager.kt     # DataStore
│   └── ServiceRegistry.kt        # Списки пакетов для отключения
├── service/
│   ├── AdbEnablerService.kt      # Accessibility-цепочка
│   ├── OverlayController.kt      # onCancel через WeakReference
│   └── OverlayService.kt         # Оверлей с прогрессом
├── ui/
│   ├── MainActivity.kt           # Главный экран
│   ├── MainViewModel.kt          # Логика UI
│   ├── SplashActivity.kt         # Сплеш с робокотом и клубком
│   ├── OnboardingScreen.kt       # Онбординг (3 экрана)
│   ├── WebViewActivity.kt        # WebView для донатов
│   └── components/
│       └── Dialogs.kt            # Все диалоги
├── util/
│   ├── AppLog.kt                 # Бета-логирование с маскировкой
│   ├── LogMasker.kt              # Маскировка данных в логах
│   ├── OptimizationNotifier.kt   # StateFlow для передачи результатов
│   └── Wait.kt                   # waitFor helper
├── AppConstants.kt               # Константы (таймауты, прогресс)
├── AppDependencies.kt            # Ручная DI
└── XiaoHyperApp.kt               # Application

app/src/main/res/
├── drawable/
│   ├── ic_robot_companion.xml    # Робокот
│   └── ic_yarn_ball.xml          # Клубок ниток
├── values/strings.xml            # RU
├── values-en/strings.xml         # EN
└── xml/
    ├── accessibility_service_config.xml
    └── file_paths.xml            # FileProvider для логов

app/src/test/java/
├── OptimizationEngineTest.kt     # 12 тестов
├── AdbPortResolverTest.kt        # 3 теста
├── LogMaskerTest.kt              # 6 тестов
└── MainViewModelTest.kt          # 8 тестов (Robolectric)
```

---

## 💻 Разработка

### Стек технологий

| Компонент       | Версия                  |
|-----------------|-------------------------|
| **Gradle**      | 9.5                     |
| **AGP**         | 9.3.1 (built-in Kotlin) |
| **Kotlin**      | 2.4.10                  |
| **Compose BOM** | 2026.06.01              |
| **compileSdk**  | 37                      |
| **targetSdk**   | 36                      |
| **minSdk**      | 28                      |
| **JDK**         | 21                      |

### Сборка

```bash
# Debug сборка
./gradlew assembleDebug

# Release сборка
./gradlew assembleRelease

# Unit-тесты
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

### Архитектурные решения

- **Ручная DI** через `AppDependencies` — нет Dagger/Hilt
- **AccessibilityService** для автоматизации UI-действий
- **OverlayService** для отображения прогресса
- **StateFlow** для реактивного UI
- **DataStore** для персистентности
- **Coroutines** для асинхронности
- **Транзакционный rollback** в `OptimizationEngine`

### Важные нюансы

- `%04x` заголовок ADB **нижним регистром** (критично!)
- Shell читается **до EOF**
- `ADB_TIMEOUT_MS` как **Int** (Socket.connect требует Int), остальные `*_MS` как Long
- Проверка службы через **`flattenToString()`** (короткая форма не работает)
- `lateinit listener` в mDNS (метка `this@DiscoveryListener` не работает)

---

## 🧪 Тестирование

### Unit-тесты (29 тестов)

```bash
./gradlew testDebugUnitTest
```

| Файл                     | Тестов | Что проверяется                     |
|--------------------------|--------|-------------------------------------|
| `OptimizationEngineTest` | 12     | Оптимизация, откат, DNS, транзакции |
| `AdbPortResolverTest`    | 3      | mDNS discovery, mergePorts          |
| `LogMaskerTest`          | 6      | Маскировка IP, токенов, путей       |
| `MainViewModelTest`      | 8      | UI-логика (Robolectric)             |

### Ручное тестирование

См. [чек-лист в Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases)
для детального чек-листа.

### Как оставить багрепорт

Откройте [Issue](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/issues) и укажите:

```
Модель: Xiaomi Redmi Note 10 Pro
Прошивка: MIUI 14.0.4
Android: 13
Проблема: пункт "Тумблер беспроводной отладки" — не включается автоматически
Лог: прикреплён
Скриншот: прикреплён
```

**Лог** можно получить через меню ⋮ → «Поделиться логом».

---

## ❓ FAQ

### Почему приложение не работает на Samsung / Realme / других?

Технически ADB работает везде, но **списки пакетов для отключения специфичны для MIUI / HyperOS**.
На других прошивках приложение отключит не те пакеты или не отключит ничего. На текущий момент
поддерживаются только Xiaomi / Redmi / Poco.

### Сломает ли это обновления прошивки?

Нет. При обновлении MIUI / HyperOS все отключённые пакеты **восстановятся автоматически** (они же
системные). После обновления нужно будет снова нажать «Оптимизировать».

### Что если после отката что-то не работает?

Все команды приложения — стандартные ADB-команды. Если возникли проблемы:

1. Перезагрузите устройство
2. Если не помогло — сбросьте настройки до заводских (крайняя мера)

За время тестирования таких случаев не было, но знать стоит.

### Почему приложение не в Google Play?

Политика Google Play **запрещает** использование Accessibility Services для автоматизации
UI-действий (нажатие кнопок, включение переключателей). Это основное назначение нашей службы.
Поэтому приложение распространяется через **RuStore**, **GetApps** и **GitHub**.

### Можно ли посмотреть что делает приложение?

Да, проект полностью открыт. Все команды ADB логируются в `xhc.log` (в бета-сборках). Каждый может
проверить, что именно выполняется.

---

## 🗺 Roadmap

### v1.1 (следующая версия)

- [ ] Профили оптимизации: «Мягкая» / «Средняя» / «Максимальная»
- [ ] История оптимизаций в DataStore
- [ ] Экспорт/импорт настроек
- [ ] Больше методов оптимизации (appops, suspend)
- [ ] Улучшенный онбординг с видео

### v2.0 (будущее)

- [ ] Поддержка других прошивок (OneUI, ColorOS)
- [ ] Плагины для расширенной оптимизации
- [ ] Облачная синхронизация настроек
- [ ] Виджет для быстрого запуска

---

## 📄 Лицензия

**CC BY-NC 4.0** (Creative Commons Attribution-NonCommercial 4.0)

- ✅ Использование разрешено
- ✅ Модификация разрешена
- ✅ Распространение разрешено
- ❌ Коммерческое использование запрещено

Полный
текст: [creativecommons.org/licenses/by-nc/4.0](https://creativecommons.org/licenses/by-nc/4.0/)

---

## 👨‍💻 Автор

**ElthebestDevelopman**

- GitHub: [@elthebestdevelopman-cyber](https://github.com/elthebestdevelopman-cyber)
- Проект: [XiaoHyperCleaner](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner)

---

## 💖 Поддержка проекта

Если приложение оказалось полезным и хочется сказать спасибо:

| Сервис        | Ссылка                                                                   |
|---------------|--------------------------------------------------------------------------|
| **ЮMoney**    | [yoomoney.ru/to/410011379195150](https://yoomoney.ru/to/410011379195150) |
| **CloudTips** | [pay.cloudtips.ru/p/90614cff](https://pay.cloudtips.ru/p/90614cff)       |

Или просто **поставьте ⭐ звезду** на GitHub — это помогает другим найти проект.

---

## ⚖️ Дисклеймер

Приложение распространяется «как есть». Автор не несёт ответственности за любые последствия
использования. Перед применением ознакомьтесь с FAQ и разделом «Использование».

Все товарные знаки (Xiaomi, Redmi, Poco, MIUI, HyperOS) принадлежат их владельцам. Приложение не
аффилировано с Xiaomi Corporation.

---

<div align="center">

**Сделано с ❤️ для владельцев Xiaomi**

[Releases](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/releases) • [Issues](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/issues) • [Discussions](https://github.com/elthebestdevelopman-cyber/XiaoHyperCleaner/discussions)

</div>