# Отчёт: заготовки, материал основной, норма расхода (лист "Объекты")

Итоговый отчёт по новой независимой фиче — созданию "Заготовки" и привязанного к ней "Материала
основного" для ДСЕ (Деталь), плюс проставлению нормы расхода на связи заготовка→материал. Не
входит в `wiki/index.md` — сырой отчёт для истории, актуальная выжимка перенесена в
`wiki/04-business-logic.md`, `wiki/05-settings-reference.md`, `wiki/03-external-api-quirks.md`.

## Что сделано

На листе "Объекты" для каждой строки, матчащей `materials.appliesToTargets` (обычно "Деталь"),
если `materials.classifierCodeColumn` ("Код классификатора объекта который записан в поле
сортамента") непустой:

1. Создаётся объект Loodsman "Заготовка" (`EditObject/new-object`, состояние `blanks.state`,
   обозначение = обозначение детали).
2. Связь деталь↔заготовка — **реверсивная**: субъект связи (`parentVersionId`) — заготовка, объект
   (`childVersionId`) — деталь. Тип связи `blanks.linkType` = "Заготовка для".
3. К заготовке (не к детали!) привязывается "Материал основной" — BO-объект, резолвится по ТОМУ ЖЕ
   коду классификатора через ПОЛИНОМ, поиском без сравнения обозначения и без фолбэка на создание
   нового элемента (та же логика, что у потока C, `resolveBomMaterialByClassifierCode`, теперь
   параметризована целевым типом). Связь заготовка→материал — `blanks.materialLinkType` =
   "Изготавливается из ..." (обычное направление, заготовка субъект).
4. На эту же связь (заготовка→материал) проставляется норма расхода — значение и единица со
   столбцов "Норма расхода"/"Единица измерения" листа "Объекты" (та же строка, что и код
   классификатора).

Это **независимый поток** от `mapping.materials` (поток A, "Материал по КД" напрямую на детали) —
деталь может одновременно получить и то, и другое, без взаимного влияния. Триггер и код
классификатора переиспользуют `materials.appliesToTargets`/`materials.classifierCodeColumn` — по
решению пользователя, без дублирования конфига под тот же смысл.

## Новые настройки (`mapping.blanks`)

```json
"blanks": {
  "target": "Заготовка",
  "state": "Проектирование",
  "linkType": "Заготовка для",
  "materialTarget": "Материал основной",
  "materialLinkType": "Изготавливается из ...",
  "rateColumn": "Норма расхода",
  "rateUnitColumn": "Единица измерения",
  "rateAttribute": "Норма расхода"
}
```

`target.isBlank()` выключает всю фичу целиком (кандидаты не собираются). `rateColumn`/
`rateAttribute` независимо управляют нормой расхода: если любой из них пуст, норма не читается/не
проставляется, остальная часть фичи (заготовка + материал) работает как обычно.

## Изменённые/новые файлы

- `domain/BlanksSettings.kt` — новый: `target`, `state`, `linkType`, `materialTarget`,
  `materialLinkType`, `rateColumn`, `rateUnitColumn`, `rateAttribute`
- `domain/BlankCandidate.kt` — новый: `detailLoodsmanId`, `detailDesignation`, `classifierCode`,
  `rate: Double?`, `rateUnitDesignation: String?`
- `mapping/Mapping.kt` — поле `blanks: BlanksSettings = BlanksSettings.None`
- `domain/MigrationContext.kt` — `blankCandidates: MutableList<BlankCandidate>`
- `domain/Status.kt` — `BLANKS_MIGRATED` (после `BOM_MATERIALS_MIGRATED`)
- `migration/MigrationEngine.kt` — сбор `blankCandidatesForRow` в `processObjectRow` (переиспользует
  уже вычисленный `materialTargetObjects`), включая парсинг нормы/единицы с логом при нечитаемом
  значении; агрегация в `runObjectsMigration()`
- `migration/MaterialsEngine.kt` — `resolveBomMaterialByClassifierCode` обобщена параметром
  `target: String` (был захардкожен `materials.materialTarget`) и перестала быть `private` —
  переиспользуется из `BlanksEngine.kt`
- `migration/BlanksEngine.kt` — новый: `runBlanksMigration()`
- `logic/Validator.kt` — `isValidBlankTargets()` (пропускает проверку, если `blanks.target` пуст)
- `workers/MigrationWorkers.kt` — `migrateBlanks()`, вызов новой проверки в `validateMapping()`
- `pipelines/MigrationPipeline.kt` — `migrateBlanks()` последним шагом после `migrateBomMaterials()`
- `client/EditObject.kt` — новый метод `setLinkAttrValues` (`EditObject/up-link-attr-values`)
- `loodsman/api/dto/UpLinkAttrValuesInputDto.kt`, `UpLinkAttrValuesOutputDto.kt` — новые
- `settings.json` — блок `mapping.blanks`

## Инциденты, найденные в ходе работы

### 1. Связь деталь↔заготовка была в обратном направлении

Первая версия создавала связь `parentVersionId = деталь, childVersionId = заготовка`. Пользователь
уточнил: связь должна быть реверсивной — субъектом должна быть заготовка. **Фикс**: поменяны
местами `parentVersionId`/`childVersionId` в `BlanksEngine.kt`. Заодно тип связи переименован —
было "Заготовка", стало "Заготовка для" (по аналогии с "Технологическая ДСЕ для", тоже
реверсивным).

### 2. Норма расхода не проставлялась — неверная модель данных

Первая реализация трактовала "Норма расхода" как аналог "Количества" у "Материала по КД" в потоке
C — т.е. как встроенное `minQuantity`/`maxQuantity`/`unitId` связи `NewLinkInputDto`. После
проверки пользователем на реальном стенде: у связи заготовка→материал по умолчанию создаётся
атрибут "Норма расхода" с величиной "Масса", единицей "кг", **пустым значением** — то есть это
самостоятельный АТРИБУТ связи в схеме Loodsman, а не встроенное поле количества. `minQuantity`/
`maxQuantity`/`unitId` — это отдельная, встроенная характеристика любой связи (величина состава),
никак не связанная с произвольными кастомными атрибутами связи.

Найден корректный эндпоинт в `swagger.lapis`: `EditObject/up-link-attr-values`
(`UpLinkAttrValuesInputDto{linkId, attributeName, attributeValue, unitGuid}`,
`< [UpLinkAttrValuesOutputDto]`) — отдельный от `EditObject/up-attr-values-by-ids`
(атрибуты ОБЪЕКТА, `versionId`) и от `EditObject/new-link` (встроенное количество связи). Требует
`linkId` (`idLink`) — оказалось, что `EditObject/new-link` уже возвращает `IdentifierDto`
созданной связи (тот же паттерн, что и для `new-object`/`create-bo-object`, задокументирован в
`wiki/03-external-api-quirks.md`), просто раньше возврат не использовался (`newLink(...)` вызывался
без захвата результата).

**Фикс**: связь заготовка→материал создаётся как обычная BOM-связь (без `minQuantity`/
`maxQuantity`/`unitId`), её `linkId` захватывается (`.asInt()`), дальше отдельным вызовом
`EditObject.setLinkAttrValues` проставляется атрибут `blanks.rateAttribute` со значением и
`unitGuid` (тот же резолв `Measure/units-by-designation` → `id`, что уже использовался для
`unitId` связи — переиспользован как есть, GUID тот же). Ответ (`isSuccess`/`errorMessage`)
проверяется явно и логируется при неудаче — на случай, если единица со столбца "Единица измерения"
не подходит под величину "Масса", закреплённую за атрибутом в схеме Loodsman.

### 3. Обсуждённый, но не воспроизведённый риск: дедупликация материала

Материал "Материал основной" резолвится с дедупликацией по коду классификатора
(`elementCache` в `BlanksEngine.kt`, тот же приём, что в потоке C) — несколько деталей с
одинаковым кодом получают ОДИН и тот же Loodsman-объект материала. Пока норма расхода
рассматривалась как атрибут ОБЪЕКТА материала, это было бы конфликтом (у разных деталей могут
быть разные нормы, второе присвоение переписало бы первое). После уточнения (норма — атрибут
СВЯЗИ, не объекта) конфликт снялся сам собой: у каждой заготовки своя собственная связь со своим
значением нормы, объект материала может безопасно переиспользоваться. Дедупликация оставлена как
есть — её отключение вместо этого создало бы реальный риск (повторный `createBoObject` на тот же
`location` = уникальный индекс Loodsman, `23505`, уже подтверждённый инцидент в проекте).

## Верификация

Билд самостоятельно не запускался — по правилам проекта ждём проверки пользователем
(`./gradlew build`/`run.sh`). Норма расхода после фикса №2 пользователем ещё не подтверждена на
реальном стенде на момент написания отчёта.
