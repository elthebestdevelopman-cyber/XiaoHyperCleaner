# Handoff Flash — продолжение мастер-задачи v2

Дата: 2026-09-16. Объём: компактный контекст для нового треда.

## Состояние

Коммиты 0–2 + WIP + каталог сделаны:
- `a5eee0f` docs: master-plan artifacts (аудит, промт v2, черновик переводов)
- `d934fb1` chore: pre-master WIP
- `cf144e7` fix: overlay continuity (P0)
- `bcc3c4f` chore: pre-handoff WIP (overlay v2: heartbeat, phase protection)
- `12625ef` feat: semantic steps catalog (manual draft)

**Чекпоинт оверлея НЕ пройден** (прогон rmu2z9m3h): оверлей визуально
исчезал на шаге msa и ещё несколько раз при тихом логе attach/detach.
Пользователь тапов не совершал (отсутствие touch-логов ожидаемо).
Текущая инструментация (attach/detach) не ловит «перекрывается/невидим».
Результаты прогона: 6/20 pass, failed=14, skipped=6;
sys_recommendations OK→FAIL verify_failed (устранён ложный успех).

## Очередь коммитов (3–6)

### Коммит 3 — feat: discovery engine (+ overlay v2 завершение)
Спека в `docs/diag/master_prompt_v2.md` → Блок 2 (ActivityScanner,
SwitchFinder, ComponentVerifier, ScanOrchestrator) + Блок 6
(checked_before в RestoreSnapshot). Overlay v2 уже частично в
`bcc3c4f` — завершить: heartbeat-строки в логе при прогоне,
дубли PERMISSIONS-фазы, caller-логи.

### Коммит 4 — feat: semantic steps + plan pre-filter
Спека → Блоки 3–5 + Блок 3.5 (ConsentWallHandler) + Блок 3.6
(прозрачность notif_*). `semantic_steps.json` уже в
`12625ef` — подключить через SemanticCatalog.
СТОП после коммита 4: device-run с экран-рекордером.

### Коммит 5 — feat: diag levels + docs sync
Спека → Блок 7 (DiagnosticLevel OFF/COMPACT/FULL, purge 3/7d/50MB)
+ Блок 8 (документация).

### Коммит 6 — refactor: neutral wording
Спека → Блок 3.7 (нейтральная лексика: «реклама» →
«рекомендации/промо-контент», словарь замены, WordingTest).
НЕ трогать машинные поля, stepId, логи, имена классов.

## Принятые решения (финальные)

1. `screenMarkers` и `searchTexts` РАЗДЕЛЕНЫ; `searchTexts` =
   deprecated-fallback на один релиз.
2. `localeCoverage` = 7 локалей (ru,en,es,zh,hi,pt,id); переводы
   в `semantic_steps.json`.
3. `home_suggestions` — plan-time skip через `resolveHomePackage()`
   в PlanBuilder; runtime-ветку удалить.
4. `SequenceKind` + `OVERFLOW_MENU` (для sys_recommendations).
5. 7 тапов по `textVersion` в MenuDialog → `diagLevelOverride`
   (release FULL opt-in).
6. Purge-триггеры: старт приложения + `captureAndSaveSnapshot`.

## Указатели на файлы

- `docs/SUMMARY.md` — сводка всего сделанного (обновить после коммитов)
- `docs/diag/global_ru_plan.md` — план Global RU + результаты прогонов
- `docs/diag/master_plan_audit.md` — полный аудит (реестр hide/show,
  миграция 26 шагов, конфликты, решения)
- `docs/diag/master_prompt_v2.md` — полный промт v2 с аддендумами
- `app/src/main/assets/catalog/semantic_steps.json` — семантический
  каталог (26 шагов, 7 локалей, создан вручную)
- `diag-dumps/before/` — baseline-дампы прогона rmu2z9m3h (точечное
  чтение: grep StepDiag/DiagnosticSnapshot/OverlaySvc)

## Критерии приёмки (финальный прогон M2102J20SG V130 Global RU)

1. Оверлей не исчезает весь STEPS-фазу (heartbeat в логе).
2. Логи: `discover:/semantic:/switch:/scan:` — новые префиксы.
3. Plan size ≈ 20; `app_not_installed` и `home_not_miui` вне плана.
4. Первый прогон: скан + отключение в одной сессии; второй — cache hit.
5. Откат возвращает `checked_before` каждого тумблера.
6. `diag/` не растёт бесконечно (purge работает).
7. В user-facing строках нет «реклама/advert» (WordingTest).

## Гейт после каждого коммита

```powershell
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```
