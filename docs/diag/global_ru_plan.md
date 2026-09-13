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