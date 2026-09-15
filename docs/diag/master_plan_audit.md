# Интеграционный аудит — мастер-задача v2

Дата: 2026-09-16. Режим: read-only аудит перед реализацией. Статус: принят,
решения зафиксированы (см. §6).

## 0. Исходные допущения

Мастер-промт в репозитории отсутствует (поиск по `master_plan`, `мастер-промт`,
`семантическ`, `keywords`, `screenMarkers` — 0 попаданий). Семантика блоков
восстановлена из текста задачи и согласована с уже реализованным
(`docs/SUMMARY.md`, `docs/diag/global_ru_plan.md`). Мастер-промт оформлен
отдельно: `docs/diag/master_prompt_v2.md`.

Готово на момент аудита:

- `RestoreSnapshot` + `addSimpleToggledStep` (`PreferencesManager.kt`),
  `reverseSimpleToggles` (`AdbEnablerService.kt`).
- `AdaptiveCatalog` с variants (cn_hyperos / global_ru) и `merge*`-методами.
- `findAndToggleSwitch` + `tapActionButton` (variant-aware fallback).
- `resetToHome` через `GLOBAL_ACTION_HOME` (`SimpleRunner.kt`).
- `DiagnosticSnapshotManager` + `tools/diag-diff.ps1`.
- 26 шагов в `SimpleSteps.ALL`; `isCheckedCompat()` в `SimpleRunner.kt`.

Не реализовано (предмет мастер-задачи):

- `checked_before` в момент тумблера (откат инвертирует `original.targetChecked`,
  а не фактическое состояние — баг, блок 6).
- `ActivityScanner`, `SwitchFinder`, `ComponentVerifier`, `ScanOrchestrator`,
  `awaitScreen` по маркерам экрана.
- `onDetachedFromWindow` watchdog, гейт «оверлей прикреплён», touch-лог.
- `DiagnosticLevel` (OFF/COMPACT/FULL), авто-очистка `diag/`.
- Префильтр плана (`PlanBuilder`).

## 1. Вызовы оверлея: реестр

`showOverlay` как функции нет — есть `OverlayController.hide/startAutomation/
updateAutomation/updateStatus/setBlocking/showResult/hint/showManualPointer`.
Все места изменения видимости/состояния:

| № | Файл | Строка | Вызов | Фаза | Нарушение «один show/hide на фазу» |
|---|---|---|---|---|---|
| 1 | `SimpleModeController.kt` | 120 | `OverlayController.hide` | destroy | дубль с № 5 |
| 2 | `SimpleModeController.kt` | 171 | `OverlayController.hide` | start (PERMISSIONS вход) | мёртвый вызов до show |
| 3 | `SimpleModeController.kt` | 540 | `OverlayController.showResult` | DONE | ок |
| 4 | `SimpleModeController.kt` | 547 | `OverlayController.startAutomation` | STEPS | ок |
| 5 | `SimpleModeController.kt` | 706 | `OverlayController.hide` | reset | ок (финальный cleanup) |
| 6 | `SimpleModeController.kt` | 708 | `permissionFlow.hideOverlay()` | reset | дубль с № 5 |
| 7 | `PermissionFlowManager.kt` | 283 | `OverlayController.hide` | любая (обёртка) | — |
| 8 | `SimpleModeController.kt` | 139 | `permissionFlow.hideOverlay()` | PERMISSIONS→advance | hide без предшествующего show |
| 9 | `SimpleModeController.kt` | 147 | `permissionFlow.hideOverlay()` | PERMISSIONS→onResume | дубль с № 8 |
| 10 | `MainViewModel.kt` | 117 | `OverlayController.hide` | cancel через оверлей | ок |
| 11 | `MainViewModel.kt` | 123 | `OverlayController.hide` | закрытие результата | ок |
| 12 | `MainViewModel.kt` | 187 | `OverlayController.hide` | DevModeRequired | ок |
| 13 | `MainViewModel.kt` | 202 | `OverlayController.hide` | onCleared | ок |
| 14 | `MainViewModel.kt` | 451 | `OverlayController.hide` | closeSimpleMode | дубль с № 15 |
| 15 | `MainActivity.kt` | 305 | `OverlayController.hide` | onPause/onResume | может сбить automation-оверлей |

Нарушений 7 из 15: № 1, 2, 6, 8, 9, 14, 15. Правило «один show на фазу /
один hide на результат» не соблюдается: hide раскидан по 5 сайтам.

Состояние `OverlayController` (singleton `object`): `onCancel`, `onResultClose`
(strongly-held listeners), вид хранится в `OverlayService` (`root`, `isBlocking`).
Поля `isAttached` нет — гейт «оверлей прикреплён» без доработки невозможен.

Мёртвый код: `hint`, `showManualPointer` (подсказки-стрелки отключены,
вызовы — no-op).

