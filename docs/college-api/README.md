# Карта API «Колледж МЭШ»

Восстановлена из бандла оригинального приложения (`ru.mes.dnevnik` 3.88.29, общий RN/Hermes-бандл
с «Дневником МЭШ», сборка колледжа — ключ `po`). Источники: 14 встроенных OpenAPI-спецификаций,
сгенерированные клиенты `*ApiAxiosParamCreator` и ручные запросы (саги `apiRequest`).
Школьная карта — [../school-mos-api-map.md](../school-mos-api-map.md); здесь только то, что касается колледжа.
Числа вида `~1612180` в разделах — номера строк декомпилированного бандла, по ним удобно искать код.

| Файл | Что внутри |
|---|---|
| [01-diary.md](01-diary.md) | Расписание, оценки, зачётка, ДЗ, посещаемость, профиль, уведомления (family/mobile, `/api/ej`, саги) |
| [02-portfolio-news.md](02-portfolio-news.md) | Портфолио (МП-бэк и портфолио-сервис), новости, рейтинг |
| [03-services.md](03-services.md) | Питание, сообщения, настройки уведомлений, проходы, платежи, справки, прочие сервисы |
| [04-college-build.md](04-college-build.md) | Чем сборка `po` отличается от школьной: хосты, id, флаги, remote config, навигация |

В карте оставлено только то, что у колледжа отвечает (или не проверялось — методы с id сущностей и POST).
Всё, что вживую дало 403/404 или 401 `{"apikey":null}`, из карты убрано — список ниже, в «Убрано».

## Главные правила

### Адреса
- `apiRequest` = `host` (или `getHost()`) + `apiVersion` (или `getApiVersion()`) + путь. По умолчанию
  `https://school.mos.ru/api/family/mobile` + `/v1`; для колледжа хост приходит из remote config
  (`mapi_host_config`) — фактически `/api/profeducation/family/mobile/v1`.
- Через `selectByBuild` колледжу подменяются только:
  `getEjHost` → `/api/profeducation`, `getEventCalendarsHost` → `/api/profeducation/eventcalendar/`,
  `getIsppHost` → `/profeducation/family/ispp`, `profile_info` → `/api/profeducation/acl/v1/mod-acl/users/profile_info`.
- **Сгенерированный клиент School ходит на `/api/ej/...` буквально** (перехватчик меняет только домен).
  Через `getEjHost` на `/api/profeducation` идут только `aware_journals`, `core/family/v1/periods_schedules`,
  `partners/v1/homeworks/launch`, `plan/family/v1/test_lessons/period`. OpenMES переписывает весь `/api/ej` —
  это наше решение, не оригинала (см. статусы: для части путей работают оба варианта).
- Рейтинг в коде — школьный `/api/ej/rating/v1`, но у колледжа работает `/api/profeducation/rating/v1`. Справки `/api/certificates/family/v1` — без подмены.
- Проходы: `/api/profeducation/family/ispp/v1/visits?contract_id=&from=&to=`, `contract_id` = `contractId` из баланса питания.
- Календарь событий у колледжа — `/api/profeducation/eventcalendar/v1/...`, обязательны `X-Mes-Role: student` и `Client-Type: diary-mobile`.

### Заголовки и идентификаторы колледжа
| Что | Колледж | Школа |
|---|---|---|
| `X-Mes-Subsystem` | `familypom` | `familymp` |
| `X-Mes-RoleId` студента | `32` | `1` |
| `subsystem_id` (usersettings) | `30` | `1` |
| `mail_target_id` (уведомления) | `17` | `6` |
| service id (новости, сторис, файлы) | `59` | `7` |
| cookie чат-бота | `59:32` | — |
| поддержка / питание | `mp_college` / `mp_college_food` | — |
| `X-Mes-AppId` (POST/PATCH питания) | `familymp` | `familymp` |

- Общие: `Authorization: Bearer`, `Profile-id`, `client-type: diary-mobile`, `Accept-Language: ru`.
- Отдельного apikey нет: `Auth-Token` (портфолио) и `token` (настройки уведомлений) — тот же токен АУПД.
- Портфолио-сервис: `Cookie: aupd_current_role=4:2` (жёстко в коде). Методы `/portfolio/...` — `x-mes-role` (1 студент, 2 родитель).
- Массивы в query оригинал передаёт повтором ключа (`a=1&a=2`).

### Особенности данных
- Питание: отрицательные суммы в примерах спецификации — беззнаковый int32 (4294959296 = −8000 коп.).
- `ceil_after` в `subject_marks/short` передаётся, только если локальный порог «целей» ≠ `51` (у колледжа ответ не меняет); порогов округления с сервера нет.
- `homeworks/launch` отвечает 302 — ссылка в `Location`.
- Лента сообщений `instantmessages/v1/feed`: параметр `filter` — строка JSON (`child_id`, `source_id`, `setting_group_id`).
- Сервисы и функции включаются remote config Varioqub (`school_services`, ~50 флагов `is_*_available`) —
  по коду нельзя сказать, какие сервисы видит конкретный колледж.

### Только колледж
Зачётка `attestation` (+ PDF `attestation/file`), `student-card` и `student-card/qr`
(QR → `api/profeducation/family/public/v1/student-card/verification_page`), тег «производственная площадка»
в легенде отметок, события AE/EC/OLYMPIAD/PROF, «Сводные отчёты» совершеннолетнего студента.

