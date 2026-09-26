# 01. «Дневниковое» ядро колледжа (Family mobile, /api/ej, ручные саги)

Источники: встроенные спецификации Family и School (только `/api/ej/...`), сгенерированные клиенты Family и School,
ручные запросы (саги) из декомпилированного бандла.

## 0. Как бандл строит URL (важно для маршрутизации)

**apiRequest** (`decompiled.js` ~1613209, фабрика `networkLayerFactory`, `createUrl` ~1612935):

```
URL = (options.host || getHost()) + (options.apiVersion !== undefined ? options.apiVersion : getApiVersion()) + endpoint + queryString
```

- `getHost()` / `getApiVersion()` (~1506046/1506110): берут remote-config `mapi_host_config` {dev,test,prod}
  (значение по умолчанию `MAPI_HOST_CONFIG_DEFAULT`: `https://school.mos.ru/api/family/mobile` + `/v1`),
  `selectByBuild({moscow, region, po})`: для `po` — `getRegionHostByType(state,'apiMapiUrl')` выбранного региона, если он
  задан, иначе московское значение из remote-config. **Локальной подмены на `profeducation/family/mobile` в бандле нет** —
  у колледжа её, по-видимому, делает remote-config (OpenMES ходит в `api/profeducation/family/mobile/v1`, работает).
- Саги **без host** → `https://school.mos.ru/api/family/mobile/v1/<path>` (у колледжа → `api/profeducation/family/mobile/v1/<path>`).
- Саги с `host: getEjHost() + '/core/family/v1'`, `apiVersion: ''` → `/api/ej/core/family/v1/<path>`
  (у колледжа `getEjHost` = `…/api/profeducation`).
- `/visits`: `host: getIsppHost()` (= `getSchoolApiHost()` + `/family/ispp`, у po `/profeducation/family/ispp`),
  `apiVersion: '/v1'` → `https://school.mos.ru/api/profeducation/family/ispp/v1/visits`.

**Сгенерированные axios-клиенты:**
- `FamilyApi` (~1612180): `basePath = 'https://school.mos.ru'`, интерсептор `familyBaseurl` (~1594910) заменяет origin на
  `getHost() + getApiVersion()` — т.е. тот же хост, что у apiRequest по умолчанию.
- `SchoolApi` (~1592958): интерсептор `schoolBaseurl` заменяет только origin на `getSchoolHost()` (у po — `apiSchoolUrl`
  региона либо school.mos.ru). **Путь `/api/ej/...` у School-клиента НЕ переписывается на `/api/profeducation`** —
  в бандле колледж шлёт их буквально на `/api/ej/...`. Переписывание `/api/ej→/api/profeducation` для этих методов —
  решение OpenMES (частично проверено вживую, см. CONTEXT.md).

Общие заголовки (commonHeaders): `X-Mes-Subsystem`, `client-type: diary-mobile`, `Accept-Language: ru`, `Profile-id`,
`X-Mes-RoleId`, `Auth-Token`/`Authorization`. Массивы в query у axios-клиентов (`setSearchParams`) — повтором ключа
(`attestation_forms=EXAM&attestation_forms=TEST`); OpenMES сейчас шлёт через запятую.

Обозначения: `*` — обязательный параметр; «F» = Family (spec 05), «S» = School (spec 12), «saga» = ручной apiRequest.
Идентификаторы: `student_id` (family mobile) и `student_profile_id` (ej) — это одно и то же число (id профиля студента);
`person_id` / `contingent_guid` / `personId` — GUID персоны Контингента.

---

## 1. Расписание

### GET schedule/short (F)
- Колледж: `GET api/profeducation/family/mobile/v1/schedule/short` · исходно `api/family/mobile/v1/schedule/short`
- Сокращённое расписание студента на даты.
- query: `student_id*` int64, `dates*` string (даты через запятую, YYYY-MM-DD).
- 200: `{payload: [{date, lessons: [{lesson_id, group_id, group_name, lesson_name, absence_reason_id, begin_time, end_time, subject_id, subject_name, bell_id, is_virtual, lesson_education_type, schedule_item_id}]}]}`

