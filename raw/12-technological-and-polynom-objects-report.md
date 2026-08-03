# Отчёт: технологические детали, Стандартные/Прочие изделия через ПОЛИНОМ, чистка state

Итоговый отчёт по трём связанным доработкам одной сессии: новый тип "Технологическая деталь" со
специальным типом/направлением связи, новый механизм создания объектов через ПОЛИНОМ для
"Стандартное изделие"/"Прочее изделие", и последующая чистка неиспользуемого поля `state` в
схеме. Не входит в `wiki/index.md` — сырой отчёт для истории, актуальная выжимка уже перенесена
в `wiki/04-business-logic.md` и `wiki/05-settings-reference.md`.

## 1. Технологическая деталь (`types[].childLinkType`/`childOfSameTypeLinkType`)

### Что сделано

Четыре комбинации условий на листе "Объекты" (все — "Раздел спецификации"="Детали"), которые
раньше не совпадали ни с одним правилом `types[]` (правило "Деталь" явно требует "Тип объекта"
пусто И обозначение НЕ оканчивается на "Т"/"T") и не создавали объект вообще:

1. "Тип объекта"=пусто + "Тип АСУП"="дет" + обозначение оканчивается на "Т"/"T"
2. "Тип объекта"="Образец" + "Тип АСУП"="дет"
3. "Тип объекта"="Образец" + "Тип АСУП"="литье"
4. "Тип объекта"="Заготовка" + "Тип АСУП"="литье"

Для всех четырёх — отдельными правилами `types[]` — теперь создаётся объект типа
"Технологическая деталь", единственный объект строки во всех четырёх случаях (подтверждено
пользователем).

### Новый тип условия `endsWith`

`migration/RuleEvaluator.kt` — зеркальный уже существовавшему `notEndsWith`:
```kotlin
"endsWith" to RuleEvaluator { rule, value ->
    value != null && rule.isValue != null && value.endsWith(rule.isValue)
},
```

### `childLinkType` — связь с реверсом направления

Изначальная реализация: объект резолвится как ПОТОМОК на листе "Связи" (по обычному коду
классификатора), связь создаётся типом `childLinkType` вместо `linksSheet.linkType`, направление
обычное (родитель→потомок). **Первая версия сломалась на реальных данных**:

```
500 Internal Server Error: "Нарушение правил связывания объектов:
[Деталь] [Технологическая ДСЕ для] [Технологическая деталь]."
```

Правило связывания в Loodsman для типа "Технологическая ДСЕ для" сконфигурировано ТОЛЬКО в
обратную сторону: субъектом связи должна быть сама "Технологическая деталь"
(`parentVersionId`), а структурный родитель листа "Связи" — объектом связи (`childVersionId`).
Заодно переименован сам тип связи: было "Технологическая ДСЕ", стало "Технологическая ДСЕ для".
**Фикс**: в точке создания связи (`runLinksMigration`, `MigrationEngine.kt`) для пар, где
`pair.child.childLinkType != null`, направление разворачивается —
`linkObjects(pair.child.loodsmanId, pair.parent.loodsmanId, childLinkType, ...)`.

### `childOfSameTypeLinkType` — частный случай "деталь входит в деталь"

Второй уточняющий инцидент от пользователя: если на листе "Связи" родитель пары САМ является
объектом с `childLinkType` (т.е. тоже "Технологическая деталь"), это уже не структурная
ДСЕ-связь, а материаловедческая — нужен тип "Изготавливается из ..." (тот же текст, что у
`materials.detailLinkType`, но независимая настройка), направление ОБЫЧНОЕ (без реверса), как у
материалов. Добавлено новое поле `childOfSameTypeLinkType` на `MappingElement`/`Identifier`,
проверяется в `runLinksMigration` ПЕРЕД веткой реверса:

```kotlin
when {
    childLinkType != null && pair.parent.childLinkType != null && childOfSameTypeLinkType != null ->
        linkObjects(pair.parent.loodsmanId, pair.child.loodsmanId, childOfSameTypeLinkType, ...) // обычное направление
    childLinkType != null ->
        linkObjects(pair.child.loodsmanId, pair.parent.loodsmanId, childLinkType, ...) // реверс
    else ->
        linkObjects(pair.parent.loodsmanId, pair.child.loodsmanId, linksSheet.linkType, ...) // обычная BOM-связь
}
```

Условие "родитель тоже особого класса" определяется исключительно наличием `childLinkType` у
родителя — без сравнения строк `target`, поскольку сегодня `childLinkType` выставлен только на
правилах "Технологическая деталь".

### Изменённые/новые файлы

- `domain/MappingElement.kt` — `childLinkType`, `childOfSameTypeLinkType` (оба `String? = null`)
- `domain/Identifier.kt` — те же два поля, переносятся с `MappingElement` в момент регистрации
  объекта для резолва листа "Связи"
- `migration/MigrationEngine.kt` — `identifiersForRow` прокидывает поля; `runLinksMigration` —
  3-ветвенный `when` вместо единственного вызова `linkObjects`
- `migration/RuleEvaluator.kt` — новый тип `endsWith`
- `settings.json` — 4 новых правила `types[]` с `target: "Технологическая деталь"`

## 2. Стандартное изделие / Прочее изделие через ПОЛИНОМ (`types[].resolveViaPolynom`)

### Что сделано

Три новые комбинации на листе "Объекты" (все — "Тип объекта"=пусто), ранее тоже не создававшие
объект:

1. "Раздел спецификации"="Стандартные изделия" + "Тип АСУП"="дет" → "Стандартное изделие"
2. "Раздел спецификации"="Стандартные изделия" + "Тип АСУП"="покуп" → "Стандартное изделие"
3. "Раздел спецификации"="Прочие изделия" + "Тип АСУП"="дет" → "Прочее изделие"

В отличие от технологических деталей — эти объекты создаются не через `EditObject/new-object`, а
тем же путём, что материалы по КД и fallback групп аналогов: поиск в ПОЛИНОМ по коду
классификатора строки (`mapping.identifierColumn`), затем `EditObject/create-bo-object` по
найденному `location`. Поиск — по тому же справочнику "Коды", что и `mapping.materials`
(`classifierCodePropertyAbsoluteCode`/`classifierCodePropertyId`/`codesReferenceName`),
отдельной настройки не заводили — по решению пользователя. Без сравнения обозначений (в отличие
от `resolveOrCreateMaterial`), как в `AnalogGroupsEngine`'s fallback. Если код не находится в
ПОЛИНОМ — лог + пропуск, без создания.

**Состояние (`state`) объекту клиент не проставляет вообще** — `create-bo-object` не принимает
такое поле. Резолвится самим Loodsman через административно настроенную привязку
type/state → свойство "применяемость" ПОЛИНОМ (`ExternalObjects/add-bo-state-and-type-state`),
тот же механизм, что уже используется для "Материал по КД" — подтверждено пользователем в
процессе уточнения требований.

### Архитектура: двухфазный резолв внутри `runObjectsMigration`

Правила с `resolveViaPolynom=true` не создают объект сразу в `processObjectRow` — конвертируются
в `PolynomBackedObjectCandidate(classifierId, target)` и откладываются. После `awaitAll()`
основного прохода по строкам, новая функция `resolvePolynomBackedObjects` группирует кандидатов
по `classifierId` (`groupBy`), резолвит конкурентно (один вызов ПОЛИНОМ на уникальный код — тот
же приём, что в `AnalogGroupsEngine.resolveUnresolvedAnalogGroupCandidates`, без отдельного
кэша/мьютекса) и добавляет результат в общий `identifiers` ДО завершения
`runObjectsMigration()` — следующий шаг пайплайна (`runLinksMigration`) видит эти объекты как
обычные, без изменений в резолве связей/количества/единицы измерения.

Проверено отдельным исследованием: `MigrationContext.polynomAccessToken` гарантированно уже
инициализирован к моменту вызова `runObjectsMigration()` — `ReplStatus.COMMAND` (единственное
состояние, из которого доступна команда `migration`) устанавливается только из успешного
`polynomLogin()`, в том же блоке, где присваивается токен. Поэтому вызов ПОЛИНОМ прямо из шага
"Объекты" безопасен, новый `Status`/шаг пайплайна не потребовался.

### Изменённые/новые файлы

- `domain/PolynomBackedObjectCandidate.kt` — новый: `data class(classifierId: Long, target: String)`
- `domain/MappingElement.kt` — `resolveViaPolynom: Boolean = false`
- `migration/MigrationEngine.kt` — `ObjectRowResult.polynomBackedCandidates`; `processObjectRow`
  партиционирует `matches` на `polynomRules`/`directRules`; новая приватная функция
  `resolvePolynomBackedObjects`
- `settings.json` — 3 новых правила `types[]` с `resolveViaPolynom: true`

Переиспользованы существующие public-хелперы без изменений: `resolveSearchScope()` и
`resolvePropertyDefinitionByAbsoluteCode(...)` (оба объявлены в `MaterialsEngine.kt`, уже были
рассчитаны на переиспользование из другого файла пакета).

## 3. Чистка мёртвого/игнорируемого `state`

Пользователь заметил: `state` у "Материал по КД" (`materials.materialState`) и у новых
"Стандартное изделие"/"Прочее изделие" (`types[].state` при `resolveViaPolynom=true`) нигде не
используется — `create-bo-object` не принимает поле состояния вообще, состояние резолвит сам
Loodsman через привязку к "применяемости". Оставлять такие поля в схеме — сбивает с толку
(выглядят как настраиваемые, по факту декоративны).

**Проверено**: `materials.materialState` оказался мёртвым полностью — не читался кодом вообще,
даже для основного потока "Материал по КД" (только упоминался в комментарии
`AnalogGroupsEngine.kt:142`). `types[].state`, наоборот, реально используется для всех правил
без `resolveViaPolynom` (обычный `EditObject/new-object`, `stateName`).

**Фикс**:
- `domain/MappingElement.state` → `String? = null` (было обязательным `String`) — не указывается
  в JSON для правил с `resolveViaPolynom: true`
- `domain/MaterialsSettings.materialState` — поле удалено из схемы полностью (вместе со значением
  в `companion object None`)
- `settings.json` — `materialState` убран из `mapping.materials`; `state` убран из 3 правил
  "Стандартное изделие"/"Прочее изделие"
- `NewObjectInputDto.stateName` уже был `String? = null` — приведение типа не потребовалось,
  `mappingElement.state` передаётся как есть

## Верификация

Пользователь сам поправил правило "Прочее изделие" в `settings.json` (было "покуп" вместо
"дет" в первой версии) и подтвердил, что объекты создаются. Билд самостоятельно не запускался —
по правилам проекта ждём проверки пользователем (`./gradlew build`/`run.sh`).
