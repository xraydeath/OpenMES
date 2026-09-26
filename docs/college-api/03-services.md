# 03. Сервисы вне дневника и портфолио (сборка «Колледж МЭШ», po)

Источники: спецификации 00, 01, 02, 03, 04, 06, 07, 12 (без /api/ej), сгенерированные axios-клиенты из decompiled.js (Contract, Gamification, OccasionController, Sudir), саги.
Все URL даны для колледжа с учётом маршрутизации selectByBuild. Базовый хост — https://school.mos.ru. Обязательные параметры помечены `*`.

## 0. Общее: хосты, заголовки, apikey

### Маршрутизация колледжа (selectByBuild, po)
| Селектор | Школа | Колледж |
|---|---|---|
| getEjHost | /api/ej | /api/profeducation |
| getEventCalendarsHost | /api/eventcalendar/ | /api/profeducation/eventcalendar/ |
| getIsppHost | /api/family/ispp (school api) | https://school.mos.ru/api/profeducation/family/ispp |
| getMeshTokenUrl | ... | /api/profeducation/acl/v1/mod-acl/users/profile_info (заголовок Partner-Source-Id: MOBILE) |
| getCertificatesHost | /api/certificates/family/v1 | то же (варианта для po нет) |
| getSUDIRHost | https://login.mos.ru/sps/api | то же |
| getLRSHost | /api/lrs | то же |
| getPersonRequestEndpoint | /api/persondata/v1 | то же |

Сгенерированные клиенты: интерцептор schoolBaseurl меняет только origin (school-dev → school.mos.ru), пути (например /api/ej) не переписывает. Для клиентов с BASE_PATH на school-dev.mos.ru итоговый адрес — school.mos.ru с тем же путём.

### Базовые адреса клиентов
| Клиент | Базовый адрес |
|---|---|
| mealsV3 (питание) | https://school.mos.ru/api/food/meals/v3 |
| Contract | https://school.mos.ru/api/contract/payments/v1 |
| schoolNsi | https://school.mos.ru/api/nsi/dictionaries/v1 |
| school / schoolMos / family | https://school.mos.ru (пути абсолютные) |
| efsp | https://api.mos.ru |
| epsh | https://pay.mos.ru (dev test.mospay, test prodlike.mospay) |
| Sudir | https://login.mos.ru |

### Заголовки
apiRequest (ручные запросы саг):
- `Accept: application/json`, `Content-Type: application/json`
- `client-type: diary-mobile`
- `X-Mes-Subsystem: familypom` (X_MES_SUBSYSTEM: default familymp, po familypom; в спецификациях прямо «для Колледж: familypom»)
- `Accept-Language: ru`
- `x-row-limit` (опционально)
- `X-Mes-RoleId`
- `Authorization: Bearer <токен АУПД>`
- `Auth-Token: <тот же токен>` — только при isOldAuthNeed
- `Profile-Id`

commonHeaders (сгенерированные клиенты): client-type, X-Mes-Subsystem, Accept-Language: ru, Profile-id (если число), X-Mes-RoleId. Плюс Authorization из Configuration.

Прочие константы po: PORTAL_ID familypom; SERVICE_ID 59 (школа 7); aupd_current_role '59:32' (школа '7:1'); subsystem для ФОС — mp_college; для экрана питания — mp_college_food.
Питание: `X-Mes-AppId: familymp` (обязателен в POST/PATCH; enum mosru|gosuslugi77m|familymp; для po значение то же — familymp).

### apikey
- Во всех Configuration функции apiKey и accessToken возвращают authSelectors.getToken — это тот же токен АУПД. Отдельного ключа нет.
- Где apiKey реально попадает в запрос: заголовок `Auth-Token` в PortfolioApi (29 мест setApiKeyToObject); заголовок `token` в SchoolApi для GET /api/notifications/usersettings/v1/user/setting и PATCH .../default_setting.
- Единственный настоящий ключ — YANDEX_MAP_API_KEY для geocode-maps.yandex.ru/1.x (параметр apikey). Зашит в бандл, в константах карты (около строки 2684766 decompiled.js). Значение здесь не приводится.

---

## 1. Питание (/api/food/meals/v3) — подробно

База: https://school.mos.ru/api/food/meals/v3. Заголовки: Authorization Bearer (АУПД), X-Mes-Subsystem; в изменяющих методах — X-Mes-AppId*.
`clientId` (и `clientIds`) — строка с JSON: ClientId `{"personId": "<uuid>"}` или `{"staffId": <int64>}`; clientIds — JSON-массив таких объектов. Деньги везде в копейках.

