# MASTER-ЗАДАЧА v2 (формализация)

Статус: формализация мастер-промта v2, согласованная с аудитом
(`master_plan_audit.md`) и Ак-задачей от 2026-09-16. Исходный чат-промт
в репозиторий не входил; ниже — каноническая фиксация требований.

## Цель

Автономный Простой режим без захардкоженных маршрутов под конкретные
прошивки: сканер находит экраны и переключатели по семантике, старые
вариантные пути остаются только как подсказки-фолбэки.

## Блоки

### Блок 1. Оверлей-непрерывность (P0)

- Один show перед фазой скана, один hide на экране результатов;
  промежуточные `hideOverlay` убрать; оставшиеся show/hide логируют caller.
- Фон: полупрозрачный `#99000000`, полноэкранный, поглощает тапы,
  `FLAG_NOT_FOCUSABLE`; текст прогресса «i/N» и «Не касайтесь экрана».
- `onDetachedFromWindow` watchdog: re-add оверлея с логом.
- Гейт раннера: перед шагом убедиться «оверлей прикреплён» (ждать до 2 с,
  иначе пауза с сообщением пользователю).
- Логирование перехвата касаний на оверлее.
- `isAttached` в `OverlayController`; удалить мёртвые `hint`/`showManualPointer`.
- Новые UI-строки — в `strings.xml` во все 7 локалей (паритет ключей).

### Блок 2. Discovery-движок

- `ActivityScanner`: поиск активностей пакета через `GET_ACTIVITIES`,
  сопоставление по keywords, кэш
  `act_cache_<pkg>_<versionCode>_<miuiIncremental>`
  («unknown»-суффикс при отсутствии `miuiIncremental`).
- `SwitchFinder`: Switch/CheckBox/RadioButton через `isCheckedCompat`;
  `checked_before`; `already_off` → success.
- `ComponentVerifier`: `awaitScreen` по `screenMarkers`, 5 с, сброс на
  домашний экран через `GLOBAL_ACTION_HOME`.
- `ScanOrchestrator`: оркестрация шагов через движок; интеграция в
  `SimpleRunner`/`AdbEnablerService`.

### Блок 3. Семантическая таблица шагов

- `assets/catalog/semantic_steps.json` (создан вручную, 26 записей, 7 локалей),
  `SemanticStep`/`SemanticCatalog`.
- `id`, `title{7}`, `keywords{7}`, `screenMarkers{7}`,
  `actionType ∈ {toggle, tap_confirm, sequence, skip}`,
  `sequenceKind ∈ {CONSENT_WALL, DELAYED_CONFIRM, TAP_FALLBACK_SEQUENCE,
  CLEAR_DATA_DECLINE, OVERFLOW_MENU}`, `confirmTexts{7}`, `tapFallbackTexts{7}`,
  `consentTexts{7}`, `overflowMenuLabels{7}`, `confirmWaitMs`,
  `maxConsentIterations`, `launchPackage`, `skipReason`, `fallbackDrillPath`,
  `safe`, `localeCoverage`.
- Захардкоженные пути убрать из рабочей логики; содержимое старых вариантов
  сохранить как `fallbackDrillPath`-подсказки сканеру для всех вариантов
  (включая cn_hyperos).
- `SimpleSteps.ALL` и `searchTexts` — deprecated на один релиз.

### Блок 4. Confidence gate

- `SemanticGate`: keyword-match И switch найден И `screenMarkers` совпали →
  действовать; иначе `skipped` с причиной `low_confidence` (без угадывания).

### Блок 5. Префильтр плана

- `PlanBuilder`: только установленные пакеты (`requiredPackages`) +
  plan-time home-skip через `resolveHomePackage()` (переиспользовать
  существующий механизм home-skip); runtime-ветка `app_not_installed` и
  `home_not_miui` удаляются; total на оверлее = размер плана.

### Блок 6. Проверка отката

- `checked_before` пишется в момент тумблера и сохраняется в
  `RestoreSnapshot.simpleToggleStates`; reverse использует сохранённое
  состояние, а не инверсию дефолта. Обратная совместимость: отсутствие поля
  → прежняя логика.

### Блок 7. Уровни диагностики

- `DiagnosticLevel` OFF/COMPACT/FULL: дефолт FULL в debug, COMPACT в
  release; FULL в release — только через 7 тапов по `textVersion` в
  `MenuDialog` (`PreferencesManager.diagLevelOverride`).
- COMPACT: сводка ≤60 узлов без bounds.
- Purge: последние 3 прогона или 7 дней, потолок 50 МБ; триггер — старт
  приложения и `captureAndSaveSnapshot`.
- Лог `diag: level=... source=...`; скриншоты в release только при FULL и
  только в opt-in отчёт.

### Блок 8. Документация

- `docs/diag/global_ru_plan.md` (синхрон, статусы, критерии v2),
  `docs/SUMMARY.md` (счётчик тестов), `CHANGELOG.md` (`[Unreleased]`).

## Коммиты и гейты

Гейт после каждого: `./gradlew :app:assembleDebug :app:lintDebug
:app:testDebugUnitTest`.

0. `docs: master-plan artifacts` — этот промт, аудит, черновик переводов.
1. `chore: pre-master WIP` — 7 файлов рабочей директории
   (без `semantic_steps.json`).
2. `fix: overlay continuity` — блок 1. СТОП после коммита: device-check
   (нет подряд идущих `overlay hidden`, нет `re-added by watchdog` на
   спокойном прогоне).
3. `feat: discovery engine` — блоки 2+6.
4. `feat: semantic steps + plan pre-filter` — блоки 3–5, тесты:
   `SwitchFinderTest`, `SemanticGateTest`, `ActivityScannerTest`,
   `ScanOrchestratorTest`, `AdaptiveCatalogTest` (семантика + фолбэки),
   актуализация `SimpleStepsTest`.
5. `feat: diag levels + docs sync` — блоки 7–8.

## Правила

- Комментарии русские краткие; логи английские; UI-строки только
  `strings.xml` (7 локалей, паритет ключей).
- Поведение варианта cn_hyperos не ломать.
- Лицензию, монетизацию и ядро (Оптимизация/Про-режим) не трогать.
- Критерий приёма: план ≈ 20 шагов на типичном глобальном устройстве,
  ноль ложных нажатий при `low_confidence`.