## 2. Текущий формат шагов → семантический

Текущий `SimpleSteps.Step` (Kotlin): `id, titleRu/En, descRu/En, intents,
searchTexts, targetChecked, manualHintRu/En, riskLevel, warningRu/En, drillPath,
launchPackage, requiredPackages, actionType (TOGGLE|CLEAR_DATA_DECLINE),
additionalToggles, confirmTexts, confirmWaitMs, preDrillWaitMs,
swipeUpAfterLaunch, tapFallbackTexts, forceStopBeforeLaunch`.
`adaptive_catalog.json` дополняет дефолты через `merge*` (два источника истины).

26 `id` (порядок = `SimpleSteps.ALL`): `msa, sys_recommendations,
ads_personalization, ux_program, carousel, home_suggestions, browser_sys,
music_sys, messages_sys, security_sys, cleaner, downloads, themes, getapps,
mivideo, shareme, filemanager, appvault_services, appvault_about, notif_msa,
notif_gamecenter, notif_appvault, notif_themes, notif_getapps, notif_browser,
notif_mivideo`.

Целевая схема (мастер-промт): `{id, title{7}, keywords{7}, screenMarkers{7},
actionType: toggle|tap_confirm|sequence|skip, sequenceKind?, confirmTexts{7},
tapFallbackTexts{7}, consentTexts{7}?, overflowMenuLabels{7}?, confirmWaitMs?,
maxConsentIterations?, launchPackage?, skipReason?, fallbackDrillPath[][],
safe, localeCoverage[]}`.

Миграция:

- `id` — 1:1 (26/26).
- `actionType`: TOGGLE→`toggle`; CLEAR_DATA_DECLINE→`sequence/CLEAR_DATA_DECLINE`;
  tap-fallback+confirm (ads_personalization)→`tap_confirm`; consent-стена
  Тем→`sequence/CONSENT_WALL`; 10s-confirm MSA→`sequence/DELAYED_CONFIRM`;
  ⋮-маршрут (sys_recommendations)→`sequence/OVERFLOW_MENU`; runtime-skip
  (home_suggestions, getapps, messages_sys, appvault_*)→`skip`.
- `confirmTexts`, `confirmWaitMs`, `launchPackage` — 1:1.
- `screenMarkers` — новое поле; сейчас `searchTexts` двойного назначения
  (и `awaitScreen`, и поиск switch). Разделяем (решение §6).
- `keywords` — новое; текущие `searchTexts` плоские, переводов на
  hi/pt/id нет в коде (есть в `strings.xml`, но не для названий переключателей).
- `safe` — из `riskLevel` (SAFE→true).
- `drillPath`/`tapFallbackTexts` из старых вариантов → `fallbackDrillPath`
  как подсказки сканеру, не рабочая логика.
- Не мигрируют (остаются в Kotlin): `intents`, runtime-параметры
  (`forceStopBeforeLaunch`, `preDrillWaitMs`, `swipeUpAfterLaunch`),
  `requiredPackages` (уходит в префильтр плана).

## 3. Карточки файлов по блокам

