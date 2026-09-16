# План: поддержка Global RU (MIUI V130) в Simple Mode

Статус: утверждён (таблица маппинга + дизайн Фаз 2-3 + 6 обязательных правок).

## Fingerprint целевого устройства
- ro.product.model: M2102J20SG
- ro.build.version.sdk: 31
- ro.miui.ui.version.name: V130
- ro.build.version.incremental: V13.0.5.0.SJURUXM
- region: RU (GLOBAL), язык: ru, HyperOS: false

## Корневые причины (по 26 дампам)
1. resetToHome() (ACTION_MAIN+CATEGORY_HOME) открывает resolver «Главный экран по умолчанию» -> 13 app-шагов застревают.
2. Навигация настроек приземляется на «Конфиденциальность» (Privacy); на Privacy есть пункт «Реклама».
3. findSwitchNear не находит CheckBox, вложенный в sibling-контейнер (widget_frame) -> switch_not_found (уведомления, Карусель).
4. forceStopPackage (рефлексия) не работает без привилегий.
5. MIUI-интенты отсутствуют на Global; fallback уводит не туда (themes/getapps -> промо MSA).

## Таблица маппинга (stepId -> факт. экран -> путь global_ru)
| stepId | Факт. экран | Причина | Путь/маркеры global_ru |
|---|---|---|---|
| msa | Конфиденциальность | drill_failed | Конфиденциальность -> Реклама -> «Персонализация рекламы»/MSA |
| sys_recommendations | Конфиденциальность | switch_not_found | Конфиденциальность -> Реклама |
| ads_personalization | Конфиденциальность | drill_failed | Конфиденциальность -> Реклама |
| ux_program | Конфиденциальность | drill_failed | доп. дамп: «Программа улучшения качества» (уточнить) |
| carousel | Карусель обоев (открылся) | switch_not_found | навигация ОК; фикс детекта; «Обновлять через мобильный Интернет» |
| home_suggestions | Главный экран по умолчанию | drill_failed | фикс resetToHome -> Рабочий стол |
| browser_sys | Главный экран по умолчанию | drill_failed | фикс resetToHome -> com.mi.globalbrowser -> Профиль -> Ещё -> Дополнительно |
| music_sys | Главный экран по умолчанию | drill_failed | фикс resetToHome -> com.miui.player -> Ещё -> Расширенные настройки |
| security_sys | Главный экран по умолчанию | switch_not_found | фикс resetToHome -> com.miui.securitycenter -> Ещё |
| cleaner | Главный экран по умолчанию | drill_failed | фикс resetToHome -> securitycenter -> Очистка -> Ещё |
| downloads | Главный экран по умолчанию | switch_not_found | фикс resetToHome -> downloads -> Ещё/Настройки |
| mivideo | Главный экран по умолчанию | drill_failed | фикс resetToHome -> com.miui.videoplayer -> Профиль -> Ещё |
| shareme | Главный экран по умолчанию | drill_failed | фикс resetToHome -> com.xiaomi.midrop -> Ещё -> О приложении |
| filemanager | Главный экран по умолчанию | clear_button_not_found | фикс resetToHome -> CLEAR_DATA через App Info |
| themes | Промо MSA | drill_failed | launch com.android.thememanager напрямую (не через MSA) |
| getapps | Промо MSA | app_not_installed | не установлен -> skipped |
| messages_sys | Главный экран по умолчанию | app_not_installed | skipped |
| appvault_services | Главный экран по умолчанию | app_not_installed | skipped |
| appvault_about | Главный экран по умолчанию | app_not_installed | skipped |
| notif_msa | Главный экран по умолчанию | timeout | фикс resetToHome + notification intent |
| notif_gamecenter | Корень Настроек | timeout | корень достигнут; notification intent gamecenter |
| notif_appvault | Корень Настроек | app_not_installed | skipped |
| notif_themes | Уведомления Тем | switch_not_found | навигация ОК; фикс детекта CheckBox |
| notif_getapps | Уведомления Тем (stale) | app_not_installed | skipped |
| notif_browser | Уведомления Тем (stale) | switch_not_found | notification intent com.mi.globalbrowser |
| notif_mivideo | Уведомления Mi Видео | switch_not_found | навигация ОК; фикс детекта CheckBox |

