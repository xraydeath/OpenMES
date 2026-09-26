# Карта API: портфолио, новости, рейтинг (Колледж МЭШ, сборка `po`)

Источник: восстановленные спецификации (10, 09, 11) + разбор decompiled.js (инстансы axios-клиентов,
интерсепторы, environmentSelectors). Всего методов: **94** (портфолио «МП-бэк» 28, портфолио-сервис 26,
новости 25, рейтинг 4) + 4 ручных запроса портфолио вне спецификаций.

Плейсхолдеры: `{personGuid}` — contingent_guid ученика (в спецификациях `personId` = «children.contingent_guid из GET /profile»),
`{studentId}` — id студента ЭЖ, `{profileId}` — id профиля, `{today}`/`{monthAgo}` — даты ГГГГ-ММ-ДД.

## 1. Откуда берутся базовые адреса (по коду)

Все сгенерированные клиенты создаются как `new XxxApi(new Configuration({basePath, accessToken, apiKey}), undefined, axiosInstance)`.
`accessToken` и `apiKey` в Configuration — **одна и та же функция `authSelectors.getToken`** (т.е. apiKey = тот же токен МЭШ).
Хост меняется интерсептором axios-инстанса: он берёт `new URL(url).origin` и заменяет его — **путь из basePath остаётся как есть**.

| Сервис | basePath в коде | axios-инстанс / интерсептор | Итог для колледжа |
|---|---|---|---|
| Портфолио-сервис (`PortfolioExternalApi`, `portfolioGatewayApi`) | `https://school.mos.ru/api/portfolio/app` | `portfolioGatewayApiInstance`: aupdCurrentRoleCookie, commonHeaders, schoolBaseurl, expiredToken | `https://school.mos.ru/api/portfolio/app` (префикс не меняется, getEjHost не участвует) |
| Портфолио «МП-бэк» (`PortfolioApi`, `portfolioApi`, пути `/portfolio/...`) | `https://school.mos.ru` | `familyApiInstance`: familyBaseurl → origin := `getHost()+getApiVersion()` (из remote-config `mapi_host_config`) | тот же хост, что у family/mobile: `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/...` (для школы `/api/family/mobile/v1/portfolio/...`). Точное значение приходит из remote-config; путь profeducation/family/mobile/v1 уже проверен клиентом OpenMES для других методов |
| Новости (`NewsApi`) | `https://school.mos.ru/api/news/v2` | `schoolApiInstance` (schoolBaseurl) | `https://school.mos.ru/api/news/v2` |
| Рейтинг (`RatingApi`) | `https://school.mos.ru/api/ej/rating/v1` | `schoolApiInstance` (schoolBaseurl) | `https://school.mos.ru/api/ej/rating/v1` — жёстко, не через getEjHost; **вживую у колледжа работает `/api/profeducation/rating/v1`** (`/api/ej` — 403) |

`schoolBaseurl` подставляет `getSchoolHost(state)`: для `po` = remote-config региона `apiSchoolUrl`, иначе `https://school.mos.ru`
(getDefaultSchoolHost; test/dev — school-test / school-dev).

### Почему `/api/portfolio/app` и `/portfolio/app` оба встречаются
- `getPortfolioHost(state) = getSchoolHost(state) + "/portfolio"` (модуль environmentSelectors, стр. ~1506492). Его используют **ручные**
  запросы: `apiRequestPortfolio` (createUrl = getPortfolioHost + `/app` + endpoint) — чек-ины культурных мест, загрузка вложений
  (`/portfolio/app/attachment`), PDF культурного паспорта (`/portfolio/app/cultural/pdf`), а также `getWebViewPortfolioUrl`
  (= getPortfolioHost + `/webview[/route]` — веб-вью портфолио).
- Сгенерированный клиент `PortfolioExternalApi` ходит в `/api/portfolio/app` (basePath). В интерсепторе `externalPortfolioBaseurl`
  есть ветка для dev: `origin + "/portfolio/app"` → `https://portfolio-dev.mos.ru/app`, т.е. `/portfolio/app` — это «родной» путь
  веб-приложения портфолио, а `/api/portfolio/app` — тот же бэкенд через API-шлюз school.mos.ru. На проде оба префикса ведут в один
  сервис (что и подтверждает рабочий OpenMES на `/portfolio/app/persons/{guid}/...`).
- Пути, которыми пользуется OpenMES (`/persons/{guid}/events`, `rewards`, `sport-rewards`, `academic-performance`, `proforientation`),
  **в бандле мобильного приложения отсутствуют** — это API веб-портфолио. Мобильное приложение использует `.../events/list`,
  `.../rewards/list`, `.../academic/performance/average`, `.../v2/academic-performance/average-mark` и т.д. (см. ниже).

## 2. Заголовки и авторизация

Интерсептор `commonHeaders` (во всех перечисленных инстансах):
- `client-type: diary-mobile`
- `X-Mes-Subsystem: familypom` (константа X_MES_SUBSYSTEM = selectByBuild {default: familymp, po: familypom}); в спецификации новостей прямо: «для Колледж: familypom»
- `Accept-Language: ru`
- `Profile-id: <currentProfileId>` (если число)
- `X-Mes-RoleId: <profileRoleId>` (если есть)
- `Authorization: Bearer <token>` — через Configuration.accessToken (setBearerAuthToObject в каждом методе).

Особенности:
- **Портфолио-сервис** (`portfolioGatewayApiInstance`): интерсептор `aupdCurrentRoleCookie` сначала очищает все куки, затем ставит
  `Cookie: aupd_current_role=4:2` (строка жёстко в коде, без selectByBuild). Ручная ссылка на вложение тоже ставит куку
  `aupd_current_role` на домен `.mos.ru`.
- **Портфолио «МП-бэк»**: заголовок `x-mes-role` (обяз. по спецификации) = `profile.getMesRole` (1 — ученик, 2 — родитель);
  `personId` в query = contingent_guid.
- **apikey**: **отдельного apikey в коде нет**. Строка `'apikey'` встречается только в
  Yandex-геокодере. `Configuration.apiKey` у всех клиентов = тот же токен (getToken); `setApiKeyToObject` вызывается лишь в
  3 методах PortfolioExternal — `GET /persons/{personId}/academic/performance/average`, `GET /persons/{personId}/share/list`, `POST /attachment` — это заголовок `Auth-Token: <тот же токен>`.
- **service_id** (новости): АУПД-id продукта `SERVICE_ID` / `STORIES_V3_SERVICE_ID` = {default: 7, po: 59} → **59**;
  в событиях appmetrica/анонсов — {default: 1, po: 32} → 32.

## 3. Портфолио — ручные запросы вне спецификаций (apiRequestPortfolio / XHR)

Все — на `getPortfolioHost + /app` = `https://school.mos.ru/portfolio/app`, `Authorization: Bearer`.
- `POST /portfolio/app/persons/{personGuid}/checkIn` — отметка посещения культурного места (тело: date, description, event{id,type}, institution, organizer, attachment). **Похоже только для школы** («Культурный марафон»/паспорт).
- `GET /portfolio/app/persons/checkIn/organization/list?geocodeX=&geocodeY=` — список культурных организаций рядом. **Похоже только для школы.**
- `POST /portfolio/app/attachment` — загрузка файла (multipart `document`).
- `GET /portfolio/app/cultural/pdf` — PDF культурного паспорта (заголовки authorization, x-mes-subsystem). **Похоже только для школы.**

## 4. Портфолио «МП-бэк» (PortfolioApi, тег portfolio) — 28 методов
База колледжа: `https://school.mos.ru/api/profeducation/family/mobile/v1` (школа: `/api/family/mobile/v1`).

#### GET /portfolio/science
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/science`
- Описание: Метод для экрана наука
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `scienceReward`: ScienceReward — Объект наград ScienceReward
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<ScienceRewardData> — Массив наград
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `scienceProject`: ScienceProject — Объект проектов scienceProject
      - `status`: string — Статус
      - `data`: array<ScienceProjectData> — Массив проектов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `scienceEmployment`: ScienceEmployment — Объект занятий scienceEmployment
      - `status`: string — Статус
      - `data`: array<ScienceEmploymentData> — Массив занятий
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `scienceEvent`: ScienceEvent — Объект конкурсов scienceEvent
      - `status`: string — Статус
      - `data`: array<ScienceEventData> — Массив конкурсов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
- **Примечание:** Используется приложением (portfolioApi.getPortfolioScience).

#### GET /portfolio/sport
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/sport`
- Описание: Метод получения сущностей на экране Спорт
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `sportReward`: SportReward — Объект со спортивными наградами
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<SportRewardData> — Массив спортивных наград
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `sportClub`: SportClub — Объект со спортивными клубами и командами
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<SportClubData> — Массив спортивных клубов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `sportUnit`: SportUnit — Объект со спортивными кружками и секциями
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<SportUnitData> — Массив со спортивными кружками и секциями
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `sportGame`: SportGame — Объект с соревнованиями
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<SportGameData> — Массив спортивных соревнований
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `hike`: Hike — Объект с походами и экспедициями
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<HikeData> — Массив с походами и экспедициями
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
- **Примечание:** Используется приложением.

#### GET /portfolio/culture
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/culture`
- Описание: Метод получения сущностей на экране Культура
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `offlineVisit`: OfflineVisit — Объект с посещениями культурных учреждений
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<OfflineVisitData> — Может быть пустым массивом
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `onlineVisit`: OnlineVisit — onlineVisit
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<OnlineVisitData> — Может быть пустым массивом
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
- **Примечание:** В коде вызывается только v2 (getPortfolioCultureV2); v1 оставлен в клиенте.

#### GET /portfolio/v2/culture
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/v2/culture`
- Описание: Метод получения сущностей на экране Культура
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `offlineVisit`: OfflineVisitv2 — Объект с посещениями культурных учреждений
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<OfflineVisitDatav2> — Может быть пустым массивом
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `onlineVisit`: OnlineVisit — onlineVisit
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `data`: array<OnlineVisitData> — Может быть пустым массивом
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
- **Примечание:** Используется приложением.

#### GET /portfolio/creation
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/creation`
- Описание: Метод для экрана "Творчество"
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `creationReward`: CreationReward — Массив наград, достижений
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CreationRewardData> — Массив наград, достижений
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `creationAffiliation`: CreationAffiliation — Объект творческих коллективов
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CreationAffiliationData> — Массив творческих коллективов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `creationEvent`: CreationEvent — Объект конкурсов
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CreationEventData> — Массив конкурсов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `creationEmployment`: CreationEmployment — Объект кружков на ЭФ "Творчество"
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CreationEmploymentData> — Массив кружков
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
- **Примечание:** Используется приложением.

#### GET /portfolio/civil
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/civil`
- Описание: Метод для экрана "Гражданская активность"
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `civilReward`: CivilReward — Объект наград для ЭФ "Гражданская активность"
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CivilRewardData> — Массив наград для ЭФ Гражданская активность
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `civilAffiliation`: CivilAffiliation — Объект клубов для ЭФ "Гражданская активность"
      - `status`: string — Статус.Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CivilAffiliationData> — Массив объектов клубов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `civilEvent`: CivilEvent — Объект конкурсов для ЭФ "Гражданская активность"
      - `status`: string — Статус.Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CivilEventData> — Массив конкурсов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `civilEmployment`: CivilEmployment — Объект кружков на ЭФ "Гражданская активность"
      - `status`: string — Статус.Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<CivilEmploymentData> — Массив кружков
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
- **Примечание:** Используется приложением.