Общие схемы:
- DishBase `{id, name, price (коп.), categoryId, categoryName}`
- Dish = DishBase + `ingredients, calories, weight, protein, fat, carbohydrates, subcategoryId, subcategoryName`
- ComplexBase `{id, name, price, kind (ComplexKind), paymentTypes[PaymentType], preorderAllowed, allowSelectItems, specialDiet}`
- ComplexKind: 0 завтрак, 1 второй завтрак, 2 обед, 3 полдник, 4 ужин, 5 второй ужин, 6 вода.
- PaymentType: 0 льготное, 1 платное.
- Address `{id, organizationName, text}`

### Клиент и счёт
| Метод | Путь | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | /clients/balance | Баланс и лимиты | clientIds* (JSON-массив ClientId) | `[{clientId{staffId, personId}, contractId int64, balance int32 коп., expenseConstraints{expenseDayLimit, balanceThreshold}}]` |
| PATCH | /clients/expense-constraints | Дневной лимит и порог уведомления о балансе | clientId*; X-Mes-AppId*; тело ExpenseConstraints `{expenseDayLimit, balanceThreshold}` | — |
| GET | /clients/allowed-food-state | Что разрешено ученику | clientId* | `{discountedMeals, preorderAllowed, foodboxAllowed, specialDietComplex, addresses[{address{id, organizationName, text}, settings{foodboxAvailable, preorderAvailable}}]}` |
| PATCH | /clients/allowed-food-state | Разрешить или запретить фудбокс и спецдиету | clientId*; тело `{foodboxAllowed*, specialDietComplex*}` | — |
| GET | /clients/food-provider | Поставщик питания | clientId* | `{name, inn, ogrn, rnipsc}` |
| POST | /clients/preorder-allowed | Включить предзаказ | clientId*; X-Mes-AppId* | — |

### Меню
| Метод | Путь | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | /menu/buffet | Меню буфета и фудбокса | clientId*, from* (date), to* (date), withFoodbox* (in \| ex \| only), addressIds | `[{onDate, addresses[{address, items[{dish: Dish, prohibition bool, foodbox bool}], buffetIsOpen, buffetOpenAt, buffetCloseAt, foodboxIsOpen, foodboxOpenAt, foodboxCloseAt}]}]` |
| GET | /menu/complexes | Комплексное меню | clientId*, from*, to*, withPreorder* (in \| ex \| only) | `[{onDate, address, items[Complex = ComplexBase + startDate, endDate, complexItems[{dish: Dish}]]}]` |
| GET | /menu/prohibitions | Запреты родителя на блюда и категории | clientId* | `[{dishId, categoryId, subcategoryId}]` |
| PUT | /menu/prohibitions | Установить запреты | clientId*; тело `[{prohibition* bool, prohibitionItem*{dishId, categoryId, subcategoryId}}]` | — |

### Операции по счёту
| Метод | Путь | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | /transactions | История движения денег | clientId*, from*, to*, limit (1..1000, по умолчанию 10), offset, type | `{hasNext, transactions[{sum коп., prevBalance коп., createdAt, origin, type, orderId}]}` |

type: 0 пополнение, 1 заказ, 2 отмена заказа, 3 перевод между счетами, 4 возврат.
Особенность: в примерах спецификации отрицательные суммы приходят как uint32 (4294959296 = −8000). Надо приводить к int32.

### Заказы
| Метод | Путь | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | /orders | Список заказов (покупки в буфете тоже) | clientId*, from*, to*, status[] , orderType[], limit, offset, sort (asc \| desc, по умолчанию desc) | `{hasNext, orders[Order]}` |
| GET | /orders/{orderId} | Один заказ | clientId* | Order |
| DELETE | /orders | Отменить заказы | clientId*, orderIds* | — |
| POST | /orders/preorder | Предзаказ комплекса | clientId*; X-Mes-AppId*; тело `{onDate*, items*[{complex{id, amount, dishes[{id, amount}]}}]}` | 201 `[OrderId]` |
| PUT | /orders/preorder/{orderId} | Изменить предзаказ | тело `{amount*, dishes[{id*, amount*}]}` | — |
| GET | /orders/preorder/summary | Сводка по предзаказам на ближайшие дни | clientId* | `{forbiddenDays, orderSum14Days, orderSum3Days, schedule[{onDate, orderAllowed, orderCount, orderSummary, address}]}` |
| POST | /orders/foodbox | Заказ фудбокса | clientId*, addressId*; тело `{items[{dishId, amount}]}` | OrderId |
| GET | /orders/rules | Регулярные правила заказа | clientId*, from*, to* | `[{id, dishId, complexId, from, to, amount, days{monday..sunday}}]` |
| POST | /orders/rules | Создать правила | X-Mes-AppId*; тело `[{dishId, complexId*, from*, to*, amount*, days*{monday..sunday}}]` | 201 `[RuleId]` |
| PUT / DELETE | /orders/rules/{ruleId} | Изменить или удалить правило | — | — |