Корень Настроек (маркеры): «Поиск настроек», «О телефоне», «Настройки», «SIM-карты и мобильные сети», «Wi-Fi», «Bluetooth».

## Фаза 2 — системные фиксы
- resetToHome -> performGlobalAction(GLOBAL_ACTION_HOME) (не вызывает resolver).
- resetSettingsToRoot -> ACTION_SETTINGS, затем подтверждение КОРНЯ по совпадению >=2 маркеров ОДНОВРЕМЕННО; если <2 -> GLOBAL_ACTION_BACK (до 5 раз) с повторной проверкой.
- findSwitchNear -> искать switch-like узел в ПОДДЕРЕВЕ ближайшего clickable-предка (рекурсивно), а не только среди прямых соседей.
- forceStopPackage -> удалить (Simple без привилегий); fresh launch через GLOBAL_ACTION_HOME.
- DirectIntentNavigator -> Global-ветка: AOSP/компонентные интенты вместо miui.intent.action.*.
- app_not_installed -> статус skipped с причиной (не failure).
- takeScreenshot onFailure -> отдельный коммит fix: корректный executor + ретрай/лог errorCode.

## Фаза 3 — вариантность каталога
- adaptive_catalog.json -> { "variants": { "cn_hyperos": <текущее байт-в-байт>, "global_ru": <новое> } }.
- Диспетчер: global_ru = isHyperOs==false && region != CN && Locale.getDefault().language == "ru".
  Версии MIUI/sdk НЕ влияют на выбор (Global V14 тоже попадает), пишутся ТОЛЬКО в лог.
  Ключ диспетчеризации включает язык устройства; нерусские локали Global -> cn_hyperos (дефолт) — зафиксировано комментарием.
- На старте прогона: AppLog.i("SimpleMode variant=<v> fingerprint=model=<..> sdk=<..> miui=<..> region=<..> lang=<..>") (англ.).
- В fingerprint-блок снапшота добавить "catalogVariant".

## Файлы
1. service/SimpleRunner.kt — resetToHome, resetSettingsToRoot, findSwitchNear, forceStop, лог variant+fingerprint.
2. data/DirectIntentNavigator.kt — Global-интенты.
3. assets/catalog/adaptive_catalog.json — variants.
4. data/AdaptiveCatalog.kt — selectVariant, variantRoot, cache-ключи с variant, лог.
5. util/DiagnosticSnapshotManager.kt — catalogVariant в fingerprint.
6. test/java/.../AdaptiveCatalogTest.kt — выбор варианта + merge.

## Коммиты и гейты (после каждого: ./gradlew :app:assembleDebug lintDebug)
1. docs: план (этот файл)
2. chore: реструктуризация каталога (cn_hyperos байт-в-байт)
3. feat: фиксы раннера и интентов
4. feat: диспетчер вариантов (+ лог + catalogVariant в снапшоте)
5. test: AdaptiveCatalogTest
6. fix: скриншоты (takeScreenshot onFailure)

## Результаты второго diagnostic run (после screenshot-fix, до тюнинга)

- Скриншоты пишутся корректно (`onFailure` отсутствует).
- `diag-diff.ps1` не применим: прежняя папка `before` отсутствует, сравнение с baseline ведётся вручную по 26 шагам.
- Из 26 baseline-шагов оставлено **19 failure-dumps**; 7 шагов дамп не породили — интерпретируем как `pass`.

