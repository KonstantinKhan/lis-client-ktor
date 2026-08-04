# Отчёт: классификация объектов ЛОЦМАН в ПОЛИНОМ (BoReference/reference-bo-version) + retry на таймаутах

Итоговый отчёт по двум связанным изменениям: (1) новая фича — классификация обычных объектов,
создаваемых на шаге "Объекты", в ПОЛИНОМ:MDM; (2) фикс реального инцидента, найденного при первом
прогоне фичи на боевом стенде — таймаут `EditObject/create-bo-object` под нагрузкой. Не входит в
`wiki/index.md` — сырой отчёт для истории, актуальная выжимка перенесена в
`wiki/04-business-logic.md`, `wiki/03-external-api-quirks.md`, `wiki/11-api-reference.md`,
`wiki/12-key-classes.md`, `wiki/14-known-issues.md`.

## Часть 1. Классификация объектов через BoReference/reference-bo-version

### Что сделано

Раньше объекты, создаваемые на шаге "Объекты" обычным `EditObject/new-object`
(`createLoodsmanObject` в `MigrationEngine.kt`, правила `mapping.types[]` без `resolveViaPolynom`),
не получали никакой привязки к элементу ПОЛИНОМ:MDM. Привязка (через `location`) была только у
объектов, которые изначально создаются другим методом — `EditObject/create-bo-object` (материалы,
"Стандартные/Прочие изделия", fallback групп аналогов, заготовки).

Добавлена недостающая классификация: после создания обычного не-папочного объекта код
классификатора этого объекта (`mapping.identifierColumn`, тот же код, что резолвит структуру BOM)
ищется в ПОЛИНОМ, и если найден — созданная версия Loodsman привязывается к его `location` через
`BoReference/reference-bo-version` (`versionId` = Loodsman id объекта, `boTypeBindingRuleId` = 0,
`objectLocation` = location).

Метод и DTO подтверждены по `swagger.lapis`:
```
create_reference_bo_version POST /api/v4/BoReference/reference-bo-version
  > reference_boversion_input_dto: ReferenceBOVersionInputDto

ReferenceBOVersionInputDto:
  versionId?: int
  boTypeBindingRuleId?: int
  objectLocation?: str?
```

Поиск location переиспользует уже существующий путь (тот же, что у "Стандартных/Прочих изделий" —
`resolvePolynomBackedObjects` в `MigrationEngine.kt` — и у материалов в `MaterialsEngine.kt`):
резолв property-definition по `mapping.materials.classifierCodePropertyAbsoluteCode`/
`classifierCodePropertyId`, поиск через `polynomClient.search.searchByStringProperty` со scope
`resolveSearchScope()`, затем `polynomClient.classification.getLocation(...)`. Отдельная настройка
под эту фичу не заводилась — по решению пользователя переиспользован существующий
`mapping.materials.*` (тот же принцип, что уже применён для `bomMaterials`/AnalogGroups
fallback/`resolveViaPolynom`).

**Область действия** (решение пользователя, зафиксировано через `AskUserQuestion`): классифицируются
только НЕ-папочные объекты, созданные обычным `EditObject/new-object`. Объекты через
`create-bo-object` не переклассифицируются — уже привязаны к ПОЛИНОМ в момент создания, повторный
вызов избыточен. Папки не классифицируются — организационный контейнер, не участвует в
BOM-структуре, классифицировать по коду классификатора нечем.

Если элемент в ПОЛИНОМ не найден — объект всё равно остаётся созданным, в лог пишется
предупреждение, миграция продолжается (та же семантика "не найдено в справочнике — не ошибка", что
везде в проекте: единицы измерения, материалы по КД, fallback групп аналогов).

### Изменённые/новые файлы

- `loodsman/api/dto/ReferenceBoVersionInputDto.kt` — новый: `versionId`, `boTypeBindingRuleId = 0`,
  `objectLocation`
- `client/BoReference.kt` — новый: `referenceBoVersion(sessionId, data)`, `POST
  BoReference/reference-bo-version`, без типизированного тела ответа (swagger не описывает `<` для
  этого метода — тот же приём, что `EditObject.upLink`)
- `client/Client.kt` — поле `val boReference = BoReference(client, requestGate)`
- `domain/ObjectClassificationCandidate.kt` — новый: `loodsmanId: Int`, `classifierId: Long` (тот
  же стиль, что `PolynomBackedObjectCandidate`)
