# XiaoHyperCleaner — итоги и решения (сводка)

Дата: 2026-09-15. Охват: аудит `.clinerules` + лицензия + монетизация + ядро + раннер + каталог.

---

## 1. Лицензия и монетизация

- **Лицензия:** CC BY-NC-SA 4.0 → GPL-3.0-or-later (канонический текст с gnu.org) + dual-licensing.
  - Публичный код — GPL-3.0 (copyleft: форки обязаны открывать исходники).
  - Автор сохраняет право выдавать коммерческую лицензию для closed-source/white-label.
  - `COMMERCIAL-LICENSE.md` — одностраничное объявление dual-licensing.
  - `README.md`, `README.en.md`, `CONTRIBUTING.md`, `SECURITY.md` — обновлены разделы лицензии и бейджи.
  - `.kt`-файлы без copyright-заголовков (правило `.clinerules`).
- **Донаты:**
  - Кнопки ЮMoney/CloudTips из меню и оверлея удалены → единая кнопка «Поддержать проект».
  - Страница донатов на GitHub Pages: `https://elthebestdevelopman-cyber.github.io/support/` (7 языков, TON/TRC-20, Boosty, ЮMoney, CloudTips, Telegram Stars).
  - `WebViewActivity` и `openWebView()` удалены полностью.
  - `.github/FUNDING.yml` → `custom: https://.../support/`.
- **Модель монетизации (решено):**
  - Платным приложение НЕ делать (много бесплатных аналогов).
  - Донаты — основной поток сейчас.
  - Commercial dual-license + white-label для других прошивок/брендов — v2.0.
  - Никаких «профилей оптимизации за деньги», «автокаталога за деньги», рекламы в приложении.
  - Premium только косметика (тема с робокотом / Supporter Pack) через стоковый биллинг — позже.

---

## 2. Аудит `.clinerules` — устранённые нарушения

| Приоритет | Нарушение | Решение |
|---|---|---|
| P1 | `result_share_log` отсутствовал в `values-es` и `values-zh` | Ключ добавлен во все 7 локалей |
| P2 | Хардкод UI-строк: ←, ⋮, 👆, 💡, %, app_name, «Уведомления» | Вынесены в `strings.xml` (непереводимые — `translatable="false"`) |
| P2 | `compose-ui-tooling-preview` не используется | Удалён из `libs.versions.toml` и `build.gradle.kts` |
| P2 | Deprecated `isChecked`/`recycle()` в `SimpleRunner.kt` | `isCheckedCompat()` через `AccessibilityNodeInfoCompat`; `recycle()` под guard + suppress |
| P2 | `README.md`/`README.en.md` — ложные утверждения | Все исправлены под реальность |
| P3 | `app/release/mapping.txt` в VCS | Уже в `.gitignore`; `CONTRIBUTING.md` дополнен правилом |
| P3 | `README` — счётчик тестов 6/45 (факт — 9/65) | Синхронизирован |
---

## 3. Ядро оптимизации (OptimizationEngine)

- **Удалён `aggressiveMode`** (поля, флаг, тумблер в `OptionsDialog`, мёртвая `applyRegionalKeys()`, `Transaction.originalRegion`, `REGIONAL_KEYS`).
  - AdGuard DNS остался единственной опцией в диалоге.
  - Причина: `aggressiveMode` управлял только `applyRegionalKeys`, которая уже была выпотрошена до no-op (регион не меняется).
- **Удалены `SYSTEM_SETTINGS`** (`low_power=1`, анимации `window_animation_scale=0.5`, `always_finish_activities=0`).
  - Причина: «мы не оптимизатор», принудительное энергосбережение могло резать уведомления, анимации — чуждый UX.
  - Ядро теперь: приватные ключи (телеметрия/рекомендации/лимиты трекинга) + отключение пакетов + DNS.
- **Точный restore «как было»:**
  - `RestoreSnapshot` + `RestoreSnapshotStore`: после успешной оптимизации оригиналы настроек и DNS сохраняются в DataStore (`restore_snapshot_json`).
  - Ручная «Отменить оптимизацию» восстанавливает **оригиналы**, а не заводские дефолты.
  - При отсутствии снапшота — graceful fallback на дефолты.
- **Тесты:** `OptimizationEngineTest` — rollback/транзакции + `restoreUsesPersistedOriginals`.

---

## 4. Простой режим (AccessibilityService / SimpleRunner)

### 4.1 Системные фиксы (6 корневых причин)

1. **`resetToHome()`:** `startActivity(HOME-intent)` → `performGlobalAction(GLOBAL_ACTION_HOME)`. Убран resolver «Главный экран по умолчанию».
2. **`resetSettingsToRoot()`:** проверка корня по ≥2 маркерам; при неудаче — глобальный Back до 5 раз.
3. **`findSwitchNear`:** поиск CheckBox в поддереве ближайшего clickable-предка (рекурсивно) — достаёт `widget_frame > CheckBox`.
4. **`forceStopPackage`:** удалён из Simple-режима (рефлексия не работает без привилегий).
5. **`DirectIntentNavigator`:** компонентные/AOSP-интенты вместо `miui.intent.action.*`; `ACTION_APP_NOTIFICATION_SETTINGS` для notif_*.
6. **`app_not_installed` → skipped** (не failure).