Order: `{orderId int64, createdAt, status, orderType, deliveryWay, provisionTerm, listTypes[], expiredAt, deliveredAt, price, discount, totalPrice, onDate, items[{dish: OrderDish, complex: OrderComplex}]}`; OrderDish = DishBase + `amount, regularRule`; OrderComplex = ComplexBase + `amount, complexItems[OrderDish], regularRule`.
- status: 0 новый, 1 собран, 2 загружен, 3 выдан, 4 отменён.
- orderType: 0 комплекс льготный, 1 комплекс платный, 2 буфет, 3 предзаказ, 4 смартбуфет.
- deliveryWay: 0 столовая, 1 смартбуфет. listTypes: 0 блюда, 1 комплексы.

## 2. Сообщения: instantmessages (/api/instantmessages/v1) — подробно

| Метод | URL | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | https://school.mos.ru/api/instantmessages/v1/feed | Лента уведомлений (оценки, ДЗ, новости, питание, проходы и т.п.) | page (int), npp (int, размер страницы), filter (строка JSON: `{"child_id": "<string>", "source_id": [int], "view": [int], "setting_group_id": [int]}`) | `{data[Item]}` |
| GET | .../count_new | Число новых | role_id, filter (`{"child_id": ...}`) | integer |
| GET | .../count_new_important | Число новых важных | filter (`{child_id, source_id[], setting_group_id[]}`) | integer |

Item: `{id, title, description, timestamp (int), created_at, is_new, is_important, familiarise_required, decision_required, vcu_icon, context{source_id*, source_name*, event_id*, event_name*, setting_group_id, setting_group_name, mobile_routing_data{type ("deeplink"), event_type (например "news"), news_id, ...}}}`.
Для фильтрации по разделам берётся setting_group_id из настроек уведомлений (раздел 3).

---

## 3. Настройки уведомлений (/api/notifications/usersettings/v1/user) — подробно

Все URL — https://school.mos.ru/api/notifications/usersettings/v1/user/... Кроме Bearer, в SchoolApi для setting (GET) и default_setting ставится заголовок `token` = токен АУПД.

| Метод | Путь | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | setting | Все группы и события с подписками | mail_target_id* = 6 (пуш в МП) | `[{setting_group_id, setting_group_name, events[{event_type_id, event_name, description, settings[{id, event_setting_id, is_subscribed, user_type_id, time_to, time_from, mail_target_id, mail_target_name, description}]}]}]` |
| PUT | setting | Массово включить/выключить | user_setting_ids*[] , is_subscribed*, time_to, time_from | `{status, message, created_id}` |
| PUT | setting/{user_setting_id} | Одна настройка | is_subscribed*, time_to, time_from | то же |
| PUT | group_setting | По группам | is_subscribed*, mail_target_id* = 6, settings_group_ids*[] , time_to, time_from | то же |
| PATCH | group_setting/{settings_group_id} | Одна группа | is_subscribed*, mail_target_id | то же |
| PATCH | default_setting | Сброс к умолчаниям | settings_group_ids*; заголовок token* | то же |

Пуш-токен (fcm, сага):
- POST https://school.mos.ru/api/fcm/v1 — тело `{deviceId (getUniqueId), token (пуш-токен), pushProviderId, applicationId: "familypom"}`.
- DELETE https://school.mos.ru/api/fcm/v1/{deviceId}.
- Порядок провайдеров DEFAULT_NOTIFICATION_PROVIDER_ORDER: apns, rustore.

Пользовательские настройки (/api/usersettings/v1):
- GET/PUT https://school.mos.ru/api/usersettings/v1?name*&subsystem_id (1 | 30)&client_id
  - name: UC_TTC_CARD_BALANCE → `{Number, Balance, isBlocked, LgotBalance, UpdateAt, LastNotificationSent}` (транспортная карта);
  - settings_group_v1 → `{goal, theme{type dark|light, is_automatic, color_pattern}, common{is_rating_visible, is_marks_allotment_visible, is_yandex_tutor_ai_visible}, schedule_type compact|full}`;
  - favoriteServices → [enum]; favoriteCircles; favoriteCamps.

---

## 4. Проходы и посещения — подробно

