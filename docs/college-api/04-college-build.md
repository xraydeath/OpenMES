# 04. Сборка колледжа (`po`) и её отличия от школьной

Источник — декомпилированный бандл Hermes (`decompiled.js`). В скобках указаны номера строк.
Все переключения по сборке идут через `selectByBuild({default, moscow, region, po})`. Приложение «Колледж МЭШ» — это сборка `po`.
Ключи OAuth, токены и личные данные в этот файл намеренно не включены.

---

## 1. Хосты (модуль environmentSelectors, ~1505800–1506620)

В `po` хосты берутся из выбранного региона: `getRegionHostByType(state, key)` → `[key].prod|test|dev.host`. Если значения нет, используются значения по умолчанию.

| Селектор | Школа (default) | Колледж (po) |
|---|---|---|
| getDnevnikHost | https://dnevnik.mos.ru | `apiBaseUrl` региона, иначе dnevnik.mos.ru |
| getSchoolHost | https://school.mos.ru | `apiSchoolUrl` региона, иначе school.mos.ru |
| getHost / getApiVersion (mobile API) | remote config `mapi_host_config` (по умолчанию `https://school.mos.ru/api/family/mobile` + `/v1`) | хост и версия региона |
| getEjHost | `/api/ej` | **`/api/profeducation`** |
| getIsppHost (питание/ИСПП) | `/api/family/ispp` | **`/api/profeducation/family/ispp`** |
| getEventCalendarsHost | `/api/eventcalendar/` | **`/api/profeducation/eventcalendar/`** |
| getMeshTokenUrl (профиль) | `/api/ej/acl/...` | **`/api/profeducation/acl/v1/mod-acl/users/profile_info`** |
| getAUPDTokenUrl | schoolUrl + `/v3/auth/sudir/auth` | то же |
| getLogoutUrl | logout login.mos.ru → redirect на dnevnik | logout login.mos.ru → redirect на school.mos.ru |
| getSupportUrl | schoolUrl + `/fos/familymp` (moscow) / `fos_config` (region) | schoolUrl + **`/fos/mp_college`** |
| getSchoolApiHost | schoolUrl + `/api` | то же |
| getCertificatesHost | `/api/certificates/family/v1` | то же (без переключения) |
| getLRSHost | `/api/lrs` | то же |
| getPortfolioHost | `/portfolio` | то же |
| Файлы ДЗ, HomeworkFilesContainer (3057865) | `/ej` | `/profeducation` |

Итог: журнальные эндпоинты (`/api/ej/...` в школе) в колледже идут через **`/api/profeducation/...`**, пути внутри те же.

---

## 2. Заголовки и идентификаторы подсистемы

### 2.1. Общие заголовки

Их ставят axios-интерцептор `commonHeaders` (1593380) и `apiRequest` (1613437):

| Заголовок | Школа | Колледж |
|---|---|---|
| `client-type` | `diary-mobile` | `diary-mobile` |
| `X-Mes-Subsystem` (X_MES_SUBSYSTEM, 1506887) | `familymp` | **`familypom`** |
| `X-Mes-RoleId` (getProfileRoleId) | студент `1`, родитель `2` | **студент `32`**, родитель `2` |
| `Profile-id` | id текущего профиля | то же |
| `Accept-Language` | `ru` | `ru` |
| `Accept` / `x-row-limit` | только в apiRequest | то же |

Сгенерированные клиенты передают `x-mes-subsystem` параметром:
- FamilyApi, например `/attendance` (~1422xxx);
- News/Stories (~1536xxx): `X-Mes-Subsystem` + `X-Mes-Role`.

Явно X_MES_SUBSYSTEM подставляется в:
- getUserProfileIdSaga;
- `/consents/consent_scan`;
- `/app/cultural/pdf` (useDownloadMuseumsPdf);
- `/api/avatarmanagement/v1/`;
- `/api/fcm/v1`;
- `/homeworks/.../attachment`;
- `/api/portfolio/app/attachment`;
- useDownloadSchedule.

### 2.2. Прочие константы, зависящие от сборки