### GET lesson_schedule_items/{lesson_schedule_item_id} (F)
- Колледж: `api/profeducation/family/mobile/v1/lesson_schedule_items/{id}`
- Карточка урока по id.
- path `lesson_schedule_item_id*` int64; query `student_id*` int64, `person_id*` uuid, `type` enum OO|AE|EC. Заголовок `cache-control` (актуальные данные).
- 200 `LessonScheduleItemResponse`: `id, date, begin_time, end_time, begin_utc, end_utc, building_name, comment, course_lesson_type (LESSON|THEMATIC_TEST|MODULE_TEST|LAB|PRACTICE), control{variant_number, mark, comment, attested_files}, details{content[], theme{id,title,average_mark,…}, lessonId, lesson_topic, additional_materials[]}, field_name, is_missed_lesson, nonattendance_reason_id (1..12), lesson_homeworks[{homework, homework_entry_student_id, homework_id, homework_entry_id, attachments[], is_done, additional_materials[], written_answer, date_assigned_on, date_prepared_for, is_smart}], homework_presence_status_id, lesson_type (NORMAL|REMOTE|ELECTRONIC), marks[SimpleMark], remote_lesson{link_to_join, link_to_record, record_preview}`.

### GET lesson_schedule_items (F)
- Колледж: `api/profeducation/family/mobile/v1/lesson_schedule_items`
- Урок по дате / предмету / группе.
- query: `student_id*`, `person_id*` uuid, `date*` date, `type` OO|AE|EC, `subject_id` int, `group_id` int64.
- 200: тот же `LessonScheduleItemResponse` (один объект).

### GET periods_schedules — календарь (saga `calendar`)
- Колледж: `api/profeducation/family/mobile/v1/periods_schedules` · исходно (host по умолчанию) `api/family/mobile/v1/periods_schedules`
- Календарь учебных/каникулярных дней по месяцам.
- query: `student_id*`, `from` (`YYYY-MM-01` или начало учебного периода), `to` (последний день месяца / конец периода с сентябрём).
- 200: схемы в бандле нет; OpenMES парсит массив `[{date, type, title}]`.

### GET core/family/v1/periods_schedules (saga `periodsSchedules`)
- Колледж: `api/profeducation/core/family/v1/periods_schedules` · исходно `/api/ej/core/family/v1/periods_schedules`
- Учебные периоды (семестры/четверти) студента. `isOldAuthNeed=false`.
- query: `student_profile_id*`.
- 200: схемы нет (список периодов графика).

### GET plan/family/v1/test_lessons/period (saga)
- Колледж: `api/profeducation/plan/family/v1/test_lessons/period` · исходно `/api/ej/plan/family/v1/test_lessons/period`
- Контрольные/зачётные уроки за период. Заголовок `Profile-Type: student|parent`.
- query: `student_profile_id*`, `student_person_id*` (GUID), `subject_id`, `from`, `to` (опциональны).
- 200: схемы нет (OpenMES: `TestLessonsResponse`).

### GET programs/lesson_plan (saga `programsLessonPlan`)
- Колледж: `api/profeducation/family/mobile/v1/programs/lesson_plan`
- Тематическое планирование по предмету/группе.
- query: `group_id*`, `student_id*`, `school_id*`, `subject_id*`.
- 200: схемы нет.

### GET programs/parallel_curriculum/{parallel_curriculum_id} (saga)
- Колледж: `api/profeducation/family/mobile/v1/programs/parallel_curriculum/{id}`
- Учебный план параллели. query: `student_id`. 200: схемы нет.

### GET topic_subjects_results (saga)
- Колледж: `api/profeducation/family/mobile/v1/topic_subjects_results`
- Результаты освоения тем урока. query: `student_id`, `lesson_id`, `group_id`, `school_id`. 200: схемы нет.

### GET events (saga)
- Колледж: `api/profeducation/family/mobile/v1/events`
- События учреждения (школьные мероприятия) на период. query: `school_id`, `class_unit_id` (пустая строка), `from_date`, `to_date` (YYYY-MM-DD). 200: схемы нет.
- Основное расписание мероприятий идёт через eventcalendar (`getEventCalendarsHost` + `v1/api/events`) — вне этого раздела.

### GET /api/ej/core/family/v1/academic_years (S)
- Колледж: `api/profeducation/core/family/v1/academic_years` (вживую есть) · исходно `/api/ej/core/family/v1/academic_years`
- Список учебных лет. Заголовок `profile-id`.
- 200: `[{id* int64, name, begin_date, end_date, current_year bool, calendar_id int64}]`.

### GET /api/ej/core/family/v1/training_camp (S)
- Колледж: `api/profeducation/core/family/v1/training_camp` · исходно `/api/ej/core/family/v1/training_camp`
- Список учебных сборов. Заголовок `profile-id`.
- 200: `[{id* int64, subject_id* int, class_level_id* int}]`.
- Признак школьного: учебные сборы (10 кл.); `training_camp_mark` у колледжа нет (404).