#### GET /portfolio/profession
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/profession`
- Описание: Метод получения сущностей на экране Моя профессия
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `professionReward`: ProfessionReward — Объект наград за профессиональные успехи
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
      - `data`: array<ProfessionRewardData> — Массив наград
    - `professionEducation`: ProfessionEducation — Объект с документами о проф. обучении
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
      - `data`: array<ProfessionEducationData> — Масив проф. обучений
    - `professionEvent`: ProfessionEvent — Объект с мероприятиями
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
      - `data`: array<ProfessionEventData>
    - `professionWorldskills`: ProfessionWorldskills — Объект с результатами ГИА по стандарту World Skills Russia
      - `status`: string — Результат. Значения: ОК - данные получены успешно. FAIL - ошибка взаимодействия.
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
      - `data`: array<ProfessionWorldskillsData>
    - `professionTesting`: ProfessionTesting — ProfessionTesting
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: ProfessionTestingData — ProfessionTestingData
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `professionRecommendations`: ProfessionRecommendations — ProfessionRecommendations
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: ProfessionRecommendationData — ProfessionRecommendationData
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `professionTrials`: ProfessionTrials — ProfessionTrials
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<ProfessionTrialData>
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `professionTrialRecommendations`: ProfessionTrialRecommendations — ProfessionTrialRecommendations
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<ProfessionTrialRecommendationData>
      - `filter`: ProfessionRecommendationTrialFilter — ProfessionRecommendationTrialFilter
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `professionExcursionRecommendations`: ProfessionExcursionRecommendations — ProfessionExcursionRecommendations
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<ProfessionExcursionRecommendationData>
      - `filter`: ProfessionRecommendationExcursionFilter — ProfessionRecommendationExcursionFilter
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `professionDodRecommendations`: ProfessionDodRecommendations — ProfessionTrialRecommendations
      - `status`: string — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - … (ещё 23 строк)
- **Примечание:** Используется приложением. Для колледжа, вероятно, самый содержательный раздел (профессия, WorldSkills/демоэкзамен — см. mapProfessionSkills, professionWorldskills в коде).

#### GET /portfolio/about
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/about`
- Описание: Метод для экрана "Обо мне"
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - query `schoolId`: string, обяз. — Идентификатор школы.
  - query `classLevel`: string, обяз. — Идентификатор параллели
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `aboutReward`: AboutReward — Объект со всеми наградами
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<AboutRewardData> — Массив со всеми наградами
      - `filter`: array<CategoryData> — Отфильтрованный массив категорий
      - `error`: string — Описание ошибки
    - `aboutInterest`: AboutInterest — Объект анкеты интересов
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<AboutInterestData> — Массив анкет интересов
      - `filter`: array<CategoryData> — Отфильтрованный массив категорий
      - `error`: string — Описание ошибки
    - `aboutRecommendationInterest`: AboutRecommendationInterest — Объект рекомендаций: кружки и худ. литература
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: AboutRecommendationInterestData — Рекомендации: кружки и худ.литиратура
      - `filter`: array<CategoryData> — Отфильтрованный массив категорий
      - `error`: string — Описание ошибки
    - `aboutRecommendationClasses`: AboutRecommendationClasses — Объект рекомендаций: предпрофильных классов и образовательны вертикалей
      - `status`: string — Статус. Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<AboutRecommendationClassesData> — Массив рекомендаций: предпрофильных классов и образовательных вертикалей
      - `error`: string — Описание ошибки
- **Примечание:** **Похоже только для школы.** Требует schoolId и classLevel (параллель) — школьные понятия; у колледжа classLevel может не совпасть.

#### GET /portfolio/items/{entityType}/{id}
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/items/{entityType}/{id}`
- Описание: Метод получения информации по каждой сущности в Портфолио для отображения детальных карточек.
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - path `entityType`: string, обяз. — Тип сущности для поиска
  - path `id`: integer, обяз. — Идентификатор элемента
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `error`: string — Описание ошибки при FAIL
    - `data`: ItemData — ItemData
      - `id`: integer — Идентификатор записи
      - `name`: string — Наименование записи
      - `entityType`: string — Тип запрашиваемой сущности (к примеру конкурс)
      - `entityName`: string — Наименвоание связаной сущности
      - `date`: string — Дата получения нагдады
      - `rewardNumber`: string — Номер награды
      - `description`: string — Описание сущности
      - `source`: ItemSource — ItemSource
      - `creationDate`: string(date-time) — Дата создания записи
      - `editDate`: string — Дата редактирования записи
      - `fileReferences`: array<FileReferences>
      - `startDate`: string — Дата начала события
      - … (ещё 39 строк)
- **Примечание:** В коде вызывается только v2.

#### GET /portfolio/v2/items/{entityType}/{id}
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/v2/items/{entityType}/{id}`
- Описание: Метод получения информации по каждой сущности в Портфолио для отображения детальных карточек.
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - path `entityType`: string, обяз.
  - path `id`: string, обяз.
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `data`: ItemDatav2 — ItemDatav2
      - `id`: string — Идентификатор записи
      - `name`: string — Наименование записи
      - `entityType`: string — Тип запрашиваемой сущности (к примеру конкурс)
      - `entityName`: string — Наименвоание связаной сущности
      - `date`: string — Дата получения нагдады
      - `rewardNumber`: string — Номер награды
      - `description`: string — Описание сущности
      - `source`: ItemSource — ItemSource
      - `creationDate`: string(date-time) — Дата создания записи
      - `editDate`: string — Дата редактирования записи
      - `fileReferences`: array<FileReferences>
      - `startDate`: string — Дата начала события
      - … (ещё 55 строк)
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `error`: string — Описание ошибки при FAIL
- **Примечание:** Детальная карточка; entityType/id берутся из списков разделов.

#### GET /portfolio/settings
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/settings`
- Описание: Метод для получения настроек отображения блоков Портфолио
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `sections`: GetSectionSettings — GetSectionSettings
      - `status`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<SectionSettings> — Массив секций
      - `themeSetting`: integer — Идентификатор темы
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### POST /portfolio/settings
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/settings`
- Описание: Метод для сохранения настроек отображения блоков портфолио
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Тело (application/json): object
    - `settings`: array<UpdateSectionSettings>
      - `sectionId`: integer — Идентификатор секции
      - `isVisible`: boolean — Булевое значение, виден ли раздел пользователю: -true: раздел отображается для пользователя -false: 
- Ответ 200: без тела

#### GET /portfolio/interests
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/interests`
- Описание: Метод для получения справочников для заполнения анкеты интересов
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - query `entity`: string, обяз. — Идентификатор справочника. Возможные значения. head, action, activity, cookery, sport, animals, character, cinema, collection, cultural, dan
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: object
    - `head`: InterestHead — InterestHead
      - `status`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<InterestHeadData> — Массив категорий интересов
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `action`: InterestAction — InterestAction
      - `status`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
      - `data`: array<InterestActionData> — Массив действий
      - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"
    - `interests`: object
      - `activity`: InterestModel — InterestModel
      - `cookery`: InterestModel — InterestModel
      - `sport`: InterestModel — InterestModel
      - `animals`: InterestModel — InterestModel
      - `character`: InterestModel — InterestModel
      - `cinema`: InterestModel — InterestModel
      - `collection`: InterestModel — InterestModel
      - `cultural`: InterestModel — InterestModel
      - `dance`: InterestModel — InterestModel
      - `design`: InterestModel — InterestModel
      - `fashion`: InterestModel — InterestModel
      - `game`: InterestModel — InterestModel
      - … (ещё 13 строк)

#### POST /portfolio/interests
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/interests`
- Описание: Метод для добавления интересов
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Тело (application/json): array<InterestAddModel>
    - `interestActionCode`: array<integer> — Массив кодов действий
    - `subinterestCode`: array<integer> — Массив кодов дочерних интересов
    - `interestCode`: integer — Код интереса
    - `interestHeadCode`: integer — Код категории интереса
    - `sourceCode`: integer — Код источника. Равен 10
- Ответ 200: без тела

#### PUT /portfolio/interests/{interestId}
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/interests/{interestId}`
- Описание: Метод для редактирования интереса
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - path `interestId`: string, обяз. — Id интереса
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Тело (application/json): InterestAddModel
    - `interestActionCode`: array<integer> — Массив кодов действий
    - `subinterestCode`: array<integer> — Массив кодов дочерних интересов
    - `interestCode`: integer — Код интереса
    - `interestHeadCode`: integer — Код категории интереса
    - `sourceCode`: integer — Код источника. Равен 10
- Ответ 200: без тела

#### DELETE /portfolio/interests/{interestId}
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/interests/{interestId}`
- Описание: Метод для удаления интереса
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - path `interestId`: string, обяз. — Id интереса
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: без тела