| Константа (строка) | Школа | Колледж | Где используется |
|---|---|---|---|
| profileRoleIdMap.student (1293522) | `1` | `32` | X-Mes-RoleId |
| hasRoleInJwtToken — допустимые роли (1706607) | `['1','2']` | `['2','32']` | проверка JWT после АУПД |
| aupdCurrentRoleMap | student `4:1`, parent `4:2` | то же | — |
| SUBSYSTEM_ID (2039951) | `1` (DNEVNIK_SUBSYSTEM_ID) | **`30`** (SPO_SUBSYSTEM_ID) | `GET/PUT /api/usersettings/v1?name=settings_group_v1&subsystem_id=30`: updateSettingsSaga, useGetUserSettings (favoriteServices, favoriteCircles, favoriteCamps, UC_TTC_CARD_BALANCE) |
| MAIL_TARGET_ID (2650684) | `6` | **`17`** | `/api/notifications/usersettings/v1/user/setting`, PUT group_setting (is_subscribed, time_from, time_to); экраны NotificationsPage, PushNotificationsSettings |
| SERVICE_ID (771462) | `7` | **`59`** | объявления, баннер новостей (usePostAnnouncementsEvents, useNewsBanner, AnnouncementsAll/NewHome, useAnnouncementsRedirect) |
| STORIES_V3_SERVICE_ID (1846665) | `7` | `59` | сторис: списки, избранное, по id, patch |
| X_Mes_Role (1947227, 1950553) | `1` | `32` | usePostAnnouncementsEvents, usePostAppmetricaV1Event |
| PORTAL_ID (771468) | `familymp` | `familypom` | mospay, useGetMospayChargesUuid |
| usePostSudirAuthTt (1708758) | `FAMILYMP` | `FAMILYPOM` | postMospaynewSudirAuthTt (deeplink finances-home) |
| X_MES_APP_ID (2663101) | `familymp` | `familymp` (не меняется) | заказы питания, foodbox, preorder, правила заказов |
| Идентификатор питания (2969166) | `familymp_food` | `mp_college_food` | FeedingV3OrderListPageContainer |
| PostFilestorageV1FilesRequestSubsystemIdEnum (2624592) | `7` | `59` | sendDump (выгрузка логов) |
| Cookie `aupd_current_role` для чат-бота (1287350) | `7:1` (moscow) | `59:32` | webview чат-бота |
| FOS / поддержка | `familymp` | `mp_college` | useHelpButton (3114968), useAppeals (3115814), RateModalContainer (1833772) |
| Источник баннера событий (3152534) | `meshmp` | `appcollege` | EventsContainer |
| Представители (3138708) | `dnevnik_mp` | `dnevnik_spo_mp` | RepresentativeBanner |
| FAQ_URL (3120000) | — | https://school.mos.ru/help/instructions/mes-college/ | помощь |
| OAuth softwareId (1399841–1399894) | diary | `diary-po` | регистрация клиента sudir |
| Deeplink-схема | — | `diary-po://` | — |
| Магазины | — | `ru.mes.diary.po`, App Store id6508168563 | обновление, оценка |
| Название | «Дневник МЭШ» | «Колледж МЭШ» (коротко «КОЛЛЕДЖ») | — |
| Тема (900031/37) | — | градиент #3B6EEC → #0D40BF | — |
| Сферум / Спутник | ссылки на магазины | пусто | — |

### 2.3. Авторизация

- `getAUPDTokenSaga` (1829634):
  - POST `getAUPDTokenUrl`, тело `{user_authentication_for_mobile_request:{mos_access_token}}`;
  - ответ `user_authentication_for_mobile_response.{mesh_access_token, mesh_refresh_token}`;
  - флаг `{default:true, po:false}`: в школе при отсутствии роли в JWT кидается `getAUPDUserRoleNotFound`, **в колледже проверка пропускается**.
- `getUserProfileIdSaga` (1505391), флаг `{default:true, po:false}`:
  - GET `getMeshTokenUrl`;
  - школа фильтрует профили по типам StudentProfile/ParentProfile, **колледж берёт любые**;
  - при пустом списке: школа → `NoProfileError`, колледж → общий `getUserProfileIdError`.
- profileRoleTextDict для po: `legal_representative` → «Законный представитель», `trusted_representative` → «Доверенный представитель». В школе — «Родитель» / «Представитель».

---

## 3. Таблица переключателей selectByBuild (по функциональности)

### 3.1. Булевы флаги