### Турникеты (/api/pass/entrances/v1)
| Метод | URL | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | https://school.mos.ru/api/pass/entrances/v1/visit_durations | Входы и выходы по дням | personId* (guid), from* (date), to* (date) | `{payload[{date, visits[{in, out, duration (строки), kindId, kindName, personIn, personOut, isIncomplete, organizationId, organizationName, organizationShortName, organizationAddress}]}]}` |
| GET | .../status | В здании ли ученик сейчас | personId*[] (guid) | `{students[{personId, isAtSchool}]}` |

### ИСПП, проходы по карте с договором (сага)
- GET https://school.mos.ru/api/profeducation/family/ispp/v1/visits?contract_id*&from*&to* — у колледжа через getIsppHost (/api/profeducation/family/ispp) + apiVersion /v1. contract_id берётся из contractId баланса питания.

## 5. Прочее из спецификации 12 (не /api/ej)

Все пути от https://school.mos.ru.

### Календарь событий (у колледжа /api/profeducation/eventcalendar/v1)
Обязательны заголовки `X-Mes-Role: student` и `Client-Type: diary-mobile` — без них 401 «Пользователь не авторизован» (OpenMES их ставит в `MesApi.getEvents`).
| Метод | Путь | Описание | Параметры | Ответ 200 |
|---|---|---|---|---|
| GET | /api/profeducation/eventcalendar/v1/api/events | Расписание и события | person_ids*, begin_date*, end_date*, expand, source_types, cache_enabled, organization_ids, school_id, class_unit_id, event_type, course_lesson_type, academic_year_id, filter, group_id; заголовки x-mes-role* (student\|parent), Profile-id | `{total_count, response[{id, source (PLAN\|AE\|EC\|ORGANIZER\|EVENTS\|AFISHA\|OLYMPIAD\|PROF), start_at, finish_at, cancelled, lesson_type, room_name, subject_id, subject_name, homework{...}, marks[...], is_missed_lesson, title, description, place, address, data{...}, link_qr}], errors{...}}` |
| GET | .../api/events/{id} | Одно событие | source* | EventItem |
| GET | .../api/events/schedule/pdf | PDF расписания | begin_date*, end_date, source_types*, person_ids*, fio*, portrait, template (basic\|daily) | pdf |

### Согласия (/api/consents)
- GET consents?person_ids[]&consent_status_id&begin_date&end_date&event_type&event_ids&event_source_id (0 ЕМИАС, 1 ВЕБ.УЧИТЕЛЬ)&offset&limit → `{consents[{persons[...], event_source_id, event_id, event_type, event_date, deadline_date, event_name, is_cancelled, is_archive, data}], limit, offset, record_number}`.
- GET permission_status?person_ids* → `[{person_id, status_id}]`.
- POST consent_scan (multipart), POST parent_consent_status.

### Справочники НСИ
- GET /family_complaints, /family_moscow_districts, /family_moscow_organizations, /private_school_ids — обёртка `{technicalName, response[...], status, message, validDate}`. В спецификации пути без префикса; вероятная база /api/nsi/dictionaries/v1.
- GET /api/nsi/dictionaries/v1/credit_organizations → банки `{Account, ID, BIC, NameP}`.
- GET /api/nsi/dictionaries/v1/GAMIFICATION_PARTNER → `[{response[{label, avatar, title, id}]}]`.
- GET /api/nsi/dictionaries/v1/family (сага).

### Знания и результаты
- GET /api/lrs/v2/results?context*&size&page → страничный список `content[{..., score, max_score, success, items[]}]`.
- GET /api/sfp/service/v1/summaries?studentId&from&to → `[{summaryId, personId, intervalFrom, intervalTo, hasRecommendation}]`; GET summaries/{summaryId} → сводка (personProgress, scores, selfDiagnostics, independentDiagnostics, sections, competitions, achievements, lessonVisited, reasonableSkips, tests, debts, recommendations).

### Персональные данные и аккаунт
- GET /api/persondata/v1/persons/{personId} → `{id, person_id, lastname, firstname, patronymic, birthdate, snils, gender_id, addresses[], documents[], contacts[], agents[], children[], education[], citizenship}`. Содержит личные данные (СНИЛС, документы).
- PUT persons/{id}; POST persons/{personId}/documents; PUT persons/{personId}/documents/{documentId}.
- GET /api/aupd/v2/external-partners/check-for-vk-user?person_id* → `{vk_edu_id}`; check-for-max-user → `{max_id}`.
- GET /api/aupd/v1/user/childrens?person_id (сага).
- POST /v3/token/refresh — форма refresh_token → `{refresh_token, access_token}`.
- POST /v1/qr/intention — тело `{current_role}`.
- POST /v1/sessions → `{sessionId}` (переход в МЭШ Библиотеку).