| # | stepId | baseline | now | reason (new run) | note |
|---|--------|----------|-----|------------------|------|
| 1 | msa | fail | fail | switch_not_found | Google GMS: экран ad-ID, переключателя нет |
| 2 | sys_recommendations | fail | pass | — | дамп отсутствует |
| 3 | ads_personalization | fail | fail | switch_not_found | Google GMS: есть кнопка «Удалить рекламный идентификатор» |
| 4 | ux_program | fail | fail | drill_failed | достигнута Конфиденциальность, нужен 3-й уровень |
| 5 | carousel | fail | fail | switch_not_found | переключатель называется «Включить» |
| 6 | home_suggestions | fail | fail | drill_failed | resetToHome требует доработки |
| 7 | browser_sys | fail | fail | drill_failed | fallback в App Info |
| 8 | music_sys | fail | fail | drill_failed | fallback в App Info |
| 9 | security_sys | fail | fail | switch_not_found | fallback в App Info |
| 10 | cleaner | fail | fail | drill_failed | fallback в App Info |
| 11 | downloads | fail | fail | switch_not_found | fallback в App Info |
| 12 | mivideo | fail | fail | drill_failed | fallback в App Info |
| 13 | shareme | fail | fail | drill_failed | fallback в App Info |
| 14 | filemanager | fail | pass | — | дамп отсутствует |
| 15 | themes | fail | fail | drill_failed | MSA-промо / App Info |
| 16 | getapps | fail | skipped | app_not_installed | логируется skipped |
| 17 | messages_sys | fail | skipped | app_not_installed | логируется skipped |
| 18 | appvault_services | fail | skipped | app_not_installed | логируется skipped |
| 19 | appvault_about | fail | skipped | app_not_installed | логируется skipped |
| 20 | notif_msa | fail | pass | — | дамп отсутствует |
| 21 | notif_gamecenter | fail | pass | — | дамп отсутствует |
| 22 | notif_appvault | fail | skipped | app_not_installed | логируется skipped |
| 23 | notif_themes | fail | pass | — | дамп отсутствует |
| 24 | notif_getapps | fail | skipped | app_not_installed | логируется skipped |
| 25 | notif_browser | fail | pass | — | дамп отсутствует |
| 26 | notif_mivideo | fail | pass | — | дамп отсутствует |

**Итог второго run:** pass 7 | skipped 6 | fail 13.

## Что было изменено в рамках global_ru

1. **adaptive_catalog.json / global_ru:**
   - Убраны неверные override для `msa` и `sys_recommendations` (шли на Google-экран «Реклама» без нужного переключателя).
   - `ads_personalization`: сохранён путь через «Реклама», добавлены `tapFallbackTexts` («Удалить рекламный идентификатор») и `confirmTexts` («Удалить», «ОК»).
   - `carousel`: в `searchTexts` добавлено «Включить» (имя реального переключателя на экране).
   - `ux_program`: `replaceDrillPath` с тремя уровнями, последний — «Дополнительные настройки».
2. **SimpleSteps + AdaptiveCatalog + SimpleRunner:**
   - `Step.tapFallbackTexts` — список текстов кнопки-действия.
   - `AdaptiveCatalog.mergeTapFallbackTexts()` — variant-aware merge, пустой для `cn_hyperos`.
   - `SimpleRunner.findAndToggleSwitch`: если переключатель не найден и `tapFallbackTexts` не пуст, тапаем кнопку-действие и подтверждаем диалог.
3. **AdaptiveCatalogTest:**
   - Тест замены drillPath: `msa` → `ads_personalization`.
   - Добавлен тест `tap fallback present for global_ru ads and empty for cn_hyperos`.

## План следующего device-run

- **Устройство / fingerprint:** M2102J20SG, MIUI V13.0.5.0.SJURUXM, ru, Global, HyperOS=false.
- **Сценарий:** чистый прогон Simple Mode с текущим `global_ru` каталогом и tap-fallback.
- **Ожидаемые изменения:**
  - `ads_personalization` — переходит из `switch_not_found` в `tapped_fallback` / success.
  - `carousel` — должен найти переключатель «Включить» и успешно отключить.
  - `ux_program` — должен дойти до «Дополнительные настройки» и найти «Программу улучшения качества».
  - `msa` / `sys_recommendations` — вернутся на путь `cn_hyperos` (Authorization & revocation / Приложения → Ещё); проверить, что false-positive toggle исчез.
- **Метрики сравнения:**
  - Снимки `diag-dumps/` до и после — сравнить через `tools/diag-diff.ps1`.
  - Цель: сократить fail с 13 до ≤5 (app-launch шаги с fallback в App Info могут потребовать отдельной настройки intent).
  - В логах не должно быть `onFailure` у screenshot; `app_not_installed` — skipped.

## Процедура прогона (чистка diag)