| Место (строка) | Школа | Колледж | Смысл |
|---|---|---|---|
| useIsMoscowRegion (1464974) | moscow: true | `regionId === Moscow` | колледж считается «Москвой», только если выбран регион Москва |
| getAUPDTokenSaga (1829634) | true | false | строгая проверка роли в JWT |
| getUserProfileIdSaga (1505391) | true | false | фильтр типов профилей |
| ScheduleListContainer (1975794) | true | **false** | «осведомлённый журнал» (makeGetAwareJournalVisibleSelector) в расписании колледжа не используется (вывод по коду, не проверено) |
| Home getStoriesDataset (2706075) | true | **false** | сторис `goals` скрыты |
| SubjectThemesPageContainer (2929083) | true (region false) | **false** | useGetSubjects не включается |
| AppSettingsThemeSectionContainer (3163506) | true | **false** | выбора цветовой темы (ColorThemePicker) нет |
| ProfessionEducationSection (3251638) | true | **false** | блока «Профессиональное обучение» нет |
| sendSchoolAnalyticEvents (3434859) | true | **false** | школьная аналитика выключена |
| Designations, легенда отметок (1780937) | false | **true** | тег «занятие на производственной площадке» |
| Мок/легенда отметок (1781685/1781700) | показаны | **скрыты** | отметки «См» и «НВ» |
| BillingV3BalancePageContainer (2916762) | обработчик A | **обработчик B** | другое поведение кнопки пополнения (детали не разбирались) |
| UserProfile (3122102) | false | **true** | аватар с инициалами |
| RepresentativeBanner (3139235) | false | **true** | баннер о представителях |
| MySchoolPageContainer (3433505) | — | **true** | аналитика college_aboutCollege_listOfBranches / listOfTeachers |

### 3.2. Флаги, зависящие от данных

| Место (строка) | Школа | Колледж |
|---|---|---|
| Foodbox (2977676) | по toggle | только при дневном лимите > 0 |
| Avatar (3100206) | фиксированное значение | `!r2` (зависит от входного параметра) |
| HomeButtonGroupCabinetContainer (3118356) | false | `isRoleAccessAvailable(role, ['student_adult'])`: совершеннолетний студент видит «Сводные отчёты» (toggle `reports`) |
| useIsCertificatesFlowAvailable (1801788) | getLegalRepresentativeChildrenNoPreschoolersAndSpo | getChildrenOnlySPO; плюс toggle `certificates` |

### 3.3. Небулевы переключатели

| Место (строка) | Школа / регион | Колледж |
|---|---|---|
| Календарь событий, source_types (2012923) | default `[]`; region `['ORGANIZER','AFISHA','OLYMPIAD','PROF']` | `['AE','EC','OLYMPIAD','PROF']` |
| Фильтры событий (3475045) | region: offsiteEvent, afishaEvents | additionalEducation, extracurricularActivity |
| Имена аналитических событий | ScheduleTopicSelection, CollectionsSubjectClick | TopicSelection, CollectionsClick |
| Локализация | — | совпадает с moscow |

Остальные ~60 вызовов selectByBuild — картинки, lottie, стили, тексты заглушек: splash, Welcome1–4, Empty/Error и т.п. На функциональность не влияют.

---

## 4. Remote config и feature toggles

### 4.1. Механизм

- Провайдер — **Varioqub (Yandex)**. `fetchRemoteConfigSaga` (1825128) делает так:
  1. `remoteConfig.setUser(id, {region, schoolIds, classLevels})`;
  2. `getAll()`;
  3. `isAllFeaturesAvailable`;
  4. `isAppAvailableSaga` (для moscow и регионов);
  5. `isAppVersionDeprecated`.
- Модуль featureToggle (1394744–1395200):
  - действия updateMultiple, updateRegionFeatureToggles, updateSchoolsFeatureToggles;
  - итоговое значение флага = глобальный ключ + переопределения `regions_feature_toggles` + `schools_feature_toggles`.
- `initialFeatureToggles` (1287318): все `false`, кроме `is_app_available: true`. **Пока конфиг не загружен, почти все сервисы выключены.**
- Конфиг выдаётся на уровне приложения (у колледжа свой проект Varioqub), региона и школы. **В бандле нет списка «сервисы колледжа»**: что показывать, решает конфиг.

### 4.2. Серверное меню

- **Серверного меню (`/v1/menu` и т.п.) нет.**
- Список сервисов «Сервисы» задаёт ключ remote config **`school_services`**, с `overridesBySchool` по `global_school_id` (getSchoolServicesConfigState, 1286392). По умолчанию у всех сервисов `available: false`.
- Строка `'subsystems'` (3165857) — это данные о ролях (getRoleInfoByRole), не меню.

### 4.3. Ключи remote config

`update_modal`, `regions_info`, `mapi_host_config`, `auth_settings`, `alice_host_config`, `avatars`, `circles_camps_config`, `dumps_settings`, `electronic_identifiers`, `erase_mark_config`, `finances_config`, `fos_config`, `gamification_config`, `gia_banner_config`, `materials_without_adaptive_layout`, `moscow_banner_config`, `new_services_badge`, `notification_provider_order` (`_school`, `_region`), `offsite_event_storage_config`, `policy_urls_config`, `proforientation_banner_config`, `proforientation_v2_banner_config`, `reauthorization_required_config`, `regions_feature_toggles`, `schools_feature_toggles`, `school_services`, `seasonal_decoration`, `sferum_config`, `sputnik_store_urls`, `student_work_config`, `transport_cards_config`, `user_notices`, `vpn_check_config`, `yandex_education_banner_config`, `yandex_tutor_ai_banner_config`.