#### GET /portfolio/directories/events
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/directories/events`
- Описание: Получение справочников для создания мероприятий
- Параметры:
  - query `type`: string ["science", "sport", "hike", "olympiad", "civil", "creation", "profession"], необяз. — Вид сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: DirectoryEvent — DirectoryEvent
      - `sections`: array<DirectorySection>
      - `subcategories`: array<DirectorySubcategory>
      - `creationKinds`: array<DirectoryCreationKind>
      - `subjects`: array<DirectorySubject>
      - `disciplines`: array<DirectoryDiscipline>
      - `eventKinds`: array<DirectoryEventKind>
      - `sportKinds`: array<DirectorySportKind>
      - `tourismKinds`: array<DirectoryTourismKind>
      - `eventLevels`: array<DirectoryLevel>
      - `olympiadLevels`: array<DirectoryLevel>
      - `formats`: array<DirectoryFormat>
    - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### GET /portfolio/directories/projects
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/directories/projects`
- Описание: Получение справочников для создания проектов
- Параметры:
  - query `type`: string ["science", "sport", "hike", "olympiad", "civil", "creation", "profession"], необяз. — Вид сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: DirectoryProject — DirectoryProject
      - `sections`: array<DirectorySection>
      - `subcategories`: array<DirectorySubcategory>
      - `disciplines`: array<DirectoryDiscipline>
      - `eventLevels`: array<DirectoryLevel>
      - `projectFormats`: array<DirectoryFormat>
    - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### GET /portfolio/directories/affiliations
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/directories/affiliations`
- Описание: Получение справочников для создания принадлженостей
- Параметры:
  - query `type`: string ["science", "sport", "hike", "olympiad", "civil", "creation", "profession"], необяз. — Вид сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: DirectoryAffiliation — DirectoryAffiliation
      - `sections`: array<DirectorySection>
      - `subcategories`: array<DirectorySubcategory>
      - `affiliationKinds`: array<DirectoryAffiliationKind>
      - `eventLevels`: array<DirectoryLevel>
      - `creationKinds`: array<DirectoryCreationKind>
      - `sportKinds`: array<DirectorySportKind>
      - `tourismKinds`: array<DirectoryTourismKind>
      - `sportClubs`: array<DirectorySportClub>
      - `trainingStages`: array<DirectoryTrainingStage>
    - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### GET /portfolio/directories/employments
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/directories/employments`
- Описание: Получение справочников для создания занятостей
- Параметры:
  - query `type`: string ["science", "sport", "hike", "olympiad", "civil", "creation", "profession"], необяз. — Вид сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: DirectoryEmployment — DirectoryEmployment
      - `sections`: array<DirectorySection>
      - `sportKinds`: array<DirectorySportKind>
      - `tourismKinds`: array<DirectoryTourismKind>
      - `subcategories`: array<DirectorySubcategory>
      - `creationKinds`: array<DirectoryCreationKind>
      - `disciplines`: array<DirectoryDiscipline>
      - `formats`: array<DirectoryFormat>
    - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### GET /portfolio/directories/rewards
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/directories/rewards`
- Описание: Получение справочников для создания наград
- Параметры:
  - query `type`: string ["science", "sport", "hike", "olympiad", "civil", "creation", "profession"], необяз. — Вид сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: DirectoryReward — DirectoryReward
      - `sections`: array<DirectorySection>
      - `events`: array<DirectoryEventProjectModel>
      - `projects`: array<DirectoryEventProjectModel>
      - `rewardKinds`: array<DirectoryRewardKind>
      - `eventLevels`: array<DirectoryLevel>
      - `subcategories`: array<DirectorySubcategory>
    - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### GET /portfolio/directories/sport-rewards
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/directories/sport-rewards`
- Описание: Получение справочников для создания спортивных наград
- Параметры:
  - query `type`: string ["science", "sport", "hike", "olympiad", "civil", "creation", "profession"], необяз. — Вид сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: DirectorySportReward — DirectorySportReward
      - `sections`: array<DirectorySection>
      - `events`: array<DirectoryEventProjectModel>
      - `sportRewardKinds`: array<DirectorySportRewardKind>
      - `sportKinds`: array<DirectorySportKind>
      - `tourismKinds`: array<DirectoryTourismKind>
      - `sportAges`: array<DirectorySportAge>
      - `eventLevels`: array<DirectoryLevel>
    - `error`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при status == "FAIL"

#### POST /portfolio/entitites
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/entitites`
- Описание: Создание сущности портфолио
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): Entity
    - `categoryCode`: integer — ИД категории
    - `sourceCode`: integer — ИД источника из справочника
    - `creatorId`: string — Идентификатор МЭШ родителя, добавившего сущность (meshId) ИЛИ Идентификатор МЭШ учащегося, добавивше
    - `name`: string — Наименование сущности
    - `typeCode`: integer — ИД типа сущности
    - `dataKind`: integer — ИД вида данных
    - `isDelete`: boolean — Признака удаленности записи
    - `isImport`: boolean — Признак импорта
    - `parallelsCode`: string — Возрастное ограничение
    - `levelCode`: integer — Уровень
    - `organizators`: string — Организаторы
    - `subjectCode`: string — ИД предмета
    - `startDate`: string(date) — Дата начала
    - `formatCode`: integer — ИД формата
    - `attachment`: array<EntityAttachement> — Вложение
      - `id`: integer — Идентификатор пользовательского вложения
      - `name`: string — Название вложения
      - `isDelete`: boolean — Признак удаленности пользовательского вложения
    - `linkedObjects`: array<EntityLinkedObject> — Список связанных объектов
      - `entityId`: integer — ИД связанного объекта
    - … (ещё 44 строк)
- Ответ 200: без тела

#### PUT /portfolio/entitites/{entityId}
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/entitites/{entityId}`
- Описание: Редактирование сущности Портфолио
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - path `entityId`: integer, обяз. — Идентификатор сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): Entity
    - `categoryCode`: integer — ИД категории
    - `sourceCode`: integer — ИД источника из справочника
    - `creatorId`: string — Идентификатор МЭШ родителя, добавившего сущность (meshId) ИЛИ Идентификатор МЭШ учащегося, добавивше
    - `name`: string — Наименование сущности
    - `typeCode`: integer — ИД типа сущности
    - `dataKind`: integer — ИД вида данных
    - `isDelete`: boolean — Признака удаленности записи
    - `isImport`: boolean — Признак импорта
    - `parallelsCode`: string — Возрастное ограничение
    - `levelCode`: integer — Уровень
    - `organizators`: string — Организаторы
    - `subjectCode`: string — ИД предмета
    - `startDate`: string(date) — Дата начала
    - `formatCode`: integer — ИД формата
    - `attachment`: array<EntityAttachement> — Вложение
      - `id`: integer — Идентификатор пользовательского вложения
      - `name`: string — Название вложения
      - `isDelete`: boolean — Признак удаленности пользовательского вложения
    - `linkedObjects`: array<EntityLinkedObject> — Список связанных объектов
      - `entityId`: integer — ИД связанного объекта
    - … (ещё 44 строк)
- Ответ 200: без тела

#### DELETE /portfolio/entitites/{entityId}
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/entitites/{entityId}`
- Описание: Удаление сущности Портфолио
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
  - query `typeCode`: integer, обяз. — Тип сущности
  - path `entityId`: integer, обяз. — Идентификатор сущности
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: без тела

#### GET /portfolio/proforientation/status
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/proforientation/status`
- Описание: —
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: ProforientationStatus
    - `status`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `error`: string — Описание ошибки
    - `data`: ProforientationStatusData — ProforientationStatusData
      - `visited`: boolean — Статус просмотра учеником результатов профориентации
      - `found`: boolean — Статус наличия результатов профориентации
- **Примечание:** **Похоже только для школы.** Статус профориентации (у OpenMES работает веб-аналог /portfolio/app/persons/{guid}/proforientation).

#### POST /portfolio/proforientation/status
- URL (колледж): `https://school.mos.ru/api/profeducation/family/mobile/v1/portfolio/proforientation/status`
- Описание: —
- Параметры:
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: ProforientationPostStatus
    - `status`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `error`: string — Описание ошибки

## 5. Портфолио-сервис (PortfolioExternalApi, тег portfolio-external) — 26 методов
База: `https://school.mos.ru/api/portfolio/app` (альтернатива, работающая у OpenMES: `https://school.mos.ru/portfolio/app`). `personId` в пути = `{personGuid}`.

#### GET /persons/{personId}/academic/performance/average
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/academic/performance/average`
- Описание: Метод бэка Портфолио. Возвращает список средних оценок
- Параметры:
  - path `personId`: string, обяз. — Идентификатор персоны
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: object — Объект с данными
      - `year`: array<AverageYear>
- **Примечание:** Используется (getAcademicPerformanceAverage). Средние по предметам.

#### GET /persons/{personId}/govexams/list
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/govexams/list`
- Описание: Метод бэка Портфолио. Метод получения информация по ГИА.
- Параметры:
  - path `personId`: string, обяз. — Идентификатор персоны
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string
    - `data`: array<ExamData>
      - `id`: integer — Идентификатор записи
      - `name`: string — Предмет экзамена
      - `date`: string(date) — Дата сдачи
      - `formaGia`: string — Формат экзамена
      - `normalizedMarkValue`: string — Нормализованная оценка ученика
      - `normalizedMarkBasis`: string — Нормализованный максимальный балл
      - `primaryMarkValue`: integer — Баллы ученика за экзамен
      - `primaryMarkBasis`: integer — Максимальный балл за экзамен
      - `examsId`: integer — Идентификатор экзамена
      - `approbation`: boolean — Признак, пробный ли экзамен: true- экзамен пробный false- экзамен не пробный
      - `subjectID`: integer — Идентификатор предмета
      - `positiveResultThreshold`: integer — Порог прохождения экзамена
      - … (ещё 4 строк)
- **Примечание:** **Похоже только для школы.** ГИА (ОГЭ/ЕГЭ).

#### GET /persons/{personId}/events/list
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/events/list`
- Описание: Метод бэкенда Портфолио. Возвращает список событий пользователя (в том числе олимпиад)
- Параметры:
  - path `personId`: string, обяз. — Идентификатор персоны
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: array<EventData> — Массив событий
      - `id`: integer — Идентификатор объекта
      - `personId`: string — Идентификатор персоны
      - `fileReferences`: array<FileReferences> — Массив прикрепленных файлов
      - `creationDate`: string(date-time) — Дата создания
      - `editDate`: string(date-time) — Дата изменения
      - `creatorId`: string — Идентификатор человека, добавившего объект
      - `name`: string — Наименование объекта
      - `isDelete`: boolean — Признак удаленности
      - `source`: Source — Source
      - `category`: Category — Category
      - `subcategory`: string
      - `dataKind`: integer — Этот параметр мы не используем
      - … (ещё 46 строк)
- **Примечание:** Используется (getEventsList). Мероприятия/олимпиады.

#### GET /persons/{personId}/diagnostic/independent-rating
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/diagnostic/independent-rating`
- Описание: Метод бэкенда Портфолио. Возвращает рейтинг по региону, школе и классу
- Параметры:
  - path `personId`: string, обяз. — Идентификатор персоны
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: array<object> — Массив с данными
      - `learningYear`: string — Учебный год
      - `independentDiagnosticRating`: array<IndependentDiagnosticRating>
- **Примечание:** Используется. Ранее проверено вживую: у колледжа путь /portfolio/app/persons/{guid}/diagnostic/independent-rating существует.

#### GET /persons/{personId}/diagnostic/general-rating
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/diagnostic/general-rating`
- Описание: Метод бэкенда Портфолио. Возвращает доп. информацию по диагностикам
- Параметры:
  - path `personId`: string, обяз. — Идентификатор персоны
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: array<object> — Массив с данными
      - `learningYear`: string — Учебный год
      - `generalRating`: array<GeneralRating>
- **Примечание:** **Похоже только для школы.** В коде клиентский метод есть, вызовов не найдено.

#### GET /persons/{personId}/rewards/list
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/rewards/list`
- Описание: Метод бэкенда Портфолио. Возвращает список наград
- Параметры:
  - query `size`: number, обяз. — Кол-во объектов в ответе метода
  - path `personId`: string, обяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат
    - `data`: array<RewardListItem> — Массив объектов с наградами
      - `id`: number — Идентификатор объекта
      - `personId`: string — Идентификатор персоны
      - `fileReferences`: array<RewardFileReferences> — Массив прикрепленных файлов
      - `creationDate`: string — Дата создания
      - `editDate`: string — Дата изменения
      - `name`: string — Наименование объекта
      - `source`: RewardSource — Источник награды
      - `category`: RewardCategory — Информация о категории у награды
      - `linkedObjectIds`: string — Идентификаторы связанных объектов
      - `description`: string — Описание к объекту
      - `categoryCode`: number — Код категории типа
      - `rewardNumber`: string — Номер награды
      - … (ещё 17 строк)
- **Примечание:** Используется (getRewardsList). Награды.