| Блок | Файлы | Характер |
|---|---|---|
| 1 Оверлей | `service/OverlayController.kt` (+isAttached, caller-лог), `service/OverlayService.kt` (onDetachedFromWindow-watchdog, фон #99000000, FLAG_NOT_FOCUSABLE, touch-лог), `data/SimpleModeController.kt` (убрать промежуточные hide), `ui/MainViewModel.kt`, `ui/MainActivity.kt`, `res/values*/strings.xml` × 7 (4 строки) | 5+ файлов, структурная |
| 2 Discovery | новые `data/scanner/{ActivityScanner,SwitchFinder,ComponentVerifier,ScanOrchestrator}.kt`, кэш `act_cache_<pkg>_<versionCode>_<miuiIncremental>` («unknown»-суффикс без incremental), интеграция в `SimpleRunner`/`AdbEnablerService` | 4 новых + 2 модификации |
| 3 Семантика | `assets/catalog/semantic_steps.json` (создан вручную), `data/{SemanticStep,SemanticCatalog}.kt`, deprecate `SimpleSteps.ALL` и `searchTexts` (fallback на один релиз) | 2 новых + модификации |
| 4 Gate | `data/SemanticGate.kt` (keyword И switch И screenMarkers → act; иначе `skipped=low_confidence`) | 1 новый |
| 5 План | `data/PlanBuilder.kt` (installed-фильтр + home-skip plan-time через `resolveHomePackage()`; runtime-ветка home_not_miui удаляется; total = размер плана) | 1 новый + 2 модификации |
| 6 Откат | `OptimizationEngine.kt` (`RestoreSnapshot.simpleToggleStates`), `SimpleRunner.kt` (`checked_before` в момент тумблера), `AdbEnablerService.kt` (reverse по `checked_before`), `PreferencesManager.kt` (миграция: нет поля → прежняя логика) | 4 модификации |
| 7 Диагностика | `util/DiagnosticSnapshotManager.kt` (DiagnosticLevel OFF/COMPACT/FULL, COMPACT ≤60 узлов без bounds, purge 3 прогона / 7 дней / 50 МБ), `PreferencesManager.kt` (`diagLevelOverride`, 7 тапов по версии в `MenuDialog`), `ui/components/MenuDialog.kt` | 3 модификации |
| 8 Документация | `docs/diag/global_ru_plan.md`, `docs/SUMMARY.md`, `CHANGELOG.md` | 3 файла |

Тесты (коммит 4): `SwitchFinderTest`, `SemanticGateTest`, `ActivityScannerTest`,
`ScanOrchestratorTest`, расширение `AdaptiveCatalogTest` (семантика +
fallback всех вариантов), актуализация `SimpleStepsTest`.

## 4. Коммиты

Гейт после каждого: `./gradlew :app:assembleDebug :app:lintDebug
:app:testDebugUnitTest`.

| Коммит | Состав | Оценка |
|---|---|---|
| 0 docs | master_plan_audit.md, master_prompt_v2.md, locale_translations_draft.md | ~0 строк кода |
| 1 chore: pre-master WIP | 7 uncommitted файлов без `semantic_steps.json` | +147/−31 |
| 2 fix: overlay continuity (P0) | блок 1; стоп после коммита — device-check | ~400 строк |
| 3 feat: discovery engine + checked_before | блоки 2+6 | ~1500 строк |
| 4 feat: semantic steps + plan pre-filter | блоки 3–5 + тесты | ~2000 строк |
| 5 feat: diag levels + docs sync | блоки 7–8 | ~600 строк |



## 5. Конфликты и риски

### Критические (разрешены решениями §6)

1. `screenMarkers` vs `searchTexts` (двойное назначение в текущем коде).
2. Переводы keywords на hi/pt/id — нет в коде.
3. `checked_before` отсутствует: при `already_done` реверс всё равно трогает
   переключатель.
4. `home_suggestions`: план по `requiredPackages` пропускает шаг без пакетов —
   конфликт префильтра и runtime home-skip.

### Важные (зафиксированы в промте/решениях)

5. Consent-стена Тем → `sequence/CONSENT_WALL`, `maxConsentIterations ≤ 3`.
6. MSA 10s-confirm → `sequence/DELAYED_CONFIRM`.
7. 7 тапов по `textVersion` в `MenuDialog` → `diagLevelOverride` (release FULL).
8. Purge-триггер: при старте приложения И при `captureAndSaveSnapshot`.
9. Промежуточные `hideOverlay` № 8/9 удаляются безопасно (подсказки-стрелки —
   no-op).

### Информационные

10. `SimpleSteps.ALL` и `searchTexts` — deprecate, удаление через релиз.
11. `hint`/`showManualPointer` — мёртвый код, удаляется в коммите 2.
12. Кэш-ключ `act_cache_<pkg>_<versionCode>_<miuiIncremental>` — при
    отсутствии `miuiIncremental` суффикс «unknown».
13. cn_hyperos-поведение не ломать: `fallbackDrillPath` обязателен для
    всех вариантов.

## 6. Принятые решения (финальные, 2026-09-16)

1. `screenMarkers` и `searchTexts` разделены; `searchTexts` —
   deprecated-fallback на один релиз (политика как у `SimpleSteps.ALL`).
2. `localeCoverage` = 7 локалей (ru,en,es,zh,hi,pt,id); переводы уже в
   `assets/catalog/semantic_steps.json` (создан вручную) — только валидация
   и подключение в коммите 4, не пересоздание.
3. `home_suggestions` — plan-time skip через `resolveHomePackage()` в
   `PlanBuilder`; runtime-ветка удаляется в коммите 4 как мёртвая.
4. Текущий WIP (7 файлов) — коммит 1 `chore: pre-master WIP` перед коммитом 2;
   `semantic_steps.json` в него не входит.

## 7. Валидация `semantic_steps.json` (2026-09-16)

- JSON парсится (проверено `ConvertFrom-Json`).
- 26 `id` совпадают с `SimpleSteps.ALL` один-в-один (порядок и состав).
- `actionType`: toggle×16, sequence×3 (msa/DELAYED_CONFIRM,
  sys_recommendations/OVERFLOW_MENU, themes/CONSENT_WALL), tap_confirm×1,
  skip×6 — все в допустимом множестве.
- `sequenceKind` ∈ {CONSENT_WALL, DELAYED_CONFIRM, OVERFLOW_MENU};
  enum `SequenceKind` расширен значением `OVERFLOW_MENU` под промт.