### Разное
- POST /api/filestorage/v1/files — тело `{subsystem_id (7|59), type_id, app_version, device_info, extension, title, data}` → `{signature_token, file_path}`.
- POST /api/proforientation/crm/v1 — тело `{action, params{...}, token, requestId}` → `{success, message, data{...}}`.
- aware_journals (сага, через getEjHost): GET/POST /api/profeducation/aware_journals?student_profile_ids&week_from&week_to (ознакомление с журналом).
- GET /api/fos/v1/url (ФОС, subsystem mp_college).
- /api/exam/v1/rest/secure/challenge/stateexam/ (экзамены).

---

## 6. Платежи

### ЕФСП (спецификация 01, https://api.mos.ru)
- POST https://api.mos.ru/api/paymentapi/1.0/1.0 — история платежей. Заголовки: AccessToken* (токен СУДИР), Authorization (ЕФСП). Тело HistoryRequest `{page, per_page, updated_from, updated_to, created_from, created_to, sort_order, sort_field, status[APRP|PROC|DECL], rnip_code[DOGM000027...]}` → `{data[{id, narrative, status, service, amount, created_at, additional_data, recepient, rnip_code}], total_count, current_page, total_pages, per_page}`. Другие серверы в спецификации: papi.mos.ru (preprod), api-efp-test.

### ЕПШ (спецификация 04, https://pay.mos.ru)
- POST /mospaynew/history/quittance — история квитанций.
- GET /mospaynew/history/receipt/{UUID} — PDF чека (Bearer СУДИР).
- POST /mospaynew/sudir/auth/tt — тело `{portalId, feature, mobileUrl}` → `{url, resultCode, resultDescription}` (ссылка для входа на pay.mos.ru).

### Contract (https://school.mos.ru/api/contract/payments/v1), без спецификации
| Метод | Путь | Описание | Тело / параметры |
|---|---|---|---|
| POST | /applications?contractId | Заявление по договору | ApplicationDto |
| GET | /applications/{applicationId} | Заявление | — |
| POST | /applications/search | Поиск заявлений | ApplicationSearchRequest |
| GET | /contracts/{contractId} | Договор | — |
| POST | /contracts/search | Поиск договоров | ContractsSearchRequest `{pagination{pageNumber, pageSize}, sorting{orderBy, direction ASC|DESC}, filters{agentUuid, contractKindId 0..3, serviceTypeId 2|3, contractStatusId, ...}}` |
| POST | /payments/search | Платежи по договору | PaymentSearchRequest (та же структура pagination/sorting/filters) |
| POST | /receipts/search | Квитанции по договору | ReceiptSearchRequest |

Это платные услуги и договоры образовательной организации (не питание).

---

## 11. СУДИР (https://login.mos.ru), без спецификации
- POST /sps/oauth/introspect — проверка токена.
- POST /sps/oauth/te — обмен токена.
- POST /sps/service/css.
Вход через МЭШ: /v3/auth/sudir/auth, /v1/token/public/key, /auth/qr/ (на school.mos.ru).

---

## 12. Справки, аватары, ЕГЭ/ОГЭ (саги)

### Справки (https://school.mos.ru/api/certificates/family/v1, isOldAuthNeed=false, у колледжа без переадресации)
- GET /certificates?studentId&limit&offset&requestDateFrom (начало учебного периода, YYYY-MM-DD) — список заказанных справок.
- GET /certificates/statuses — справочник статусов.
- GET /certificates/{certificateId}.
- POST /certificates — заказ (тело из формы). Лимит: 5 запросов в день на ученика.
- PATCH /certificates/{id}/hardcopy — заказать бумажную справку.

### Аватары (/api/avatarmanagement/v1)
- GET /api/avatarmanagement/v1/{personId}.
- POST /api/avatarmanagement/v1/{personId} — multipart file, is_default; XHR с Authorization Bearer, Auth-Token, x-mes-subsystem, X-Mes-RoleId; таймаут 240 с.
- PATCH /api/avatarmanagement/v1/{personId}/{avatarId}?is_default.
- DELETE /api/avatarmanagement/v1/{personId}/{avatarId}.
- Картинки: https://school.mos.ru/avatars/{uri}.

## Пробы

Рабочий список проб — `feature/more/src/main/kotlin/ru/openmes/feature/more/ApiProbes.kt`, статусы — README.md.