- `migration/MigrationEngine.kt`:
  - `ObjectRowResult` — новое поле `classificationCandidates: List<ObjectClassificationCandidate>`
  - `processObjectRow` — кандидаты собираются там же, где `identifiersForRow` (не-папочные
    объекты, `classifierIdForRow != null`)
  - новая функция `classifyCreatedObjects(candidates)` — резолвит `classifierCodeProperty`/
    `searchScope` один раз, группирует кандидатов по `classifierId` (один поиск в ПОЛИНОМ на
    уникальный код), `reference-bo-version` вызывается отдельно для каждого `loodsmanId` в группе;
    ошибки на любом шаге (поиск/getLocation/привязка) — лог + счётчик, не бросают исключение
  - вызов `classifyCreatedObjects(...)` в `runObjectsMigration()` — после
    `resolvePolynomBackedObjects(...)`, до финального `println` со статистикой

### Верификация

Пользователь прогнал миграцию на реальном стенде с новой фичей. Классификация отработала —
единственная проблема, о которой сообщил пользователь, не связана с логикой классификации
напрямую (см. часть 2 ниже).

## Часть 2. Инцидент: таймаут create-bo-object под нагрузкой + retry-фикс

### Инцидент

На боевом прогоне поймана ошибка:
```
Не удалось создать 'Стандартное изделие' по коду классификатора '102310000000012172':
Request timeout has expired [url=http://127.0.0.1:8076/api/v4/EditObject/create-bo-object,
request_timeout=30000 ms]
```
Кандидат пропущен (лог + счётчик, штатный fallback `resolvePolynomBackedObjects`), миграция не
упала — но объект недосоздан без ручного вмешательства. Причина — сервер (Loodsman → ПОЛИНОМ)
под нагрузкой не уложился в `requestTimeoutMillis` (30 секунд).

### Рассмотренные варианты

1. Retry с backoff на transient-ошибках (таймаут/5xx).
2. Поднять `requestTimeoutMillis`.
3. Снизить `maxConcurrentRequests` в `settings.json`.
4. Собирать список пропущенных кандидатов в отдельный файл для точечного повторного прогона.

Выбраны варианты 1 и 3. Вариант 3 пользователь применяет самостоятельно через `settings.json`, без
изменения кода. Вариант 1 реализован.

### Фикс: retryOnTransientError

`client/Helpers.kt` — новая функция:
```kotlin
suspend fun <T> retryOnTransientError(
    times: Int = 3,
    initialDelayMs: Long = 1_000,
    block: suspend () -> T
): T
```
До 3 попыток, экспоненциальный backoff (1с → 2с), ловит только `HttpRequestTimeoutException` и
`ResponseException` со статусом 5xx — всё остальное (4xx, парсинг и т.д.) пробрасывается сразу без
повтора.

Обёрнуты **только** `EditObject.createBoObject` и `BoReference.referenceBoVersion`
(`client/EditObject.kt`, `client/BoReference.kt`) — `retryOnTransientError` снаружи,
`requestGate.withPermit` внутри каждой попытки: permit не удерживается на время задержки между
попытками, не блокирует остальную конкурентную нагрузку.

**Осознанно НЕ применено к `EditObject.newLink`/`EditObject.create` (`new-object`)** — таймаут не
гарантирует, что запрос не выполнился на сервере (могла потеряться только сетевая часть ответа);
повтор неидемпотентного вызова без защиты от дубля мог бы создать второй объект/связь с теми же
данными. `create-bo-object` безопасен для retry именно благодаря уникальному индексу Loodsman по
`location` (`idx_uq_stmain_stkeyattr_inidtype`, см. `wiki/03-external-api-quirks.md`) — повторный
вызов после уже успешно выполнившегося первого упадёт на конфликте индекса, а не создаст дубль
молча. `reference-bo-version` по своей природе — идемпотентная привязка (повторная привязка того
же `versionId` к тому же `location` не создаёт новых сущностей).

### Изменённые файлы

- `client/Helpers.kt` — новая `retryOnTransientError()`
- `client/EditObject.kt` — `createBoObject` обёрнут в `retryOnTransientError`
- `client/BoReference.kt` — `referenceBoVersion` обёрнут в `retryOnTransientError`

### Верификация

Билд самостоятельно не запускался — по правилам проекта ждём проверки пользователем. Повторный
прогон на боевом стенде с retry-фиксом на момент написания отчёта не подтверждён.