## 2. Оценки

### GET marks (F)
- Колледж: `api/profeducation/family/mobile/v1/marks`
- Оценки за период.
- query: `student_id*` int64, `from` date, `to` date.
- 200 `{payload: [{id*, value*, values[{grade{five,ten,hundred,origin}, grade_system_id, grade_system_type, name, nmax}], weight*, date*, subject_id*, subject_name*, control_form_name, comment, comment_exists, criteria[{name,value}], is_exam, is_point, point_date, original_grade_system_type, has_files, created_at, updated_at}]}`

### GET marks/{markId} (F)
- Колледж: `api/profeducation/family/mobile/v1/marks/{markId}`
- Подробности оценки. query: `person_id` (GUID).
- 200 `MarkResponse`: поля оценки + `teacher{last_name, first_name, middle_name, user_id}`, `activity{lesson_topic, schedule_item_id}`, `history[{before, after, date}]`, `class_results{total_students, marks_distributions[{mark_value, number_of_students, percentage_of_students}]}`, `result_files[]`, `student_work_result_files[]`, `theme_mastery{theme_id,url,stats_url,suggested,correct_percent}`, `subject_id`, `subject_name`.

### GET subject_marks/short (F + saga `subjectMarksShort`)
- Колледж: `api/profeducation/family/mobile/v1/subject_marks/short`
- Сводка по всем предметам (средний, динамика, цель).
- query: `student_id*` int64, `ceil_after` int32 — порог округления. Сага добавляет `ceil_after` (`updateQueryParamsWithRoundingTresholdSaga`) только если включены «цели» и порог ≠ `DEFAULT_ROUNDING_TRESHOLD` (`'51'`); порог берётся из локальных настроек, а не с сервера.
- 200 `{payload: [{subject_id*, subject_name*, average*, dynamic*, count*, start*, end*, period, fixed_value, marks*[MarkWithDate], target*{value, round, remain, paths}}]}`

### GET subject_marks/for_subject (F + saga `subjectMarksForSubject`)
- Колледж: `api/profeducation/family/mobile/v1/subject_marks/for_subject`
- Сводка по одному предмету с разбивкой по периодам.
- query: `student_id*`, `subject_id` (есть в саге, в спеке отсутствует), `ceil_after`.
- 200 `{subject_id*, subject_name*, average*, average_by_all*, dynamic*, periods*[{title, start, end, count, dynamic, value, fixed_value, marks[], target}]}`

### GET final_marks (saga `marksArchive`)
- Колледж: `api/profeducation/family/mobile/v1/final_marks`
- Старый архив итоговых оценок. query: `student_id*`. 200: схемы нет.

### GET attestation (F) — зачётная книжка (профильно для колледжа)
- Колледж: `api/profeducation/family/mobile/v1/attestation`
- Данные зачётки по фильтрам.
- query: `student_id*`, `attestation_forms*` [EXAM|TEST|COURSE_WORK|PRACTICE|GIA] (массив), `academic_year_id` int, `is_current_year` bool.
- 200 `{semesters*[{academic_year_id, attestation_periods_id, name, attestation_forms[{attestation_form_name, subjects}]}], years*[{academic_year_id, academic_year_name, class_level_id, class_level_name, is_current_year}], gia*{academic_year_id, gia_forms[{subject_id, subject_name, mark, theme, defense_date, exam_date, supervisor_name, exam_commission_chairman_name, order_date, exam_protocol_id}], diploma{qualification_name, registration_number, issue_date, commission_decision_date}}}`

### GET attestation/file (F)
- Колледж: `api/profeducation/family/mobile/v1/attestation/file`
- Зачётка в PDF. query: `student_id*`, `person_id*` uuid, `school_id*` int. 200: application/pdf.

### GET subjects/list (saga `subjectsList`)
- Колледж: `api/profeducation/family/mobile/v1/subjects/list`
- Список предметов студента. query: `student_id*`. 200: схемы нет.

## 3. Домашние задания

### GET homeworks/short (F)
- Колледж: `api/profeducation/family/mobile/v1/homeworks/short`
- ДЗ в сокращённом виде (так грузит приложение: `sort_column=date`, `sort_direction=asc`).
- query: `student_id*`, `from`, `to`, `sort_column`, `sort_direction` asc|desc, `subject_id`, `page`, `per_page`.
- 200 `{payload: [{description*, subject_id*, subject_name*, group_id, date*, date_assigned_on, homework_entry_student_id*, materials_amount*[{selected_mode, uuids}], has_written_answer*, is_done*, type, has_teacher_answer*, lesson_date_time*}]}`