## Расхождения OpenMES с оригиналом
- Оценки: оригинал — `subject_marks/short` и `subject_marks/for_subject` (с `subject_id`), OpenMES — `subject_marks`.
- Портфолио: оригинал — `/persons/{id}/events/list`, `/rewards/list?size=`, `/academic/performance/average`,
  `/v2/academic-performance/average-mark`; OpenMES — пути веб-портфолио (`/events`, `/rewards`, `/academic-performance`).
- Массивы в query: OpenMES — через запятую.

## Проверка (живые статусы)
Прогон «Разведки», 2026-09-26, аккаунт студента колледжа, 158 GET-запросов (`feature/more/ApiProbes.kt`).
Пути даны после роутинга OpenMES (`CollegeRouting`: `/api/family/mobile` и `/api/ej` → `/api/profeducation`).
«пусто» — 200 с пустым массивом/объектом у этого аккаунта (метод есть, данных нет).

### Работает (200)
| Раздел | Методы |
|---|---|
| family/mobile (profeducation) | `profile`, `schedule/short` (1 и 2 даты), `periods_schedules`, `marks`, `subject_marks/short` (`ceil_after` ответ не меняет), `final_marks`, `attestation` (`is_current_year` и `academic_year_id` — одинаково), `subjects/list`, `homeworks/short`, `homeworks`, `attendance`, `student-card`, `settings` |
| `/api/profeducation/core/family/v1` | `academic_years`, `periods_schedules`, `nonattendance_reasons`, `emias_medical_recommendations` (по `student_profile_id` и по `person_ids`), `training_camp` (пусто), `aware_journals` (пусто) |
| `/api/profeducation/plan/family/v1` | `test_lessons/period` (пусто) |
| **Рейтинг** `/api/profeducation/rating/v1` | `rank/rankShort`, `rank/subjects`, `rank/class` — **с данными**. Оригинал ходит на `/api/ej/rating/v1`, у нас его переписывает роутинг — и это работает |
| Портфолио МП-бэк (profeducation/family/mobile) | `portfolio/science`, `sport`, `culture`, `v2/culture`, `creation`, `civil`, `profession`, `settings`, `interests`, `proforientation/status` |
| Портфолио-сервис (`/api/portfolio/app` и `/portfolio/app` — одинаково) | `academic/performance/average`, `v2/academic-performance/average-mark`, `govexams/list`, `events/list`, `rewards/list`, `diagnostic/independent-rating`, `diagnostic/general-rating`, `personal-diagnostic-grouped` (пусто), `share/list` (пусто), `reference/olympiad/subject`, `fos/feedbackLink` |
| Новости `/api/news/v2` | `news/users/v2`, `news/main/users`, `news/top`, `news/top/recommended`, `channels/system`, `catalogs` (TAGS, EMOJI), `stories/user`; пусто: `news/users`, `channels`, `announcements/user`, `banners/v2`, `stories/favorite/list/v2` |
| Питание `/api/food/meals/v3` | `clients/balance`, `allowed-food-state`, `food-provider`, `menu/buffet`, `menu/complexes`, `orders`, `orders/preorder/summary`; пусто: `menu/prohibitions`, `transactions`, `orders/rules` |
| Сообщения, настройки | `instantmessages/v1/feed` (пусто), `count_new`, `count_new_important`; `notifications/usersettings/v1/user/setting?mail_target_id=6`; `usersettings/v1?name=…` (пустое тело) |
| Прочее | `pass/entrances/v1/status`, `certificates/family/v1/certificates/statuses`, `avatarmanagement/v1/{personGuid}` (пусто), `consents/consents`, `consents/permission_status` (пусто), `aupd/v1/user/childrens`, `aupd/v2/external-partners/check-for-max-user` (vk — 204), `nsi/dictionaries/v1/credit_organizations`, `family`, `GAMIFICATION_PARTNER`, `fos/v1/url` |

### Работает при правильных параметрах (из текста ошибки)
| Метод | Что нужно |
|---|---|
| family/mobile `lesson_schedule_items` | `subject_id` или `group_id` |
| `portfolio/directories/{events,projects,affiliations,employments}` | параметр `type` |
| `portfolio/directories/{rewards,sport-rewards}` | `personId` (UUID) |
| `pass/entrances/v1/visit_durations` | период ≤ 7 дней |
| `certificates/family/v1/certificates`, `sfp/service/v1/summaries` | `studentId` — списком |
| `portfolio/app/portfolio/users/context` | кука `aupd_current_role` |
| `profeducation/eventcalendar/v1/api/events` | заголовки `X-Mes-Role: student`, `Client-Type: diary-mobile` |

### Сбой сервера на момент проверки
- family/mobile `notifications/search`, `notifications/status` — 500, таймаут бэкенда `mes-api.mos.ru/profeducation/notification/family/v1/...`.
- `profeducation/family/ispp/v1/visits` — 504 шлюза.

### Убрано из карты (у колледжа не работает)
- 404 «method does not exist»: family/mobile `final_marks/v2`, `final_marks/file`, `person-details`, `gia_applications`, `egeoge/*`, `map-token`, `portfolio/sort`.
- 404 нет пути: `school_settings/rounding_limits`, `psychology/v1/*`, `nsicache/v1/egeoge`.
- 403: весь `/api/ej/...` без перевода на `/api/profeducation` (правила маршрутизации выше).
- 401 `{"apikey":null}`: `soft_skills`, `tasting`, `ei/external` (карты), `additional_education`, `education_books`, `knowledge_skill`, `gamification`, `geo/tracker`, `alice`, `circles`.
- 401 `claims/v1` (нужна веб-сессия ЭЖД); 400/500 без понятной причины: `family/materials/v1`, `portfolio/app/.../result-lessons`, `.../independent-diagnostic-grouped`.