### 4.4. Feature toggles (1400772): ключ → флаг → назначение

| Ключ | Флаг | Назначение |
|---|---|---|
| app_available | is_app_available | приложение доступно (иначе заглушка) |
| preorder_v3 | is_preorder_v3_available | предзаказ питания v3 |
| remote_lesson | is_link_to_join_available | ссылка на дистанционный урок |
| sudir_account | is_password_creation_available | создание пароля/аккаунта СУДИР |
| compilations / new_compilations | is_compilations_available / is_new_compilations_available | подборки (материалы) |
| goals_v2 / settings_goals | is_goals_v2_available / is_settings_goals_available | цели |
| agreements | is_agreements_v2_available | «Согласия» |
| consents_banner | is_consents_banner_available | баннер согласий |
| flu_vaccination / flu_vaccination_banner | is_flu_vaccination_available / ..._banner_available | прививка от гриппа |
| offsite_events | is_offsite_events_available | выездные мероприятия |
| portfolio_interests / new_portfolio | is_portfolio_interests_available / is_new_portfolio_available | портфолио (new_portfolio меняет последнюю вкладку) |
| alisa | is_alisa_available | Алиса / голосовой помощник |
| certificates | is_certificates_available | «Справки об обучении» |
| month_schedule | is_month_schedule_available | расписание на месяц |
| schedule_print_form | is_print_forms_available | печатная форма расписания |
| stories / sharing_stories / stories_reactions | is_stories_v2_available / is_sharing_stories_available / is_stories_reactions_available | сторис |
| reports | is_reports_v2_available | «Сводные отчёты» |
| vk_chats | is_vk_chats_v2_available | чаты VK |
| profile_documents | is_profile_documents_available | документы в профиле |
| requests | is_requests_available | «Запросы» |
| contracts / contractid | is_contracts_available / is_contractid_available | «Договоры» |
| geolocation | is_geolocation_available | геолокация |
| degustations | is_degustations_available | «Дегустации» |
| transport_cards | is_transport_cards_available | транспортные карты |
| electronic_identifiers | is_electronic_identifiers_available | «Москвёнок: карты и браслеты» |
| electronic_student_card | is_electronic_student_card_available | электронный студенческий билет |
| vcu_notifications | is_vcu_notifications_available | уведомления ВЦУ |
| notifications_settings | is_notifications_settings_available | настройки уведомлений (MAIL_TARGET_ID) |
| new_news_flow | is_new_news_flow_available | новый поток новостей |
| sections_groups / sections_on_map | is_sections_groups_available / is_sections_on_map_available | кружки |
| camping | is_camping_available | лагеря |
| visits_v2 | is_new_visits_flow_available | новая посещаемость (проходы) |
| events | is_events_available | календарь событий |
| school_claim | is_school_claim_available | заявления в школу |
| appeals | is_appeals_available | обращения (FOS) |
| rate_app | is_rate_app_available | оценка приложения |
| new_created_child_flow | is_new_created_child_flow_available | добавление ребёнка |
| new_lrs_results_flow | is_new_lrs_results_flow_available | результаты ЛРС (контрольные) |
| physical_health_group | is_physical_health_group_available | группа здоровья |
| — (без ключа) | is_absence_editing_available, is_new_materials_available | остаются false по умолчанию |

Вложенные флаги в других конфигах:
- school_services: `archive.is_print_form_available`, `gamification.*`, `voiceHelper.is_device_control_available`;
- finances_config: `is_payment_history_available`, `is_autopayment_banner_available`.

---

## 5. Навигация и сервисы

### 5.1. Вкладки (MainTabNavigator, 3312878–3313420)

1. «Расписание» (schedule-tab).
2. Оценки (marks-tab).
3. «Задания» (homeworks-tab).
4. Школа/колледж (school-tab, SchoolStackNavigator). Подписи берутся из localization, у `po` они как у moscow.
5. Последняя вкладка:
   - «Портфолио», если `is_new_portfolio_available`;
   - иначе «Счета» (finances-home), если доступны финансы;
   - иначе «Профиль».

Для дошкольника вместо части вкладок — «Детский сад» и «Распорядок». Для колледжа не актуально.
Отдельных вкладок для `po` нет, набор вкладок от сборки не зависит.

### 5.2. Главная вкладки «Школа» (SchoolHomePageContainer, 3323656)