### GET homeworks (saga `homework`)
- Колледж: `api/profeducation/family/mobile/v1/homeworks`
- Полные ДЗ. query: `student_id*`, `from`, `to`. 200: схемы нет (`{payload:[{homework, homework_entry_student_id, homework_id, homework_entry_id, subject_*, date_*, materials, …}]}` по OpenMES).

### POST / DELETE homeworks/{homework_entry_student_id}/done (F)
- Колледж: `api/profeducation/family/mobile/v1/homeworks/{id}/done`
- Отметить / снять отметку о выполнении.
- path `homework_entry_student_id*` int; query `type` OO|AE|EC (обязателен для AE/EC).
- 200 `{success: bool}`.

### GET /api/ej/partners/v1/homeworks/launch (S + saga `homeworkLaunch`)
- Колледж: `api/profeducation/partners/v1/homeworks/launch` (сага: `getEjHost()+'/partners/v1'`, apiVersion '')
- Ссылка на внешний материал ДЗ; ответ 302, сага ловит `responseHandlers[302]` и берёт Location.
- query: `material_id*` (encodeURIComponent), `homework_entry_id` int, `work_id` int (геймификация).

### Студенческие работы (S, attachments)
Колледж: `api/profeducation/attachments/v1/...` · исходно `/api/ej/attachments/v1/...`. Заголовок `Profile-id`.
- `POST /api/ej/attachments/v1/student_work` — создать работу. body `{lesson_schedule_item_id*, student_profile_id*, work_type*, variant}`. 201 `{id, lesson_schedule_item_id, student_profile_id, work_type, variant, status CREATED|ON_CHECK|EVALUATED, mark_ids[], created_at, updated_at}`.
- `PUT /api/ej/attachments/v1/student_work/{student_work_id}/variant` — сменить вариант. body `{variant}`.
- `POST /api/ej/attachments/v1/files/by_student_work/{student_work_id}/storage_access` — доступ к хранилищу. 200 `{token, file_link, file_uuid}`.
- `POST /api/ej/attachments/v1/files/by_student_work/{student_work_id}` — прикрепить файлы. body `{files[{file_uuid*, file_name, order_number, size, extension, deleted}]}`. 200 `{work_id, files[{file_uuid, original_file_uuid, file_name, order_number, size, extension, file_link}]}`.

## 4. Посещаемость

### GET attendance (F)
- Колледж: `api/profeducation/family/mobile/v1/attendance`
- Пропуски за период. query: `student_id*`, `from`, `to`.
- 200 `{attendance*[{date*, summary*, notified, description, reason_id (1..12), is_system, parent_profile_id, lessons*[{subject_id, subject_name, bell_id, schedule_item_id, reason_id, notified, description, lesson_education_type, health_status}]}], days_count*, year_description}`

### POST attendance/v2, DELETE attendance (F)
- Колледж: `api/profeducation/family/mobile/v1/attendance/v2` / `.../attendance`
- Создать / удалить уведомление о запланированном пропуске (родительская функция).
- body `{student_id*, notifications*[{date*, bell_id, begin_time, end_time, lesson_education_type, description, reason_id}]}`. 200 (POST) `{ids*[int]}`.
- Скорее родительская/школьная функция.

### GET /api/ej/core/family/v1/nonattendance_reasons (S)
- Колледж: `api/profeducation/core/family/v1/nonattendance_reasons`
- Справочник причин пропусков. query `ae_only` bool. Заголовок `Profile-id`.
- 200 `[{id*, name*, is_parent_available, ae_only, attachment_needed, deleted_at}]`

### GET /api/ej/core/family/v1/emias_medical_recommendations (S)
- Колледж: `api/profeducation/core/family/v1/emias_medical_recommendations` (вживую есть)
- Медрекомендации ЕМИАС (освобождения от занятий).
- query (все необязательные): `student_profile_id`, `person_ids`, `class_unit_id`, `start_date`/`end_date` date-time, `page`, `per_page`.
- 200 `[{id, date, student_profile_id, subject_ids[int], type SICK|SICK_WITH_INFECTION|EXEMPT}]`