#### GET /reference/olympiad/subject
- URL (колледж): `https://school.mos.ru/api/portfolio/app/reference/olympiad/subject`
- Описание: Метод получение списка всех предметов. Метод бэкенда Портфолио.
- Параметры:
  - query `size`: number, обяз. — Кол-во объектов в ответе метода
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: SubjectData — SubjectData
      - `content`: array<SubjectContent> — Список предметов
      - `empty`: boolean — Признак наличия ответа
      - `first`: boolean — Признак того, что ответ данного метода первый при пагинации
      - `last`: boolean — Признак того, что ответ данного метода последний при пагинации
      - `number`: integer — Номер страницы пагинации
      - `numberOfElements`: integer — Количество элементов на странице пагинации
      - `pageable`: SubjectPageable — SubjectPageable
      - `size`: integer — Кол-во объектов в ответе
      - `sort`: SubjectSort — SubjectSort
      - `totalElements`: string — Общее количество элементов на странице пагинации
      - `totalPages`: string — Общее количество страниц пагинации
- **Примечание:** **Похоже только для школы.** Справочник предметов олимпиад.

#### GET /reference/teacher/{schoolId}
- URL (колледж): `https://school.mos.ru/api/portfolio/app/reference/teacher/{schoolId}`
- Описание: Метод получение списка всех учителей школьника. Метод бэкенда Портфолио.
- Параметры:
  - path `schoolId`: integer, обяз. — global_school_id из профиля
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: array<TeacherData>
      - `staffId`: number — Идентификатор сотрудника
      - `lastName`: string — Фамилия учителя
      - `firstName`: string — Имя учителя
      - `patronymic`: string — Отчество учителя
- **Примечание:** **Похоже только для школы.** Учителя школьника (для «Спасибо учителю»). В спецификации ошибочно указан path-параметр personId, в пути его нет.

#### POST /persons/{personId}/gratitude-teacher
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/gratitude-teacher`
- Описание: Метод сохранения благодарности учителю. Метод бэкенда Портфолио.
- Параметры:
  - path `personId`: string, обяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Тело (application/json): object
    - `creatorId`: string — Mesh_id пользователя
    - `entityId`: integer — Идентификатор мероприятия
    - `entityType`: string — Тип мероприятия
    - `gratitudeText`: string — Текст благодарности
    - `personId`: string — Идентификатор персоны
    - `recordId`: string — Идентификатор записи в ClickHouse
    - `sourceCode`: integer — Код источника благодраности
    - `staffId`: integer — Идентификатор учителя, которому выражают благодарность
    - `subjectCode`: integer — Код учебного предмета
    - `teacherFIO`: string — ФИО учителя
    - `yearEducation`: integer — Год обучения
- Ответ 200: object
    - `data`: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
- **Примечание:** **Похоже только для школы.** «Спасибо учителю».

#### GET /persons/{personId}/error-message
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/error-message`
- Описание: Получение отправленных ошибок пользователем по сущности
- Параметры:
  - query `entityType`: string, обяз. — Тип сущности
  - query `entityId`: integer, обяз. — Идентификатор сущности
  - path `personId`: string, обяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`:  ["OK", "FAIL"] — **ОК** - все отрабатывает. **FAIL** - Ошибка.
    - `data`: array<ErrorMessageData>
      - `errorId`: integer
      - `personId`: string(uuid) — Идентификатор пользователя
      - `entityId`: integer — Идентификатор сущности
      - `entityType`: EntityTypeEnum — Тип сущности в портфолио
      - `recordId`: integer — Идентификатор записи
      - `errorTypeCode`:  [1, 2] — Тип ошибки - 1 - Ошибки в данных - 2 - Данные не обо мне
      - `errorGeneralMessage`: string — Текст ошибки для поля общих данных
      - `errorFileMetadataMessage`: string — Текст ошибки для поля приложенных данных
      - `errorChildEntityMessage`: string — Текст ошибки для поля связанных данных
    - `error`: string

#### POST /persons/{personId}/error-message
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/error-message`
- Описание: Создание нового сообщения об ошибке пользователем
- Параметры:
  - path `personId`: string, обяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Тело (application/json): ErrorMessageEdit
    - `errorId`: integer — Код ошибки
    - `personId`: string — Идентификатор пользователя
    - `entityId`: integer — Идентификатор сущности
    - `entityType`: EntityTypeEnum — Тип сущности в портфолио
    - `recordId`: integer — Идентификатор записи
    - `errorTypeCode`:  [1, 2] — Тип ошибки - 1 - Ошибки в данных - 2 - Данные не обо мне
    - `errorGeneralMessage`: string — Текст ошибки для поля общих данных
    - `errorFileMetadataMessage`: string — Текст ошибки для поля приложенных данных
    - `errorChildEntityMessage`: string — Текст ошибки для поля связанных данных
- Ответ 200: object
    - `result`:  ["OK", "FAIL"]
    - `data`: array<ErrorMessageData>
      - `errorId`: integer
      - `personId`: string(uuid) — Идентификатор пользователя
      - `entityId`: integer — Идентификатор сущности
      - `entityType`: EntityTypeEnum — Тип сущности в портфолио
      - `recordId`: integer — Идентификатор записи
      - `errorTypeCode`:  [1, 2] — Тип ошибки - 1 - Ошибки в данных - 2 - Данные не обо мне
      - `errorGeneralMessage`: string — Текст ошибки для поля общих данных
      - `errorFileMetadataMessage`: string — Текст ошибки для поля приложенных данных
      - `errorChildEntityMessage`: string — Текст ошибки для поля связанных данных
    - `error`: string

#### PUT /persons/{personId}/error-message
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/error-message`
- Описание: Редактирование сообщения об ошибке
- Параметры:
  - path `personId`: string, обяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Тело (application/json): ErrorMessageEdit
    - `errorId`: integer — Код ошибки
    - `personId`: string — Идентификатор пользователя
    - `entityId`: integer — Идентификатор сущности
    - `entityType`: EntityTypeEnum — Тип сущности в портфолио
    - `recordId`: integer — Идентификатор записи
    - `errorTypeCode`:  [1, 2] — Тип ошибки - 1 - Ошибки в данных - 2 - Данные не обо мне
    - `errorGeneralMessage`: string — Текст ошибки для поля общих данных
    - `errorFileMetadataMessage`: string — Текст ошибки для поля приложенных данных
    - `errorChildEntityMessage`: string — Текст ошибки для поля связанных данных
- Ответ 200: object
    - `result`:  ["OK", "FAIL"]
    - `data`: array<ErrorMessageData>
      - `errorId`: integer
      - `personId`: string(uuid) — Идентификатор пользователя
      - `entityId`: integer — Идентификатор сущности
      - `entityType`: EntityTypeEnum — Тип сущности в портфолио
      - `recordId`: integer — Идентификатор записи
      - `errorTypeCode`:  [1, 2] — Тип ошибки - 1 - Ошибки в данных - 2 - Данные не обо мне
      - `errorGeneralMessage`: string — Текст ошибки для поля общих данных
      - `errorFileMetadataMessage`: string — Текст ошибки для поля приложенных данных
      - `errorChildEntityMessage`: string — Текст ошибки для поля связанных данных
    - `error`: string

#### GET /attachment
- URL (колледж): `https://school.mos.ru/api/portfolio/app/attachment`
- Описание: Метод получения прикрепленного файла. Метод бэкенда Портфолио.
- Параметры:
  - query `id`: string, необяз. — Идентификатор файла
  - query `personId`: string, необяз. — Идентификатор ученика. Необходимо получать из кеша метода GET /profile, параметр: children.contingent_guid
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
- **Примечание:** В коде также собирается вручную: getSchoolUrl + `/api/portfolio/app/attachment?id={id}&personId={personGuid}` (с кукой aupd_current_role).

#### POST /attachment
- URL (колледж): `https://school.mos.ru/api/portfolio/app/attachment`
- Описание: —
- Параметры: нет
- Заголовки/куки по спецификации: DocumentClass, DocumentTitle, X-Mes-Subsystem, personId
- Тело (multipart/form-data): string
- Ответ 200: Attachement
    - `id`: integer — Идентификатор документа
    - `personId`: string(uuid) — Идентификатор персоны, которой принадлежит вложения
    - `typeCode`: AttachementTypeCode — AttachementTypeCode
      - `code`: integer — Код справочника
      - `value`: string — Значение справочника
    - `formatTypeCode`: AttachementTypeCode — AttachementTypeCode
      - `code`: integer — Код справочника
      - `value`: string — Значение справочника
    - `achievementId`: integer
    - `olympiadId`: integer
    - `entityId`: integer
    - `entityType`: string
    - `attachingDatetime`: string(date-time) — Дата пользовательского вложения с временем
    - `centralStorageDocumentId`: string — Идентификатор документа в ЦХЭД
    - `centralStorageDirectLink`: string — Прямая ссылка на документ в ЦХЭД
    - `size`: integer — Размер документа в байтах
    - `name`: string — Наименование документа
    - `attachmentType`: AttachementType — AttachementType
      - `id`: integer
      - `code`: string
      - `value`: integer
    - `attachmentFormatType`: AttachementType — AttachementType
      - `id`: integer
      - `code`: string
      - `value`: integer
    - `deleted`: boolean — Признак того, что запись удалена
    - `creationDate`: string(date-time) — Дата создания записи
    - `modificationDate`: string(date-time) — Дата последнего изменения записи
- **Примечание:** В коде загрузка идёт через XMLHttpRequest на getPortfolioHost + `/app/attachment` = `https://school.mos.ru/portfolio/app/attachment` (без /api), multipart поле `document`, Authorization: Bearer.

#### GET /portfolio/users/context
- URL (колледж): `https://school.mos.ru/api/portfolio/app/portfolio/users/context`
- Описание: Метод позволяет получить контекст пользователя
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Role, X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: ContextData — ContextData
      - `userId`: integer — Идентификатор пользователя в Портфолио Учащегося
      - `aupdId`: integer — Идентификатор АУПД
      - `meshId`: string — Идентификатор пользователя в МЭШ Контингент
      - `oldContingentId`: string — Идентификатор Контингента
      - `staffId`: string — Идентификатор сотрудника
      - `ssoId`: string — Идентификатор СУДИРа
- **Примечание:** Используется.

#### GET /settings/administrators
- URL (колледж): `https://school.mos.ru/api/portfolio/app/settings/administrators`
- Описание: Метод бэка портфолио, передающий администраторские настройки отображения разделов у пользователей
- Параметры:
  - query `sort`: string, обяз. — Порядок сортировки. Передается значение == code
  - query `classLevel`: string, обяз. — Номер параллели. Передается значение из GET /profile "class_level_id:
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: object
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: object — Объект с данными
      - `sections`: array<SettingsSection> — Массив с настройками видимости по секциям
- **Примечание:** Клиентский метод есть, вызовов не найдено. classLevel — параллель из профиля.