- Слоты: services, attendance, food, rating, balance, баннер итогов, транспортная карта, профориентация, цифровой учитель.
- Условия: useVisitsV2Available, toggle `reports`, медрекомендации ЕМИАС, useIsMoscowRegion.

### 5.3. «Сервисы» (schoolServicePaths/Titles, ~2772515–2773103)

- Каждый сервис включается через `school_services` (remote config) и через `useAvailableSchoolServices`.
- useAvailableSchoolServices проверяет:
  - роли student_teen, student_adult, legal_representative, trusted_representative;
  - class_level_ids;
  - первоклассников и дошкольников.

Сервисы: portfolio (Портфолио), food (питание), attendance (Посещаемость), lesson-map (предметы), exams (Подготовка к ЕГЭ), cultural-places (Места), sections (Кружки), school (о школе/колледже, my-school), archive (итоговые отметки, final-marks), lesson-tests (Контрольные), rating (Рейтинг), library (Библиотека), news (Новости), digital-teacher (Цифр. учитель), attestations (Зачётная книжка), soft-skills (Учебные умения), gamification (Подарки), ai-chatbot (Чат-бот Кеша), psychologist (Психолог), voice-helper (Голос. помощник), issued-materials (Учебники).

### 5.4. Кабинет (HomeButtonGroupCabinet)

Пункты кабинета и флаги:
- «Москвёнок: карты и браслеты» — electronic_identifiers;
- «Согласия» — agreements;
- «Запросы» — requests;
- «Договоры» — contracts;
- «Справки об обучении» — certificates;
- «Сводные отчёты» — reports; в колледже ещё требуется роль `student_adult`;
- «Дегустации» — degustations.

Также в кабинете участвуют toggles offsite_events и flu_vaccination.

### 5.5. Что есть в колледже

Разделение по бандлу:
- **Есть, и это специфика колледжа:**
  - журнал через `/api/profeducation`;
  - роль 32, подсистема familypom / 30 / 59;
  - FOS mp_college;
  - тег «производственная площадка» в легенде отметок;
  - события с типами AE/EC/OLYMPIAD/PROF и фильтрами additionalEducation / extracurricularActivity;
  - баннер представителей (dnevnik_spo_mp);
  - аватар с инициалами;
  - «Сводные отчёты» для совершеннолетнего студента;
  - справки только по СПО-детям (getChildrenOnlySPO);
  - аналитика «О колледже» (филиалы, преподаватели);
  - питание `mp_college_food`, foodbox только при лимите > 0.
- **Нет (жёстко выключено сборкой):**
  - выбор цветовой темы;
  - блок «Профессиональное обучение»;
  - сторис «цели» (goals) на главной;
  - темы предметов (SubjectThemes / useGetSubjects);
  - «осведомлённый журнал» в расписании;
  - школьная аналитика;
  - отметки «См» и «НВ» в легенде;
  - ссылки на Сферум и Спутник;
  - строгая проверка роли в JWT и фильтр типов профилей.
- **Зависит от флага или конфига** (решает remote config колледжа, по бандлу не определить): все сервисы из 5.3, пункты кабинета из 5.4, последняя вкладка (портфолио / счета / профиль), новости и сторис, уведомления, события, посещаемость v2, транспортные карты, электронный студенческий билет и остальные toggles из 4.4.

---

## 6. training_camp (учебные сборы)

- Эндпоинт: `GET {ej}/core/family/v1/training_camp` (operationId `get-training-camp`, «Получить список учебных сборов»). В колледже `{ej}` = `/api/profeducation`.
- Цепочка вызовов:
  1. `schoolApi.getTrainingCamp`;
  2. `useGetTrainingCamp({profileId})` (3446602, queryKey `MARKS_ARCHIVE_QUERY_KEYS.getTrainingCamp`);
  3. `useTrainingCampPeriods` (3447754).
- `useTrainingCampPeriods` берёт contingentGuid и classLevelId текущего ученика. Если в ответе есть его `class_level_id`, в список периодов добавляется «Учебные сборы (УС)».
- Потребители (только они):
  - `MarksArchiveContainer` (3444608): архив и итоговые отметки, вместе с useAcademicYears / useMarksArchive;
  - `FinalMarksRoundingLimitsPageContainer` (3450130): пороги округления итоговых.
- Поле `training_camp_mark` в итоговых отметках читается на 3447446.
- От сборки не зависит (selectByBuild нет). Ранее на реальных данных колледжа не было ни `training_camp_mark`, ни `rounding_limits`. Для колледжа это, по сути, школьная функция (учебные сборы ОБЖ 10 класса).