### GET visits — проходы ИСПП (saga `visits`)
- Колледж: `https://school.mos.ru/api/profeducation/family/ispp/v1/visits` · исходно `api/family/ispp/v1/visits` (`getIsppHost` + `/v1`)
- Проходы через турникеты по договору питания/карты.
- query: `contract_id*`, `from`, `to`. `isOldAuthNeed=false`. 200: схемы нет.

## 5. Профиль

### GET profile (saga)
- Колледж: `api/profeducation/family/mobile/v1/profile`
- Профиль пользователя; `timeout 300000`, `force`, заголовок `Profile-Id`.
- 200: схемы нет; OpenMES: `{profile{id, user_id, type, first_name, last_name,…}, children[…]}`.

### GET school_info (F)
- Колледж: `api/profeducation/family/mobile/v1/school_info`
- Сведения об учреждении. query: `class_unit_id*`, `school_id*`, `subject_id`, `student_id`.
- 200 `{id*, name*, type, principal, classroom_teachers[{last_name, first_name, middle_name}], address{county, district, address}, phone, email, website_link, teachers[{…, subject_names[]}], branches[{name*, type, address, is_main_building, is_student_building, building_id*}]}`

### GET student-card (F) — только колледж
- Колледж: `api/profeducation/family/mobile/v1/student-card`
- Электронный студенческий билет. query `student_id*`.
- 200 `{last_name*, first_name*, middle_name, student_card_number*, student_card_issue_date*, student_card_valid_until*, education_form_name*, education_level_name*, class_level_name*, profession_specialty_name*, enrollment_order_number*, enrollment_order_date*, school{name*, founder_name*}}`

### POST student-card/qr (F)
- Колледж: `api/profeducation/family/mobile/v1/student-card/qr`, query `student_id*`.
- 200 `{qr_code*, qr_url* (…/api/profeducation/family/public/v1/student-card/verification_page?token=…), expires_at*, ttl_seconds*}`

### aware_journals — «ознакомлен с дневником» (saga `awareJournals`)
- `GET api/profeducation/core/family/v1/aware_journals` · исходно `/api/ej/core/family/v1/aware_journals`
  query: `student_profile_ids*`, `week_from`, `week_to` (даты начала/конца недели).
- `POST` тот же путь, body `{parent_profile_id, student_profile_id, week}`.
- Признак школьного: родительская подпись за неделю.

## 6. Уведомления

- `GET notifications/search` (saga `notificationsSearch`) → `api/profeducation/family/mobile/v1/notifications/search?student_id*` — лента уведомлений. Схемы нет.
- `GET notifications/status` (saga `notificationsStatus`) → `.../notifications/status?student_id*`, `minLatency` — счётчик/статус непрочитанных. Схемы нет.

## 7. Настройки

### GET / PUT settings (F)
- Колледж: `api/profeducation/family/mobile/v1/settings`
- Группы пользовательских настроек.
- GET query: `person_ids*` [string], `name` enum settings_group_v1|favoriteServices|favoriteCircles|UC_PARENT_TTC_CARD_NOTIFICATION_LIMITS|UC_TTC_CARD_BALANCE.
- PUT query `name`; body `{persons*[{person_id*, settings* (строка JSON)}]}`.
- 200/206 `{persons*[{person_id*, settings}]}`
- (Серверные user-settings в `api/usersettings/v1` — вне раздела.)

## 8. Прочее

- `POST camps/applications` (F) — заявка в лагерь; body `{slot_id*, motivation_text*, benefit*, cadet_class*, education_type_id*, applicant*, child, shift_id, …}`. Школьное.
- Платежи (F): `POST payments/mospay/init` (body `{portal_id*, payments*[{type FOOD|CHARGE, amount*, contract_id, …}], urls}` → `{payment_link*, request_uid*, package_uid}`); `POST payments/mospay/charges` (`{portal_id*, start_date, end_date}` → `{UUID*}`); `GET payments/mospay/charges/{UUID}` (→ `{is_search_completed*, total_sum*, charges*[{uin, amount_to_pay, bill_for, supplier_org_info, …}]}`); `POST payments/history/token` (→ `{access_token*, expires_in*}`); `POST payments/history` (body `{page, per_page, created_from, created_to, status[APRP|PROC|DECL], rnip_code[]}` → `{data[{id, status, service, amount, created_at, additional_data…}], total_count, current_page, total_pages, per_page}`).

---

## Пробы

Рабочий список проб — `feature/more/src/main/kotlin/ru/openmes/feature/more/ApiProbes.kt`, статусы — README.md.