#### GET /persons/{personId}/share/list
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/share/list`
- Описание: Метод бэка портфолио, возвращающий список активных ссылок на портфолио
- Параметры:
  - path `personId`: string, обяз. — Ид ученика
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: Share
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: array<ShareData> — Массив ссылок
      - `id`: string — ИД ссылки
      - `personId`: string — ИД персоны
      - `startDate`: string — Начало срока действия ссылки
      - `endDate`: string — Окончание срока действия ссылки
      - `creationDate`: string — Дата создания ссылки
      - `url`: string — Ссылка
      - `isActive`: string — Признак активности ссылок
      - `qrCode`: string — QR-код ссылки в формате base64
      - `display`: ShareDisplay — Список разделов доступных для просмотра портфолио по ссылке
      - `themeSetting`: integer — Ид темы
    - `code`: integer — Код ошибки взаимодействия (при её наличии) Имеет значение только при result == "FAIL"
    - `message`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при result == "FAIL"

#### POST /persons/{personId}/share
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/share`
- Описание: Метод бэка портфолио, создающий новую ссылка для просмотра порфтолио
- Параметры:
  - path `personId`: string, обяз. — Ид ученика
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Тело (application/json): ShareCreate
    - `accessPeriod`: integer — Период доступа Возможные значения: 1 = "Неделя" 2 = "Месяц" 3 = "Год"
    - `customPeriod`: object — Период действия
      - `startDate`: string(date-time) — Дата и время начала периода действия
      - `endDate`: string(date-time) — Дата и время окончания периода действия
    - `display`: ShareDisplay — Список разделов доступных для просмотра портфолио по ссылке
      - `studentData`: boolean — Признак отображения раздела "Данные об ученике" при просмотре портфолио по ссылке
      - `profile`: boolean — Признак отображения раздела "Мой профиль" при просмотре портфолио по ссылке.
      - `interests`: boolean — Признак отображения раздела "Интересы, увлечения и хобби" при просмотре портфолио по ссылке. Не може
      - `studies`: boolean — Признак отображения раздела "Учеба" при просмотре портфолио по ссылке
      - `performance`: boolean — Признак отображения раздела "Успеваемость" при просмотре портфолио по ссылке. Не может быть TRUE, ес
      - `gia`: boolean — Признак отображения раздела "ГИА" при просмотре портфолио по ссылке. Не может быть TRUE, если studie
      - `oge`: boolean — Признак отображения раздела "ОГЭ" при просмотре портфолио по ссылке. Не может быть TRUE, если studie
      - `ege`: boolean — Признак отображения раздела "ЕГЭ" при просмотре портфолио по ссылке. Не может быть TRUE, если studie
      - `gve9`: boolean — Признак отображения раздела "ГВЭ-9" при просмотре портфолио по ссылке. Не может быть TRUE, если stud
      - `gve11`: boolean — Признак отображения раздела "ГВЭ-11" при просмотре портфолио по ссылке. Не может быть TRUE, если stu
      - `other`: boolean — Признак отображения раздела "Другое" при просмотре портфолио по ссылке. Не может быть TRUE, если stu
      - `trainingInfo`: boolean — Признак отображения раздела "Сведения об обучении" при просмотре портфолио по ссылке. Не может быть 
      - … (ещё 41 строк)
    - `themeSetting`: integer — Ид темы
- Ответ 200: ShareCreateResponse
    - `result`:  ["OK", "FAIL"] — Значения: - ОК - все отрабатывает. - FAIL - Ошибка.
    - `data`: ShareData — Информация о ссылке
      - `id`: string — ИД ссылки
      - `personId`: string — ИД персоны
      - `startDate`: string — Начало срока действия ссылки
      - `endDate`: string — Окончание срока действия ссылки
      - `creationDate`: string — Дата создания ссылки
      - `url`: string — Ссылка
      - `isActive`: string — Признак активности ссылок
      - `qrCode`: string — QR-код ссылки в формате base64
      - `display`: ShareDisplay — Список разделов доступных для просмотра портфолио по ссылке
      - `themeSetting`: integer — Ид темы
    - `code`: integer — Код ошибки взаимодействия (при её наличии) Имеет значение только при result == "FAIL"
    - `message`: string — Описание ошибки взаимодействия (при её наличии) Имеет значение только при result == "FAIL"

#### PUT /persons/{personId}/share/activity
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/share/activity`
- Описание: Метод бэка портфолио, изменяющий статус активности ссылок на порфтолио
- Параметры:
  - path `personId`: string, обяз. — Ид ученика
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Тело (application/json): ShareActivity
    - `isActive`: boolean — Признак активации/деактивации ссылок
- Ответ 200: без тела

#### DELETE /persons/{personId}/share/{linkId}
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/share/{linkId}`
- Описание: Метод бэка портфолио, удаляющий ссылку на портфолио
- Параметры:
  - path `personId`: string, обяз. — Ид ученика
  - path `linkId`: string, обяз. — Ид ссылки
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: без тела

#### GET /fos/feedbackLink
- URL (колледж): `https://school.mos.ru/api/portfolio/app/fos/feedbackLink`
- Описание: Метод для получения ссылки на форму обратной связи в Анкете интересов
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `status`:  ["OK", "FAIL"] — Статус ответа
    - `data`: object
      - `url`: string — Ссылка для перехода на форму обратной связи

#### POST /persons/proforientation/recommendation/{personId}
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/proforientation/recommendation/{personGuid}`
- Описание: Метод формирования заявки на мероприятие
- Параметры:
  - path `personId`: string, обяз.
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): ProfessionApplicationRequest
    - `eventId`: string — ИД мероприятия
- Ответ 200: без тела
- **Примечание:** **Похоже только для школы.** Заявка на профориентационное мероприятие.

#### GET /persons/{personId}/v2/academic-performance/average-mark
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/v2/academic-performance/average-mark`
- Описание: Получение данных о текущий средних оценках учащегося
- Параметры:
  - path `personId`: string, обяз.
- Заголовки/куки по спецификации: X-Mes-Subsystem, x-mes-role
- Ответ 200: AveragePerformance
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: AveragePerformanceData — AveragePerformanceData
      - `averageMarkFive`: number — Общий средний балл
      - `subjectsRank`: array<AveragePerformanceSubject> — Массив средних баллов по предметам
- **Примечание:** Используется (getPersonsPersonIdV2AcademicPerformanceAverageMark).

#### GET /persons/{personId}/personal-diagnostic-grouped
- URL (колледж): `https://school.mos.ru/api/portfolio/app/persons/{personGuid}/personal-diagnostic-grouped`
- Описание: Получение данных об Индивидуальных диагностиках
- Параметры:
  - query `count`: integer, обяз. — Тип подсчета диагностик Возможные значения: 1 = "Все диагностики" 2 = "Диагностики у которых isVisible = true " Передаётся 2
  - path `personId`: string, обяз.
- Заголовки/куки по спецификации: X-Mes-Subsystem, aupd_current_role
- Ответ 200: PersonalDiagnostics
    - `result`: string — Результат. Значения: ОК - все отрабатывает. FAIL - Ошибка.
    - `data`: array<PersonalDiagnosticData>
      - `learningYear`: string — Учебный год
      - `diagnostics`: array<PersonalDiagnosticItem> — Массив диагностик за указанный год
      - `bestResult`: PersonalDiagnosticBestResult — DiagnosticBestResult
- **Примечание:** **Похоже только для школы.** Индивидуальные диагностики МЦКО.

## 6. Новости (NewsApi) — 25 методов
База: `https://school.mos.ru/api/news/v2`. Все методы используются приложением. Для колледжа специфичного нет — только X-Mes-Subsystem: familypom и service_id=59.

#### GET /news/users
- URL (колледж): `https://school.mos.ru/api/news/v2/news/users`
- Описание: Получение новостной ленты для детей и их представителей. Новостная лента состоит из новостей каналов.
- Параметры:
  - query `channel_id`: integer, необяз. — Идентификатор канала. При передаче будут возвращены новости канала
  - query `tag_ids`: string, необяз. — Массив идентификаторов тэгов. Используется для фильтрации по тэгам
  - query `page`: integer, необяз. — Параметр пагинации. Запрашиваемая страница пагинации.
  - query `per_page`: integer, необяз. — Параметр пагинации. Количество записей на 1 странице пагинации (не более 100)
  - query `name`: string, необяз. — Параметр фильтрации. Фильтруем новости по названию
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: NewsFeed
    - `data`: array<News>
      - `id`: integer — Идентификатор новости
      - `name`: string — Наименование новости
      - `views`: integer — Количество просмотров новости
      - `published_at`: string(date-time) — Дата и время публикации новости
      - `created_at`: string(date-time) — Дата и время создания новости
      - `updated_at`: string(date-time) — Дата и время обновления записи
      - `is_actual`: boolean — Признак актуальности новости
      - `is_system`: boolean — Признак того, что новость является системной
      - `actual_to`: string(date-time) — Дата, до которой новости актуальна
      - `channel`: ChannelForNews — Объект канала с ограниченным массивом атрибутов. Используется при отображении новостей в ленте
      - `tags`: array<Tag> — Массив тегов новости
      - `emoji`: array<Emoji> — Массив эмодзи
      - … (ещё 8 строк)
    - `pagination`: Pagination — Объект для пагинации
      - `total`: integer — Общее количество записей
      - `page`: integer — Порядковый номер текущей страницы
      - `page_size`: integer — Количество записей на одной странице
      - `page_count`: integer — Общее количество страниц

#### GET /channels
- URL (колледж): `https://school.mos.ru/api/news/v2/channels`
- Описание: Получение списка новостных каналов
- Параметры:
  - query `page`: integer, необяз. — Параметр пагинации. Запрашиваемая страница пагинации.
  - query `per_page`: integer, необяз. — Параметр пагинации. Количество записей на 1 странице пагинации (не более 200)
  - query `name`: string, необяз. — Параметр фильтрации. Фильтр по названию канала
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: ChannelsFeed
    - `data`: array<Channel>
      - `id`: integer — Идентификатор канала
      - `name`: string — Наименование канала
      - `description`: string — Описание канала
      - `is_deleted`: boolean — Признак удаленности канала
      - `logo`: ChannelLogo — Объект логотипа канала
      - `creator_id`: string — Идентификатор создателя
      - `is_changeable`: boolean — Доступно ли редактирование канала для определенного пользователя
      - `region`: CatalogItem — Модель, которая подходит под запись любого из каталогов, таких как: каталог регионов, каталог городо
      - `cities`: array<CatalogItem>
      - `schools`: array<CatalogItem>
      - `parallels`: array<CatalogItem>
      - `subjects`: array<CatalogItem>
      - … (ещё 2 строк)
    - `pagination`: Pagination — Объект для пагинации
      - `total`: integer — Общее количество записей
      - `page`: integer — Порядковый номер текущей страницы
      - `page_size`: integer — Количество записей на одной странице
      - `page_count`: integer — Общее количество страниц

#### GET /channels/{id}
- URL (колледж): `https://school.mos.ru/api/news/v2/channels/{id}`
- Описание: Получение канала по переданному идентификатору
- Параметры:
  - path `id`: integer, обяз. — Идентификатор канала
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: ChannelById
    - `id`: integer — Идентификатор канала
    - `name`: string — Наименование канала
    - `description`: string — Описание канала
    - `logo`: ChannelLogo — Объект логотипа канала
      - `id`: integer — Идентификатор логотипа
      - `value`: string — Ссылка до хранилища в s3 с изображением логотипа
    - `cover`: string — Ссылка до изображения канала в s3
    - `all_news_count`: integer — Кол-во всех новостей в канале
    - `is_changeable`: boolean — Доступно ли редактирование канала для определенного пользователя
    - `is_deleted`: boolean — Признак удаленности записи
    - `region`: CatalogItem — Модель, которая подходит под запись любого из каталогов, таких как: каталог регионов, каталог городо
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `cities`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `schools`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `classes`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `subjects`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `parallels`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `roles`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов

#### GET /news/{id}
- URL (колледж): `https://school.mos.ru/api/news/v2/news/{id}`
- Описание: Получение новости по переданному идентификатору
- Параметры:
  - path `id`: integer, обяз. — Идентификатор новости
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: NewsById
    - `id`: integer — Идентификатор новости
    - `name`: string — Наименование новости
    - `views`: integer — Количество просмотров новости
    - `tags`: array<Tag>
      - `id`: integer — Идентификатор тега из справочника
      - `name`: string — Наименование тега
    - `emoji`: array<Emoji>
      - `id`: integer — Идентификатор эмодзи из справочника
      - `count`: integer — Количество поставленных эмодзи
      - `reacted_by_user`: boolean — Признак того, что пользователь выбрал эмодзи из справочника
    - `channel`: ChannelForNewsById — ChannelForNewsById
      - `id`: integer — Идентификатор канала
      - `name`: string — Название канала
      - `logo`: string — Путь (URI) в хранилище S3 к логотипу канала
      - `total_news_count`: integer — Количество новостей в канале
    - `content`: array<NewsContentV2>
      - `type`:  ["TEXT", "IMAGE", "VIDEO", "CAROUSEL"] — Тип контента в блоке
      - `value`: array<string> — Контент
    - `attachments`: array<Attachment>
      - `id`: integer — Идентификатор вложения
      - `name`: string — Название вложения
      - `format`: string — Формат вложения
      - `size`: integer — Размер вложения (в килобайтах)
      - `link`: string — Путь (URI) в хранилище S3 к изображению вложения
      - `type`:  ["IMAGE", "VIDEO", "DOC", "PDF", "LINK"] — Тип вложения
    - `is_changeable`: boolean — Признак возможности внесения изменений пользователем
    - `status`:  ["PUBLISHED"] — Текущий статуса новости
    - `cover`: string — Путь (URI) в хранилище S3 к обложке новости
    - `publication_id`: integer — Идентификатор публикации в ЕЦУ
    - `published_at`: string(date-time) — Дата и время публикации новости
    - `created_at`: string(date-time) — Дата и время создания новости
    - `archived_at`: string(date-time) — Дата и время архивации новости
    - `updated_at`: string(date-time) — Дата и время обновления записи
    - `actual_to`: string(date-time) — Дата, до которой новость считается актуальной
    - `is_actual`: boolean — Признак актуальности новости
    - `is_system`: boolean — Признак того, что новость является системной

#### GET /news/main/users
- URL (колледж): `https://school.mos.ru/api/news/v2/news/main/users`
- Описание: Получение главных новостей для детей и их представителей. Новости принадлежат каналу "Главные новости".
- Параметры:
  - query `tag_ids`: string, необяз. — Массив идентификатор тэгов. Используется для фильтрации по тэгам
  - query `name`: string, необяз. — Наименование новости. Подстрока для фильтрации (%LIKE%) новостей по наименованию
  - query `page`: integer, необяз. — Параметр пагинации. Запрашиваемая страница пагинации
  - query `per_page`: integer, необяз. — Параметр пагинации. Количество записей на 1 странице пагинации (не более 100)
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: NewsFeed
    - `data`: array<News>
      - `id`: integer — Идентификатор новости
      - `name`: string — Наименование новости
      - `views`: integer — Количество просмотров новости
      - `published_at`: string(date-time) — Дата и время публикации новости
      - `created_at`: string(date-time) — Дата и время создания новости
      - `updated_at`: string(date-time) — Дата и время обновления записи
      - `is_actual`: boolean — Признак актуальности новости
      - `is_system`: boolean — Признак того, что новость является системной
      - `actual_to`: string(date-time) — Дата, до которой новости актуальна
      - `channel`: ChannelForNews — Объект канала с ограниченным массивом атрибутов. Используется при отображении новостей в ленте
      - `tags`: array<Tag> — Массив тегов новости
      - `emoji`: array<Emoji> — Массив эмодзи
      - … (ещё 8 строк)
    - `pagination`: Pagination — Объект для пагинации
      - `total`: integer — Общее количество записей
      - `page`: integer — Порядковый номер текущей страницы
      - `page_size`: integer — Количество записей на одной странице
      - `page_count`: integer — Общее количество страниц

#### GET /channels/system
- URL (колледж): `https://school.mos.ru/api/news/v2/channels/system`
- Описание: Получение системного канала/новостной рубрики, внутри которой находятся системные новости. Является каналом для главных новостей.
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: SystemChannel
    - `id`: integer — Идентификатор канала
    - `name`: string — Наименование канала
    - `description`: string — Описание канала
    - `is_deleted`: boolean — Признак удаленности канала
    - `logo`: ChannelLogoWithoutId — Объект логотипа новостного канала, у которого нет идентификатора. Используется для метода GET /chann
      - `value`: string — Ссылка до S3 на графическое изображение
    - `creator_id`: string — Идентификатор создателя
    - `is_changeable`: boolean — Доступно ли редактирование канала для определенного пользователя
    - `region`: CatalogItem — Модель, которая подходит под запись любого из каталогов, таких как: каталог регионов, каталог городо
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `cities`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `schools`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `parallels`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `subjects`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `classes`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `roles`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `all_news_count`: integer — Количество новостей системного канала

#### GET /catalogs
- URL (колледж): `https://school.mos.ru/api/news/v2/catalogs`
- Описание: Метод получения наполнения справочников. Справочник эмодзи, справочник тэгов.
- Параметры:
  - query `catalogue_name`: string ["EMOJI", "TAGS"], обяз. — Наименование справочника: EMOJI — справочник эмодзи, TAGS — справочник тэгов новостей
  - query `filter`: string, необяз. — Параметр посимвольной фильтрации
  - query `is_system`: boolean, необяз. — Параметр фильтрации каталога тэгов по принадлежности к системным новостям.
  - query `page`: integer, необяз. — Параметр пагинации. Запрашиваемая страница пагинации.
  - query `per_page`: integer, необяз. — Параметр пагинации. Количество записей на 1 странице пагинации
  - query `emoji_usage`: string ["NEWS", "STORIES"], необяз. — Параметр использования каталога, передается только при catalogue_name = EMOJI
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: Catalogs
    - `data`: array<CatalogItem>
      - `id`: integer — Идентификатор записи
      - `value`: string — Наименование записи
      - `is_system`: boolean — Признак принадлежности системным новостям. Актуален тольк для справочника тэгов
    - `pagination`: Pagination — Объект для пагинации
      - `total`: integer — Общее количество записей
      - `page`: integer — Порядковый номер текущей страницы
      - `page_size`: integer — Количество записей на одной странице
      - `page_count`: integer — Общее количество страниц

#### POST /news/reaction
- URL (колледж): `https://school.mos.ru/api/news/v2/news/reaction`
- Описание: Сохранение новой реакции для новости. Реакция- связка новость+ эмодзи из списка. В ответ метода приходит список ВСЕХ реакций, которые есть для новости (в том числе новая реакция). Так сделано, чтобы не перезапрашивать объект новости для обновления данных о реакциях.
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): object
    - `emoji_id`: integer — Идентификатор эмодзи из справочника
    - `news_id`: integer — Идентификатор новости
- Ответ 200: array<Emoji>
    - `id`: integer — Идентификатор эмодзи из справочника
    - `count`: integer — Количество поставленных эмодзи
    - `reacted_by_user`: boolean — Признак того, что пользователь выбрал эмодзи из справочника

#### DELETE /news/reaction/{news_id}
- URL (колледж): `https://school.mos.ru/api/news/v2/news/reaction/{news_id}`
- Описание: Удаление реакции, поставленной пользователем. В ответ метода приходит список ВСЕХ реакций, которые есть для новости. (без учета удаленной) Так сделано, чтобы не запрашивать объект новости при обновлении данных о реакциях
- Параметры:
  - path `news_id`: integer, обяз. — Идентификатор новости
- Ответ 200: array<Emoji>
    - `id`: integer — Идентификатор эмодзи из справочника
    - `count`: integer — Количество поставленных эмодзи
    - `reacted_by_user`: boolean — Признак того, что пользователь выбрал эмодзи из справочника

#### GET /banners/v2
- URL (колледж): `https://school.mos.ru/api/news/v2/banners/v2`
- Описание: Получение экземпляра баннера из МЭШ.Новостей для пользователя
- Параметры:
  - query `location`: string ["HEADER", "MAIN"], обяз. — Расположение баннера: HEADER (в хедере), MAIN (на главной странице). Передается значение: HEADER
  - query `service_id`: string, обяз. — Идентификатор продукта в справочнике АУПД. Передается значение: 7
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `banner_instance_id`: integer — Идентификатор экземпляра баннера для отображения в интерфейсе текущего продукта МЭШ для текущего пол
    - `id`: string — Идентификатор баннера
    - `is_tech_banner`: boolean — Признак технического баннера
    - `published_at`: string(date-time) — Дата и время публикации баннера. Формат: YYYY-MM-DD HH:MM:SS
    - `image_left`: string(uri) — Путь (URI) в хранилище S3 к файлу с графическим изображением левой картинки на баннере. Может быть n
    - `image_right`: string(uri) — Путь (URI) в хранилище S3 к файлу с графическим изображением правой картинки на баннере. Может быть 
    - `text`: string — Текст на баннере
    - `banner_background_color`: string — Цвет фона баннера. Может быть передан как один цвет или градиент - направление изменения цветов и на
    - `banner_text_color`: string — Цвет текста баннера - Hex-код
    - `banner_text_font`: string — Семейство шрифтов текста баннера. Не используется для баннера в МП
    - `action_button`: boolean — Наличие кнопки-действия на баннере. Одно из значений: - True - присутствует на баннере; - False - от
    - `button_link`: string — Ссылка на: - переход в браузер на внешнюю страницу в интернете - переход в раздел приложения Дневник
    - `button_text`: string — Название кнопки действия. Может быть null
    - `button_background_color`: string — Цвет фона кнопки действия на баннере. Может быть передан как один цвет или градиент - направление из
    - `button_text_color`: string — Цвет текста кнопки-действия - Hex-код. Может быть null
    - `button_text_font`: string — Семейство шрифтов текста кнопки-действия. Может быть null. Не используется для баннера в МП
    - `preset_id`: integer — Идентификатор пресета, на основании которого создан баннер
    - `location`: string ["HEADER", "MAIN"] — Расположение баннера
    - `status`:  ["DRAFT", "ONPUBLIC", "PENDING", "PUBLISHED", "ARCHIVED"] — Текущий статуса баннера. Одно из значений: - "DRAFT" — черновик; - "ONPUBLIC" — на публикации; - "PE
    - `hide_type`: string ["NON_HIDDEN", "ONCE_HIDDEN", "MULTIPLE_HIDDEN"] — Тип скрытия баннера
    - `hide_period`: integer — Период для скрытия баннера в часах после его закрытия пользователем. Передается для баннера с hide_t