1. **Подготовка устройства:**
   - Подключить USB, убедиться что `adb devices` видит устройство.
   - Включить AccessibilityService для XiaoHyperCleaner.
   - Закрыть все приложения, вернуться на домашний экран.

2. **Очистка старых diag-дампов:**
   ```powershell
   # Удалить старые дампы из diag-dumps/before/ и diag-dumps/after/
   Remove-Item -Path "diag-dumps/before/*.json" -Force
   Remove-Item -Path "diag-dumps/after/*.json" -Force
   # Очистить логи
   Remove-Item -Path "diag-dumps/before/xhc.log.txt" -Force
   Remove-Item -Path "diag-dumps/after/xhc.log.txt" -Force
   ```

3. **Сбор before-дампов (если нужно сравнение):**
   - Запустить приложение, выполнить Simple Mode.
   - Экспортировать логи: `adb logcat -d -s SimpleRunner:I AdbEnablerService:I StepDiag:I > diag-dumps/before/xhc.log.txt`
   - Скопировать JSON-дампы: `adb pull /storage/emulated/0/Android/data/com.xiaohypercleaner/files/diag-dumps/ diag-dumps/before/`

4. **Запуск прогона:**
   - Запустить Simple Mode через UI приложения.
   - Дождаться завершения всех шагов.
   - Проверить логи: `adb logcat -s SimpleRunner:I AdbEnablerService:I StepDiag:I DiagnosticSnapshot:I`

5. **Экспорт after-дампов:**
   ```powershell
   adb logcat -d -s SimpleRunner:I AdbEnablerService:I StepDiag:I > diag-dumps/after/xhc.log.txt
   adb pull /storage/emulated/0/Android/data/com.xiaohypercleaner/files/diag-dumps/ diag-dumps/after/
   ```

6. **Сравнение и анализ:**
   - Запустить `tools/diag-diff.ps1` для сравнения before/after.
   - Проверить:
     - Количество pass/skipped/fail до и после.
     - Отсутствие `home_not_miui` для устройств с MIUI-лаунчером.
     - Отсутствие `drill_failed` для themes (должен быть consent-цикл).
     - Успешные скриншоты: в логах `captureScreenshot: saved ... (XXKB)`.
     - Toggled-логи: `toggled: text='...' desc='...' bounds=[...] step=...`.

7. **Критерии успешного прогона:**
   - `ads_personalization` — pass (tapped_fallback или toggled).
   - `carousel` — pass (toggled с «Включить»).
   - `ux_program` — pass (drill 3 уровня до «Дополнительные настройки»).
   - `themes` — pass (consent-цикл + toggled).
   - `home_suggestions` — pass или skipped с `home_not_miui` (если лаунчер не MIUI).
   - App-шаги (browser_sys, music_sys и др.) — не падают в App Info fallback.
   - Скриншоты сохраняются без ошибок `onFailure`.
---

## Прогон rmu2z9m3h (2026-09-16) — результаты и чекпоинт

- **Метрики:** 6/20 pass, **failed=14**, **skipped=6**.
- `sys_recommendations`: OK→FAIL `verify_failed` — устранён ложный успех
  (ранее фиксировался как success без реального переключения).
- **Чекпоинт оверлея НЕ пройден:** оверлей визуально исчезал на шаге `msa`
  и ещё несколько раз при тихом логе фазы STEPS (нет `overlay hidden`).
  Окно не удалялось `hide()`, а становилось невидимым/перекрывалось;
  attach/detach-логи в прогоне отсутствуют (инструментация не ловит).
- Пользователь тапов не совершал — отсутствие touch-логов ожидаемо.
- Базовая линия сохранена в `diag-dumps/before/` (26 JSON + PNG + `xhc.log.txt`).

### План следующего прогона

- Устройство: M2102J20SG, MIUI V13.0.5.0.SJURUXM, ru, Global, HyperOS=false.
- Сценарий: чистый прогон Simple Mode после коммита 4, **запись экрана
  встроенным рекордером** для диагностики оверлея.
- В логе должны появиться строки heartbeat/attach после коммита 3:
  `overlay: heartbeat recovered reason=...`, `overlay: viewAttachedToWindow`,
  `overlay: viewDetachedFromWindow`.
- Сравнение before/after через `tools/diag-diff.ps1`.