### 4.2 Launch-интенты для app-шагов

- 6 шагов (music_sys, security_sys, cleaner, downloads, mivideo, shareme): `getLaunchIntentForPackage(pkg)` по списку кандидатов.
- Перед drill: проверка `foreground-пакет == целевой И дерево доступности непустое`; иначе следующий кандидат.
- Фолбэк App Info убран.

### 4.3 Специальные обработчики

- **`home_suggestions` (global_ru):** если дефолтный home ≠ `com.miui.home` → skipped с `home_not_miui`.
- **`themes`:** consent-цикл — тап согласия и продолжение drill (до 3 итераций).
- **`browser_sys`:** `com.mi.globalbrowser` + ретрай до 3 попыток.
- **`msa` (global_ru):** drillPath `[Конфиденциальность, Реклама]`.
- **`ads_personalization` (global_ru):** tap-fallback — тап «Удалить рекламный идентификатор» + подтверждение диалога.

### 4.4 Tap-fallback (новый механизм)

- Каталог: `tapFallbackTexts` + `confirmTexts` для шага.
- `AdaptiveCatalog.mergeTapFallbackTexts()` — мёрж из варианта.
- `SimpleRunner.findAndToggleSwitch`: если тумблер не найден, а fallback предписан — тапаем кнопку + подтверждаем диалог.
- Для `cn_hyperos` список пуст — поведение не меняется.

---

## 5. Вариантность каталога (AdaptiveCatalog)

- **`adaptive_catalog.json`:** содержимое перенесено в `variants.cn_hyperos` (байт-в-байт); добавлен `variants.global_ru`.
- **Диспетчер:** `global_ru = isHyperOs==false && region != CN && language == "ru"`.
  - Версии MIUI/SDK — только в лог; нерусские Global → `cn_hyperos`.
  - `catalogVariant` в fingerprint-блоке снапшотов; `AppLog.i` на старте прогона.
- **Мёрж:** маркеры варианта имеют приоритет над Kotlin-дефолтами.
- **Тесты:** `AdaptiveCatalogTest` — 6 кейсов диспетчера + tap-fallback.

---

## 6. Диагностика (дампы и логи)

- **`DiagnosticSnapshotManager`:**
  - Снапшоты в `getExternalFilesDir("diag")`, по одному на шаг: JSON + PNG-скриншот (API 30+).
  - Fingerprint: model, sdk, miui ui version, incremental, region, catalogVariant.
  - Логирование: `AppLog.i` (путь, размер) / `AppLog.e` (код onFailure).
  - `accessibility_service_config.xml`: `android:canTakeScreenshot="true"`.
- Снапшоты — при любой ошибке шага (не только timeout).
- **Toggled-логи:** `AppLog.i` с `text`, `desc`, `bounds` переключенного узла.
- **Экспорт:** `shareLog` пакует `diag/` в `xhc_diagnostics.zip` → `ACTION_SEND_MULTIPLE`.
- **`tools/diag-diff.ps1`:** one-command diff (before/after) с `[FIXED]`-маркером.

---

## 7. Restore без ADB

- Если все действия — simple toggles → реверс через Accessibility, ADB не нужен → success.
- ADB-подключение только при ADB-only действиях.
- Если ADB нужен и недоступен → предложить включение (AdbEnablerService); после отказа — Failure + предложение отправить логи.
- Логи (англ.): restore scope (simple/adb), result per group.

---

## 8. Тесты

- 9 сьютов, 71 тест.
- Обязательные области покрыты: OptimizationEngineTest (rollback + restore), LogMaskerTest, SimpleStepsTest, AdbPortResolverTest.
- Добавлены: AdaptiveCatalogTest, SimpleRunnerClearDataTest, TextMatcherTest.

---

## 9. Процедура прогона

См. `docs/diag/global_ru_plan.md` → раздел «Процедура прогона». Кратко:
1. Подготовка устройства (USB, Accessibility).
2. Чистка старых diag-дампов.
3. Запуск Simple Mode.
4. Экспорт логов/дампов: `adb logcat`, `adb pull`.
5. Сравнение: `tools/diag-diff.ps1 -Before diag-dumps/before -After diag-dumps/after`.
6. Критерии: `ads_personalization` pass, `carousel` pass, `ux_program` pass, `themes` pass, `home_suggestions` pass/skipped, app-шаги без App Info, скриншоты без onFailure.

---

## 10. Отложенные задачи

- Ссылки Boosty/крипта в `FUNDING.yml` (по готовности).
- `CLA.md` перед приёмом внешних PR (сохранить право dual-licensing).
- Тема с робокотом (Supporter Pack) через стоковый биллинг.
- Расширение на другие бренды (Samsung, OPPO) — v2.0.