- **Примечание:** service_id: в спецификации «7»; для колледжа по аналогии 59. location=HEADER.

#### PATCH /banners/instance
- URL (колледж): `https://school.mos.ru/api/news/v2/banners/instance`
- Описание: Изменение статуса экземпляра баннера МЭШ.Новостей для пользователя после закрытия баннера
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): object
    - `banner_instance_id`: integer — Идентификатор экземпляра текущего закрываемого пользователем баннера banner_instance_id из ответа на
    - `banner_instance_status`: string ["USER_HIDDEN", "USER_HIDDEN_TEMP"] — Значение для закрытия баннера согласно логике: - если hide_type === “ONCE_HIDDEN” из ответа GET /ban
- Ответ 200: без тела

#### POST /appmetrica/v1/event
- URL (колледж): `https://school.mos.ru/api/news/v2/appmetrica/v1/event`
- Описание: Отправка события о действии пользователя (переход по кнопке действия или закрытие) над экземпляром баннера МЭШ.Новостей для сбора метрик
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Role, X-Mes-Subsystem
- Тело (application/json): object
    - `banner_instance_id`: integer — Идентификатор (banner_instance_id) экземпляра баннера, по которому выполнено действие, из ответа на 
    - `service_id`: string — Идентификатор продукта в справочнике АУПД. Передается значение: 7
    - `location`: string ["HEADER", "MAIN"] — Расположение баннера
    - `action`: string ["HIDE", "FOLLOW"] — - если пользователь нажал на кнопку действия на баннере, то передается значение: FOLLOW - если польз
    - `start_date`: string(date-time) — Дата и время из поля published_at экземпляра баннера, по которому выполнено действие, из ответа на в
- Ответ 201: без тела
- **Примечание:** В теле service_id = selectByBuild {default:1, po:32} → 32 (другой справочник, не АУПД 59).

#### POST /appmetrica/v1/event/announcement
- URL (колледж): `https://school.mos.ru/api/news/v2/appmetrica/v1/event/announcement`
- Описание: Отправка события о действии пользователя над экземпляром анонса МЭШ.Новостей для сбора метрик
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Role, X-Mes-Subsystem
- Тело (application/json): object
    - `announcement_id`: integer — Идентификатор анонса, с которым было выполнено действие
    - `announcement_instance_id`: integer — Идентификатор экземпляра анонса, с которым было выполнено действие
    - `service_id`: string — Идентификатор продукта МЭШ, в котором размещен экземпляр анонса
    - `announcement_action`: AppmetricaEventAnnouncementActionEnum — Действие, которое пользователь выполнил с экземпляром анонса
    - `start_date`: string(date-time) — Дата и время начала публикации анонса
    - `end_date`: string(date-time) — Дата и время окончания публикации анонса
    - `links_data`: array<AnnouncementLinkData>
      - `link`: string(uri) — Ссылка, по которой был осуществлен переход на слайде
      - `slide_id`: integer — Идентификатор слайда, на котором был осуществлен переход
      - `slide_order`: integer — Порядковый номер слайда, на котором был осуществлен переход
      - `is_first_view_opening`: boolean — Признак перехода по ссылке после первичного показа анонса
      - `is_repeat_view_opening`: boolean — Признак перехода по ссылке после повторного показа анонса
- Ответ 201: без тела
- **Примечание:** В теле service_id = selectByBuild {default:1, po:32} → 32.

#### GET /announcements/user
- URL (колледж): `https://school.mos.ru/api/news/v2/announcements/user`
- Описание: Получение списка анонсов с атрибутами, необходимыми для отображения визуальной части.
- Параметры:
  - query `filter`: string ["ALL", "NEW"], обяз. — Фильтрация анонсов: "ALL" — будут возвращены все активные анонсы "NEW" — будут возвращены только НЕ просмотренные активные анонсы
  - query `service_id`: string, обяз. — Идентификатор продукта в справочнике АУПД. Передается значение: МП Дневник: 7, Колледж: 59
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: Announcements
    - `data`: array<AnnouncementsData>
      - `announcement_instance_id`: integer — Идентификатор экземпляра анонса для отображения в интерфейсе текущего продукта МЭШ для текущего поль
      - `id`: integer — Идентификатор анонса
      - `name`: string — Наименование анонса
      - `type`: string — Тип анонса по мероприятию
      - `published_at`: string(date-time) — Дата и время публикации анонса
      - `publish_end_time`: string(date-time) — Дата и время окончания публикации анонса
      - `slides_count`: integer — Количество слайдов анонса
      - `is_read`: boolean — Признак просмотренности анонса пользователем.
      - `format`:  ["MPFULL", "MPHALF", "WEB"] — Формат для которого создается анонс. Одно из значений: WEB: для публикации - Веб версия; MPFULL: для
      - `slides`: array<AnnouncementsSlides> — Массив слайдов анонса
- **Примечание:** service_id для колледжа = 59 (в спецификации: «МП Дневник: 7, Колледж: 59»; в коде SERVICE_ID = selectByBuild {default:7, po:59}).

#### GET /news/users/v2
- URL (колледж): `https://school.mos.ru/api/news/v2/news/users/v2`
- Описание: Получение списка новостей для пользователей
- Параметры:
  - query `is_system`: boolean, необяз. — Фильтрация для системных новостей
  - query `page`: integer, необяз. — Номер страницы
  - query `per_page`: integer, необяз. — Количество записей на странице
  - query `tag_ids`: array<integer>, необяз. — Массив идентификатор тэгов для поиска новостей
  - query `channel_id`: integer, необяз. — Идентификатор канала
  - query `name`: string, необяз. — Подстрока для фильтрации новостей по наименованию
- Заголовки/куки по спецификации: X-Mes-Role, X-Mes-Subsystem
- Ответ 200: object
    - `data`: array<NewsV2>
      - `id`: integer — Идентификатор новости
      - `name`: string — Наименование новости
      - `views`: integer — Количество просмотров новости
      - `published_at`: string(date-time) — Дата и время публикации новости
      - `created_at`: string(date-time) — Дата и время создания новости
      - `updated_at`: string(date-time) — Дата и время обновления записи
      - `is_actual`: boolean — Признак актуальности новости
      - `is_system`: boolean — Признак того, что новость является системной
      - `actual_to`: string(date-time) — Дата, до которой новости актуальна
      - `channel`: ChannelForNews — Объект канала с ограниченным массивом атрибутов. Используется при отображении новостей в ленте
      - `tags`: array<Tag>
      - `emoji`: array<Emoji>
      - … (ещё 8 строк)
    - `pagination`: Pagination — Объект для пагинации
      - `total`: integer — Общее количество записей
      - `page`: integer — Порядковый номер текущей страницы
      - `page_size`: integer — Количество записей на одной странице
      - `page_count`: integer — Общее количество страниц

#### GET /stories/user
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/user`
- Описание: Получение списка историй
- Параметры:
  - query `service_id`: string, обяз. — Идентификатор продукта МЭШ в справочнике АУПД
- Заголовки/куки по спецификации: App-version, X-Mes-Subsystem
- Ответ 200: Stories
    - `data`: array<StoryData>
      - `id`: integer — Идентификатор истории
      - `stories_instance_id`: integer — Идентификатор экземпляра истории
      - `title`: string — Заголовок истории
      - `slides_count`: integer — Количество слайдов истории
      - `priority`: integer — Порядковый номер (приоритет) истории при отображении историй у пользователей-потребителей историй
      - `published_at`: string(date-time) — Дата и время публикации истории
      - `publish_end_time`: string(date-time) — Время окончания публикации истории
      - `is_read`: boolean — Признак просмотра истории пользователем
      - `show_thumbnail`: boolean — Признак отображения на миниатюре изображения
      - `preview`: string(uri) — Ссылка на картинку из S3 для отображения в превью истории
      - `is_favorite`: boolean — Признак избранной истории у пользователя
      - `is_personalized`: boolean — Признак персонализированной истории
      - … (ещё 7 строк)
- **Примечание:** service_id = STORIES_V3_SERVICE_ID = selectByBuild {default:"7", po:"59"} → 59.

#### GET /stories/{story_id}
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/{story_id}`
- Описание: Получение истории по ее идентификатору, реализован в рамках рефакторинга бэка
- Параметры:
  - query `service_id`: string, обяз. — Идентификатор продукта МЭШ в справочнике АУПД
  - path `story_id`: integer, обяз. — Идентификатор истории
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: StoryData
    - `id`: integer — Идентификатор истории
    - `stories_instance_id`: integer — Идентификатор экземпляра истории
    - `title`: string — Заголовок истории
    - `slides_count`: integer — Количество слайдов истории
    - `priority`: integer — Порядковый номер (приоритет) истории при отображении историй у пользователей-потребителей историй
    - `published_at`: string(date-time) — Дата и время публикации истории
    - `publish_end_time`: string(date-time) — Время окончания публикации истории
    - `is_read`: boolean — Признак просмотра истории пользователем
    - `show_thumbnail`: boolean — Признак отображения на миниатюре изображения
    - `preview`: string(uri) — Ссылка на картинку из S3 для отображения в превью истории
    - `is_favorite`: boolean — Признак избранной истории у пользователя
    - `is_personalized`: boolean — Признак персонализированной истории
    - `slides`: array<SlideData>
      - `id`: integer — Идентификатор слайда
      - `slide_order`: integer — Порядковый номер слайда среди всех слайдов истории
      - `is_read`: boolean — Признак просмотра слайда истории пользователем
      - `header`: string — Заголовок слайда
      - `header_font`: FontEnum — Шрифты
      - `header_font_size`: integer — Размер шрифта заголовка
      - `header_font_style`: array<FontStyleEnum> — Массив списка стилей шрифта заголовка слайда
      - `header_align`: HorizontalAlignEnum — Выравнивание по горизонтали
      - `header_font_color`: string — Цвет шрифта заголовка (Hex-код)
      - `body`: string — Описание на слайде
      - `body_font`: FontEnum — Шрифты
      - `body_font_size`: integer — Размер шрифта описания
      - … (ещё 17 строк)
    - `status`: StoryStatusEnum — Статус истории
    - `audience`: array<AudienceData>
      - `id`: string — Идентификатор группы пользователей в системе МЭШ
      - `name`: string — Наименование группы пользователей в системе МЭШ
    - `services`: array<ItemServices>
      - `id`: string — Идентификатор продукта
      - `name`: string — Наименование продукта
    - `sharing_name`: string — Имя ученика, которому принадлежит персонализированная история
    - `reactions_count`: integer — Кол-во проставленных реакций пользователями на историю (эмодзи)
    - `emoji_id`: integer — Идентификатор проставленной пользователем эмодзи
- **Примечание:** service_id = 59.

#### GET /stories/favorite/list/v2
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/favorite/list/v2`
- Описание: Получение списка избранных историй, реализован в рамках рефакторинга бэка
- Параметры:
  - query `service_id`: string, обяз. — Идентификатор продукта МЭШ в справочнике АУПД
  - query `page`: integer, необяз. — Номер страницы
  - query `per_page`: integer, необяз. — Количество записей на странице
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: FavoriteStories
    - `data`: array<StoryData>
      - `id`: integer — Идентификатор истории
      - `stories_instance_id`: integer — Идентификатор экземпляра истории
      - `title`: string — Заголовок истории
      - `slides_count`: integer — Количество слайдов истории
      - `priority`: integer — Порядковый номер (приоритет) истории при отображении историй у пользователей-потребителей историй
      - `published_at`: string(date-time) — Дата и время публикации истории
      - `publish_end_time`: string(date-time) — Время окончания публикации истории
      - `is_read`: boolean — Признак просмотра истории пользователем
      - `show_thumbnail`: boolean — Признак отображения на миниатюре изображения
      - `preview`: string(uri) — Ссылка на картинку из S3 для отображения в превью истории
      - `is_favorite`: boolean — Признак избранной истории у пользователя
      - `is_personalized`: boolean — Признак персонализированной истории
      - … (ещё 7 строк)
- **Примечание:** service_id = 59.

#### PATCH /stories/favorite/v2/{story_id}
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/favorite/v2/{story_id}`
- Описание: Добавление/удаление истории в "Избранные", реализован в рамках рефакторинга бэка
- Параметры:
  - query `service_id`: string, обяз. — Идентификатор продукта МЭШ в справочнике АУПД
  - query `is_favorite`: boolean, обяз. — Признак добавления истории в "Избранные" пользователя
  - path `story_id`: integer, обяз. — Идентификатор истории
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 204: без тела
- **Примечание:** service_id = 59.

#### PATCH /announcements/instance
- URL (колледж): `https://school.mos.ru/api/news/v2/announcements/instance`
- Описание: Изменение статуса и признака просмотренности экземпляра анонса после взаимодействия с ним пользователя
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): AnnouncementInstanceRead
    - `announcement_instance_id`: integer — Идентификатор экземпляра анонса для пользователя
    - `is_read`: boolean — Признак просмотра анонса пользователем
- Ответ 200: без тела

#### GET /news/top
- URL (колледж): `https://school.mos.ru/api/news/v2/news/top`
- Описание: Получение списка топ-новостей
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Role, X-Mes-Subsystem
- Ответ 200: object
    - `data`: array<NewsV2>
      - `id`: integer — Идентификатор новости
      - `name`: string — Наименование новости
      - `views`: integer — Количество просмотров новости
      - `published_at`: string(date-time) — Дата и время публикации новости
      - `created_at`: string(date-time) — Дата и время создания новости
      - `updated_at`: string(date-time) — Дата и время обновления записи
      - `is_actual`: boolean — Признак актуальности новости
      - `is_system`: boolean — Признак того, что новость является системной
      - `actual_to`: string(date-time) — Дата, до которой новости актуальна
      - `channel`: ChannelForNews — Объект канала с ограниченным массивом атрибутов. Используется при отображении новостей в ленте
      - `tags`: array<Tag>
      - `emoji`: array<Emoji>
      - … (ещё 8 строк)

#### GET /news/top/recommended
- URL (колледж): `https://school.mos.ru/api/news/v2/news/top/recommended`
- Описание: Получение списка рекомендуемых новостей
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Role, X-Mes-Subsystem
- Ответ 200: object
    - `data`: array<NewsV2>
      - `id`: integer — Идентификатор новости
      - `name`: string — Наименование новости
      - `views`: integer — Количество просмотров новости
      - `published_at`: string(date-time) — Дата и время публикации новости
      - `created_at`: string(date-time) — Дата и время создания новости
      - `updated_at`: string(date-time) — Дата и время обновления записи
      - `is_actual`: boolean — Признак актуальности новости
      - `is_system`: boolean — Признак того, что новость является системной
      - `actual_to`: string(date-time) — Дата, до которой новости актуальна
      - `channel`: ChannelForNews — Объект канала с ограниченным массивом атрибутов. Используется при отображении новостей в ленте
      - `tags`: array<Tag>
      - `emoji`: array<Emoji>
      - … (ещё 8 строк)

#### POST /stories/reaction
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/reaction`
- Описание: Сохранение реакции пользователя на историю
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): object
    - `stories_instance_id`: integer — Идентификатор экземпляра истории
    - `emoji_id`: integer — Идентификатор эмодзи
- Ответ 200: object
    - `reactions_count`: integer — Кол-во проставленных реакций пользователями на историю (эмодзи)

#### DELETE /stories/reaction/{stories_instance_id}
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/reaction/{stories_instance_id}`
- Описание: Удаление реакции пользователя на историю
- Параметры:
  - path `stories_instance_id`: integer, обяз. — Идентификатор экземпляра истории
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Ответ 200: object
    - `reactions_count`: integer — Кол-во проставленных реакций пользователями на историю (эмодзи)

#### PATCH /stories/instance
- URL (колледж): `https://school.mos.ru/api/news/v2/stories/instance`
- Описание: Изменение признака просмотренности экземпляра истории после взаимодействия с ним пользователя
- Параметры: нет
- Заголовки/куки по спецификации: X-Mes-Subsystem
- Тело (application/json): object
    - `stories_instance_id`: integer — Идентификатор экземпляра история для отображения в интерфейсе текущего продукта МЭШ для текущего пол
    - `is_read`: boolean — Признак просмотренности пользователем
- Ответ 200: без тела

## 7. Рейтинг (RatingApi) — 4 метода
База в коде: `https://school.mos.ru/api/ej/rating/v1`; **для колледжа — `https://school.mos.ru/api/profeducation/rating/v1`** (проверено, 200 с данными). `personId` — вероятно person GUID ученика (значение передаётся снаружи хука useGetRankShort и т.п.).

#### GET /rank/rankShort
- URL (колледж): `https://school.mos.ru/api/profeducation/rating/v1/rank/rankShort`
- Описание: Краткий рейтинг за запрошенный период (без деления по предметам)
- Параметры:
  - query `personId`: string, обяз. — Идентификатор учащегося
  - query `beginDate`: string(date), обяз. — Дата начала периода
  - query `endDate`: string(date), обяз. — Дата окончания периода
- Ответ 200: array<object>
    - `date`: string(date) — Дата
    - `rankPlace`: integer — Место в общем рейтинге
- **Примечание:** у колледжа работает через `/api/profeducation/rating/v1` (проверено).

#### GET /rank/class
- URL (колледж): `https://school.mos.ru/api/profeducation/rating/v1/rank/class`
- Описание: Рейтинг класса (для общего рейтинга класса и рейтинга класса в рамках предмета)
- Параметры:
  - query `personId`: string, необяз. — Идентификатор учащегося
  - query `classUnitId`: integer, необяз. — Идентификатор класса
  - query `date`: string(date), обяз. — Дата рейтинга
  - query `subjectId`: integer, необяз. — Идентификатор предмета
- Ответ 200: array<object>
    - `personId`: string — Глобальный идентификатор персоны
    - `rank`: rank
      - `averageMarkFive`: number — Средний взвещенный балл в 5-балльной системе за год
      - `rankPlace`: integer — Место в рейтинге за год
      - `rankStatus`: string — stable - стабилен, down - понижен, up - вырос
      - `trend`: string — балл_предыдущий<балл_текущий - up, балл_предыдущий>балл_текущий - down, балл_предыдущий==балл_текущи
      - `averageMarkFive30`: number — Средний взвещенный балл в 5-балльной системе за последние 30 дней
      - `rankPlace30`: integer — Место в рейтинге за последние 30 дней
      - `rankStatus30`: string — stable - стабилен, down - понижен, up - вырос
      - `trend30`: string — балл_предыдущий<балл_текущий - up, балл_предыдущий>балл_текущий - down, балл_предыдущий==балл_текущи
      - `attestationPeriodId`: integer — идентификатор аттестационного периода
      - `periodId`: integer — идентификатор учебного периода
      - `moduleId`: integer — идентификатор модуля
    - `previousRank`: previousRank
      - `averageMarkFive`: number(double) — Средний взвещенный балл в 5-балльной системе за год
      - `rankPlace`: integer — Место в рейтинге за год
      - `averageMarkFive30`: number(double) — Средний взвещенный балл в 5-балльной системе за последние 30 дней
      - `rankPlace30`: integer — Место в рейтинге за последние 30 дней
    - `imageId`: integer — Идентификатор файла эмодзи с животным
- **Примечание:** у колледжа работает через `/api/profeducation/rating/v1` (проверено).

#### GET /rank/subjects
- URL (колледж): `https://school.mos.ru/api/profeducation/rating/v1/rank/subjects`
- Описание: Текущий рейтинг по ученику по предметам
- Параметры:
  - query `personId`: string, обяз. — Идентификатор учащегося
  - query `date`: string(date), обяз. — Дата рейтинга
  - query `subjectId`: integer, необяз. — Идентификатор предмета
- Ответ 200: array<object>
    - `subjectId`: integer — Идентификатор предмета
    - `subjectName`: string — Наименование предмета
    - `rank`: rank
      - `averageMarkFive`: number — Средний взвещенный балл в 5-балльной системе за год
      - `rankPlace`: integer — Место в рейтинге за год
      - `rankStatus`: string — stable - стабилен, down - понижен, up - вырос
      - `trend`: string — балл_предыдущий<балл_текущий - up, балл_предыдущий>балл_текущий - down, балл_предыдущий==балл_текущи
      - `averageMarkFive30`: number — Средний взвещенный балл в 5-балльной системе за последние 30 дней
      - `rankPlace30`: integer — Место в рейтинге за последние 30 дней
      - `rankStatus30`: string — stable - стабилен, down - понижен, up - вырос
      - `trend30`: string — балл_предыдущий<балл_текущий - up, балл_предыдущий>балл_текущий - down, балл_предыдущий==балл_текущи
      - `attestationPeriodId`: integer — идентификатор аттестационного периода
      - `periodId`: integer — идентификатор учебного периода
      - `moduleId`: integer — идентификатор модуля
- **Примечание:** у колледжа работает через `/api/profeducation/rating/v1` (проверено).

#### GET /rank/subjectsDynamics
- URL (колледж): `https://school.mos.ru/api/profeducation/rating/v1/rank/subjectsDynamics`
- Описание: Текущий рейтинг по ученику динамика по предмету за запрошенные даты
- Параметры:
  - query `personId`: string, обяз. — Идентификатор учащегося
  - query `subjectId`: integer, обяз. — Идентификатор предмета
  - query `date`: array<string>, обяз. — Даты рейтинга
- Ответ 200: array<object>
    - `date`: string(date) — Дата
    - `person`: subjectsDynamics
      - `averageMarkFive`: number(double) — Средний взвешенный балл в 5-балльной системе
    - `class`: subjectsDynamics
      - `averageMarkFive`: number(double) — Средний взвешенный балл в 5-балльной системе
- **Примечание:** у колледжа работает через `/api/profeducation/rating/v1` (проверено).

## Пробы

Рабочий список проб — `feature/more/src/main/kotlin/ru/openmes/feature/more/ApiProbes.kt`, статусы — README.md.
