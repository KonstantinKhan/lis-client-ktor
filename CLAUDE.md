# lis-client-ktor

Kotlin/Ktor CLI-инструмент для миграции данных из Excel (BOM/спецификация) в Loodsman PLM по
REST API. Правила миграции (какие строки каким типам объектов соответствуют, какие атрибуты
проставлять, как строить связи) заданы в `settings.json` — это JSON-DSL, а не хардкод в коде.
Цель архитектуры: новую миграцию собирать правкой `settings.json` +, при необходимости,
регистрацией нового обработчика правила — не переписывать движок с нуля под каждую задачу.

## Быстрый старт

- `./run.sh` / `run.cmd` — `installDist` + запуск собранного дистрибутива. **Не** `./gradlew run`:
  `login()` (`workers/LoginWorkers.kt`) требует настоящей консоли (`System.console()`), а под
  `gradlew run` JVM форкается через pipe и консоли нет — упадёт с понятной ошибкой.
- `settings.json` в корне проекта — конфиг подключения + маппинг Excel→Loodsman, читается
  относительно рабочей директории (`MigrationContext.configFileName`, по умолчанию `"settings.json"`).
- REPL-команды после логина/чекаута: `migration` (запустить миграцию), `exit` (checkin + выход —
  **важно**: без `exit` изменения лежат в чекауте и не видны снаружи сессии, это нормальное
  поведение Loodsman, не баг).
- Тесты: `./gradlew test`.

## Карта пакетов

- `client/` — Ktor HTTP-клиент (`Client.kt`) + суб-клиенты `Login`/`EditObject`/`ConfMetaData`/
  `CheckOut`/`ObjectInfo`/`Measure`/`ObjectConfiguration`. Все HTTP-вызовы идут через общий
  `Client.requestGate: Semaphore` — единственная точка контроля нагрузки на Loodsman (см.
  "Конкурентность" ниже).
- `domain/` — классы-зеркала схемы `settings.json` (`Settings`, `Mapping`, `MappingElement`,
  `Conditions`, `Rule`, `Attribute`, `ReplaceRule`, `Connection`, `ObjectsSheet`, `LinksSheet`) +
  `MigrationContext` — общее mutable-состояние REPL-сессии (сквозной контекст для всего pipeline).
- `dsl/` — кастомный Chain-of-Responsibility DSL (см. ниже), общая инфраструктура выполнения шагов.
- `pipelines/` + `workers/` — конкретные шаги на этом DSL: `PreparePipeline` (чтение конфига),
  `LoodsmanInit` (логин → чекаут), `MigrationPipeline` (сама миграция), `LoodsmanExit` (checkin).
- `migration/` — движок правил миграции: `RuleEvaluators` (реестр интерпретаторов `Rule.type`),
  `ConditionsEvaluator` (матчинг `single`/`or`/`and`), `AttributeResolver`+`ReplaceRuleStrategies`
  (резолв значений атрибутов), `MigrationEngine.kt` (`runObjectsMigration`/`runLinksMigration` —
  собственно оркестрация чтения Excel → создания объектов → создания связей), `MaterialsEngine.kt`
  (`runMaterialsMigration` — сортаментные материалы через ПОЛИНОМ, включая заменители, общий
  пайплайн `processMaterialCandidates`, см. ниже; `runBomMaterialsMigration` — материалы по КД
  для DS-объектов, только по коду классификатора, без ПОЛИНОМ-фолбэка, см. ниже).
- `excel/` — `ExcelSaxParser` — потоковый (SAX, не DOM) парсер xlsx, отдаёт `Flow<ExcelRow>` с
  опциональным фильтром по имени листа.
- `repl/` — интерактивная консоль (`ReplConsole`), команды через `ICommand` (`suspend fun execute`),
  диспетчеризуется по `ReplStatus`.
- `mapping/` — тонкие Dto↔Domain конвертеры (`toApiDto`, `toDomain`).
- `logic/` — `Validator` (проверка, что все `target` из `settings.json` существуют как типы в
  Loodsman — гейт перед стартом миграции, чтобы не упасть на середине).
- `loodsman/api/dto/` — сериализуемые DTO под конкретные эндпоинты Loodsman.

## COR DSL (`dsl/`)

```kotlin
object SomePipeline : ICorExec<MigrationContext> by pipeline<MigrationContext>({
    worker {
        on { status == Status.SOME_STATE }       // T.() -> Boolean, дефолт — всегда true
        handle { /* suspend T.() -> Unit */ }     // основная логика
        except { e -> /* T.(Throwable) -> Unit */ } // дефолт — молча глотает исключение!
    }
}).build()
```

- `ICorWorker.execute()` (`dsl/core/ICorWorker.kt`): если `on()` истинно — выполняет `handle()`;
  исключение из `handle()` ловится и передаётся в `except()`. **Если `except()` не вызвать `throw`
  явно — исключение молча гасится**, и chain идёт дальше как ни в чём не бывало. При добавлении
  worker'а с реальной вероятностью падения — либо явный `except { throw it }`, либо осознанно
  решить, что тихое продолжение — ожидаемое поведение (как для read-only шагов).
  Ещё одно следствие: если worker внутри `pipeline{}` перебрасывает исключение из `except`, оно
  долетает до **родительского** chain и там либо гасится (если у родителя своего `except{}` нет —
  дефолт молчит), либо тоже перебрасывается. У `PreparePipeline`/`LoodsmanInit`/`MigrationPipeline`
  верхнеуровневого `except{}` нет — падение проброшенного исключения долетает до вызывающего кода
  (`ReplConsole`/`MigrationCommand`).
- `worker{}`/`pipeline{}` — билдеры, добавляют узел в `ICorChainDsl<T>`. `CorChain` выполняет детей
  строго последовательно (`execs.forEach { it.execute(context) }`).
- Активация каждого шага в `pipelines/`+`workers/` идёт через `MigrationContext.status: Status` —
  простая ручная стейт-машина: worker меняет `status` на выходе, следующий worker гейтится на новое
  значение.
- **`parallel{}` в DSL не подключён к реальному `CorParallel`** (`dsl/dsl.kt` — билдер `parallel`
  собирает обычный последовательный `CorWorkerDsl`, класс `CorParallel` существует, но никем не
  вызывается). Не полагаться на него — конкурентность в проекте реализована на уровне `Flow`/
  `async` внутри `migration/MigrationEngine.kt`, не через DSL.

## Rule-engine и схема `settings.json`

```jsonc
{
  "connection": { "dbName": "...", "url": "...", "maxConcurrentRequests": 10 },
  "mapping": {
    "source": { "type": "xlsx", "path": "..." },
    "objectsSheet": { "name": "Объекты", "headersRowIndex": 1 },
    "linksSheet": { "name": "Связи", "headersRowIndex": 1, "parentColumnIndex": 1,
                     "childColumnIndex": 2, "linkType": "Состоит из ...",
                     "quantityColumn": "конструкторское количество",
                     "unitColumn": "единица измерения",
                     "unitExcludeValues": ["компл", "-"] },
    "identifierColumn": "код классификатора",
    "attributes": [
      { "attrColumn": "наименование", "loodsmanAttr": "Наименование" },
      { "attrColumn": "Без чертежа", "loodsmanAttr": "Дополнение наименования",
        "replace": { "type": "any", "find": "any", "target": "БЧ" } }
    ],
    "types": [
      { "target": "Папка", "source": "обозначение", "state": "Папка для чтения",
        "isProject": false, "linkToRoot": true,
        "conditions": { "single": {"type":"check","column":"...","is":"..."}, "or": [], "and": [] } }
    ],
    "bomMaterials": { "specificationConditions": { "single": {"type":"check","column":"конструкторская спецификация","is":"DS"},
                                                    "or": [], "and": [] },
                      "materialConditions": { "single": {"type":"check","column":"Раздел спецификации","is":"Материалы"},
                                              "or": [], "and": [] } }
  }
}
```

- `Conditions.single/or/and` — три независимые группы, КАЖДАЯ непустая группа должна пройти (AND
  между группами); пустая/неприменимая группа (`{}` или `[]`) не блокирует — считается `true`.
  `single` в JSON всегда объект (`{}`, если не используется) — проверка "применимо ли" идёт по
  `single.column != null`, а не по `single != null` (частая ловушка).
- `Rule.type` ("check" = точное совпадение, "parse" = НЕ заканчивается на значение) резолвится
  через реестр `RuleEvaluators` (`migration/RuleEvaluator.kt`) — новый тип условия добавляется
  регистрацией `RuleEvaluators.register("новый_тип") { rule, value -> ... }`, без правки
  `ConditionsEvaluator`.
- `Attribute.replace` — аналогичный реестр `ReplaceRuleStrategies` (`migration/AttributeResolver.kt`).
  Сейчас одна нетривиальная стратегия `ReplaceAny`: если сырое значение непустое — подставить
  `target` (используется как "флаг присутствия", не буквальный find/replace, несмотря на название
  полей `find`/`target`).
- `MappingElement.isProject` — см. предупреждение про Loodsman ниже, **не** ставить `true` ни на
  что, кроме настоящего корня миграции.
- `MappingElement.linkToRoot` — жёсткая привязка к root ("Миграция") сразу после создания. Для
  сборочных единиц/деталей, у которых родитель определяется листом "Связи", `linkToRoot: false`.
- **Одна строка Excel может матчить несколько правил одновременно** (например, "Папка" и головная
  "Сборочная единица" с одним и тем же `обозначение`/классификатором) — `MigrationEngine.
  processObjectRow` создаёт объект на каждый матч, а не только первый. Не-проектные объекты,
  созданные из той же строки, что и проектная папка, автоматически линкуются к этой папке (а не к
  root) — см. `processObjectRow` в `migration/MigrationEngine.kt`.
- `identifiers` (используется `runLinksMigration` для резолва листа "Связи" по `classifierId`)
  содержит ТОЛЬКО не-проектные (`isProject == false`) объекты — проектные папки исключены из
  структуры BOM намеренно, они не участвуют в связях по листу "Связи".
- `linksSheet.unitColumn`/`unitExcludeValues` — единица измерения (`Unit`/`Measure`) связи
  "Состоит из ...". `unitColumn` — имя столбца Excel с обозначением единицы (ищется по имени,
  как `quantityColumn`, не по индексу); `unitExcludeValues` — список значений (например
  `"компл"`, `"-"`), при которых unit связи не трогается вообще (остаётся дефолт API).
  `runLinksMigration` резолвит уникальные обозначения (не по одному на связь — designation
  обычно повторяется в сотнях строк) через `Client.measure.unitsByDesignation`, тем же
  паттерном конкурентности, что и остальной движок (см. "Конкурентность" ниже). Если
  `units-by-designation` вернул 0 или >1 результат (обозначение не найдено или неоднозначно
  между разными величинами) — unit для соответствующих связей не проставляется, случай
  логируется и считается (`unitsNotFound`/`unitsCollision` в summary), миграция продолжается.
- `mapping.bomMaterials` (`specificationConditions`/`materialConditions`) — признак "у объекта
  есть собственная конструкторская спецификация" (DS). Обе — та же `Conditions`/
  `ConditionsEvaluator`, что и `mapping.types[].conditions`: `ConditionsEvaluator.matches`
  работает по `RowView` независимо от листа-источника. **Обе проверяются на строках листа
  "Объекты"** — лист "Связи" в этом проекте содержит только коды классификаторов и количества,
  раздела спецификации там нет вообще (проверено вживую — см. инцидент ниже).
  - `specificationConditions` — на строке САМОГО объекта (`processObjectRow`), например
    `{"single":{"type":"check","column":"конструкторская спецификация","is":"DS"}}`.
    **Не заданные условия (`isConfigured` == false) выключают фичу целиком** — специально
    проверяется через `ConditionsEvaluator.isConfigured`, а не голый `matches(...)`: пустая
    `Conditions()` для `matches` означает "всегда true" (годится для `types[]`, где элемент списка
    уже сам по себе opt-in), но здесь означала бы "любой объект — DS", что не то, что нужно по
    умолчанию.
  - `materialConditions` — на строке КАЖДОГО child'а (тоже лист "Объекты"), например
    `{"single":{"type":"check","column":"Раздел спецификации","is":"Материалы"}}`. Строка раздела
    "Материалы" обычно не матчит ни одно `mapping.types[]` правило (нет типа для этого раздела) и
    поэтому не становится объектом — но её `classifierId` всё равно собирается в
    `processObjectRow` ДО early-return на пустых `matches` (иначе строка никогда не попадёт в
    `bomMaterialRowClassifierIds`) и кладётся в `MigrationContext.bomMaterialRowClassifierIds`.
    Пустая `Conditions()` здесь намеренно "всегда true" (доп. фильтр поверх
    `specificationConditions`, а не самостоятельный гейт).
  - **Реальный инцидент**: `materialConditions` изначально проверялся на строке листа "Связи"
    (по аналогии с `linksSheet.unitColumn`/`quantityColumn`) — казалось логичным, но на практике
    на "Связи" такого столбца не оказалось вообще, из-за чего условие никогда не проходило и ни
    один материал не создавался (кандидатов всегда было 0). Если понадобится проверять что-то по
    самой строке "Связи" — придётся заново подтвердить, что нужный столбец там реально есть,
    а не просто предположить это по аналогии с другими полями `linksSheet`.

  Если объект прошёл `specificationConditions`, `runLinksMigration` для его нерезолвленных
  child-строк листа "Связи" (child — валидный `Long`, но не совпал ни с одним созданным объектом)
  сверяет `childClassifierId` с `bomMaterialRowClassifierIds`; при совпадении трактует его как код
  классификатора "Материала по КД" и откладывает в `MigrationContext.bomMaterialCandidates`, а не
  молча теряет связь, как обычно (см. `processObjectRow`/`runLinksMigration` в
  `migration/MigrationEngine.kt`). Если `childColumnIndex` вообще не парсится как `Long`
  (адресация материала по обозначению/наименованию, а не по коду классификатора, — не
  подтверждённый вживую, но правдоподобный случай) — `bomMaterialRowClassifierIds` сверить не с
  чем, используется только признак DS родителя. Обработка — `runBomMaterialsMigration()`
  (`migration/MaterialsEngine.kt`), отдельный шаг pipeline (`Status.MATERIALS_MIGRATED →
  BOM_MATERIALS_MIGRATED`) ПОСЛЕ сортаментного `runMaterialsMigration`: ищет элемент в ПОЛИНОМ
  только по коду классификатора (переиспользует
  `classifierCodePropertyAbsoluteCode`/`codesReferenceName`/`classifierCodePropertyId` из
  `materials`), БЕЗ сравнения обозначения по чертежу и БЕЗ фолбэка на создание нового элемента в
  ПОЛИНОМ (если код не нашёл совпадения — связь пропускается, лог + счётчик, не ошибка). Найденный
  элемент материализуется в Loodsman тем же `createBoObject`, что и в сортаментном потоке
  (`materials.materialTarget`), связь — `linksSheet.linkType` ("Состоит из ..."), НЕ
  `materials.detailLinkType`. `BomMaterialCandidate` несёт СВОИ `quantity`/`unitDesignation`,
  прочитанные с ТОЙ ЖЕ строки "Связи" (`linksSheet.quantityColumn`/`unitColumn`) — реальный
  инцидент: изначально эти поля не собирались вовсе, и все связи материал↔родитель через этот
  путь создавались с дефолтом API (`1.0`, без unit), никак не выделяясь среди обычных связей.
  `runBomMaterialsMigrationInternal` резолвит unit те же уникальные обозначения через
  `resolveUnitId` (вынесен из `private` в `MigrationEngine.kt` специально для переиспользования
  здесь) — тот же паттерн, что в `runLinksMigration`.
- `materials.substituteDrawingDesignationColumn` — материал-заменитель (столбец "Материал
  заменитель по чертежу" на листе "Объекты"), второй независимый кандидат материала на ту же
  деталь. Разделяет с основным материалом ВСЁ остальное (`classifierCodeColumn`, `hierarchy`,
  `materialTarget`/`materialState`, `detailLinkType` — решение пользователя: заменитель линкуется
  тем же типом связи "Изготавливается из ..."), различается только колонка обозначения по
  чертежу. Собирается в `processObjectRow` в ОТДЕЛЬНЫЙ список
  (`MigrationContext.materialSubstituteCandidates`, не в `materialCandidates`) — если бы основной
  и заменитель одной детали (у них общий `classifierCode`, см. решение пользователя) попали в
  один `candidatesByKey`, группа дедупликации схлопнула бы их в один резолв и связался бы только
  один из двух. `MaterialsEngine.processMaterialCandidates` — общий пайплайн резолва/создания/
  линковки, вызывается дважды (для `materialCandidates` и `materialSubstituteCandidates`) с
  ОБЩИМ `elementCache`/мьютексами (если один и тот же элемент Полином окажется и чьим-то основным
  материалом, и чьим-то заменителем — нужен ровно один Loodsman-объект на него). `""` (дефолт) —
  заменители не собираются вовсе.
- **Группа замены материала** (`ObjectConfiguration/new-change-group-2` + `new-change-variant-2`,
  `client/ObjectConfiguration.kt`, `MaterialsEngine.createSubstituteChangeGroups`/
  `createSubstituteChangeGroup`) — создаётся ТОЛЬКО для деталей, у которых реально резолвился и
  связался И основной материал, И заменитель (`mainLinked`/`substituteLinked` — карты
  `detailLoodsmanId -> materialLoodsmanId`, которые `processMaterialCandidates` теперь возвращает
  вместо `Unit`, пересечение по ключу детали). Деталь без заменителя (пустая ячейка
  `substituteDrawingDesignationColumn`, отфильтровано ещё в `processObjectRow`) группу не получает.
  Порядок вызовов на деталь: `new-change-group-2` (`versionId`=деталь, `changeGroupName`=
  `materials.changeGroupName`, `groupType`=2 — жёстко в коде, `CHANGE_GROUP_TYPE_MATERIAL`, не
  вынесено в settings) → `ObjectInfo.linkedFast(detailLoodsmanId, materials.detailLinkType)` ОДИН
  раз (обе связи "Изготавливается из ..." — основной материал и заменитель — под одним родителем,
  поэтому один вызов возвращает обе) → сопоставление нужного `idLink` по `idVersion ==
  <Loodsman id материала>` (не по `product`/`version`, как в
  `docs/link-measure-unit-migration.md` — там predлагался этот путь для чужого случая; здесь уже
  есть точный Loodsman id обеих сторон, сверка по нему надёжнее) → два `new-change-variant-2`
  (`changeGroupId` из первого вызова, `changeVariantName`=`materials.mainMaterialVariantName`/
  `substituteMaterialVariantName`, `linkFirstVariantId`=найденный `idLink`, `isBasic`=`true`/
  `false`). Если `idLink` для одной из сторон не нашёлся — вся группа для этой детали пропускается
  с логом (не полу-созданная группа с одним вариантом).

## Конкурентность

Единственная реальная точка троттлинга запросов к Loodsman — `Client.requestGate:
Semaphore(connection.maxConcurrentRequests)`, которым обёрнут каждый HTTP-вызов в каждом
суб-клиенте (`Login`/`EditObject`/`ConfMetaData`/`CheckOut`/`ObjectInfo`). Локальная обработка
строк Excel (матчинг условий, сборка DTO) — дешёвая, поэтому в `MigrationEngine.kt` она просто
запускается вся сразу (`rows.map { async { ... } }` внутри `coroutineScope`, `awaitAll()`), без
собственного лимита — корутины большую часть времени просто ждут permit семафора.

**Исключение, подтверждённое вживую**: `ObjectConfiguration/new-change-group-2` НЕ безопасен для
конкурентных вызовов даже на РАЗНЫЕ объекты — конкурентный запуск уронил Postgres в deadlock
(`40P01`) внутри собственной хранимой процедуры Loodsman (`dt_variants.prnewchangegroup` →
блокировка tuple в `stlocks`/`stchanges`), в отличие от `new-object`/`new-link`, чья конкурентность
подтверждена рабочей (см. ниже). `MaterialsEngine.createSubstituteChangeGroups` поэтому вызывает
`createSubstituteChangeGroup` **последовательно** (`forEach`, без `async`/`awaitAll`) — если
понадобится ускорить, сначала проверить на реальном стенде, не вернётся ли deadlock.

**Антипаттерн, которого нужно избегать в этом коде** (реальный инцидент, не гипотетический):
`flatMapMerge` поверх `channelFlow`, который внутри блокирующе (`trySendBlocking`) кормит данные
из чужого Java SAX-парсера (POI/Xerces), да ещё и с ранней отменой через `.first{}` — вызвало
настоящий deadlock на ~400 строках (объекты переставали создаваться после первого, без единой
ошибки в логе, зависание на много минут). Кооперативная отмена корутин через `trySendBlocking`
не гарантированно долетает сквозь произвольный чужой blocking-код, который может проглотить
`CancellationException` как обычный `RuntimeException`. Текущее решение: `ExcelSaxParser.parse()`
собирается ПОЛНОСТЬЮ в `List` через `.toList()` (без ранней отмены), и только потом список
обрабатывается конкурентно через `async`/`awaitAll`. Если возникнет соблазн оптимизировать через
`flatMapMerge` для больших файлов — сначала перечитать этот абзац.

## Особенности API Loodsman (наработано вживую, экономит часы отладки)

- `EditObject/new-object` (`EditObject.create`) создаёт объект СРАЗУ в текущем подключённом
  чекауте — подтверждено вручную через Swagger. Отдельный "insert-object" эндпоинт
  (`InsertObjectModel`, есть в `loodsman/api/dto/` как наследие старого экспериментального кода в
  `Main.kt`) для этого не нужен.
- Содержимое чекаута не видно снаружи текущей сессии (ни в UI-списке "объекты за сегодня", ни в
  других сессиях под тем же логином) до `CheckOut/check-in-2` (команда `exit` → `LoodsmanExit` →
  `workers/CheckoutWorkers.kt:checkin()`). Это штатное поведение Loodsman, не повод искать баг.
- `isProject=true` делает объект отдельным ПРОЕКТОМ верхнего уровня в клиенте Loodsman — он будет
  отображаться на одном уровне с другими проектами независимо от структурных связей (`newLink`).
  Использовать `isProject=true` только для настоящего корня миграции ("Миграция", создаётся в
  `workers/CreateWorkers.kt:createRoot()`). Если поставить `isProject=true` на промежуточную папку
  проекта — она "задвоится": будет и вложена по структуре, и торчать отдельным проектом рядом с
  root. Реальный баг, который так и проявился и был исправлен переводом `"Папка".isProject` в
  `false` в `settings.json`.
- Чекаут открывается ИМЕННО на созданном root-объекте (`checkout()` в `workers/CheckoutWorkers.kt`
  вызывается с `typeName`/`name` = имя root), сразу после `createRoot()`, до `connectCheckout()`.
  Вся структура миграции создаётся уже внутри этого чекаута — не нужно отдельно "брать в работу".
- Конкурентные запросы (создание объектов/связей) в рамках одного чекаута Loodsman переносит
  нормально — проверено на `maxConcurrentRequests: 10` и ~400 строках, 0 потерянных объектов.

## Status / ReplStatus

`ReplConsole` крутит `while(true)` по `MigrationContext.replStatus` (`repl/ReplStatus.kt`):
`START` → `PreparePipeline.execute()` (чтение `settings.json`) → `AUTH` → `LoodsmanInit.execute()`
(логин → `allTypes()` → `createRoot()` → `checkout()` → `connectCheckout()`) → `COMMAND` (REPL
принимает ввод, диспетчеризует в `ICommand` по первому слову строки).

Внутри `LoodsmanInit`/`MigrationPipeline` шаги гейтятся отдельным, более гранулярным
`MigrationContext.status: Status` (`domain/Status.kt`): `START → EXIST_CONFIG/NOT_CONFIG → LOGIN →
LOGIN_SUCCESS → CHECKOUT → CONNECT_CHECKOUT → OBJECTS_MIGRATED → LINKS_MIGRATED →
MATERIALS_MIGRATED → BOM_MATERIALS_MIGRATED`. Это два разных enum на разных уровнях детализации,
не путать.

## Как добавить

- **Новое правило миграции** — в типовом случае только `settings.json`: новый элемент
  `mapping.types[]` с `conditions`/`state`/`isProject`/`linkToRoot`, без единой строчки кода.
- **Новый тип условия/replace-стратегии** — регистрация в `RuleEvaluators`/`ReplaceRuleStrategies`
  (`migration/`), не правка `ConditionsEvaluator`/`AttributeResolver`.
- **Новый REPL-command** — реализовать `ICommand` (`repl/commands/`), зарегистрировать в
  `defaultCommands()` (`repl/DefaultCommands.kt`).
- **Новый Loodsman-эндпоинт** — DTO в `loodsman/api/dto/`, метод в соответствующем суб-клиенте
  `client/*.kt` (обязательно через `requestGate.withPermit { ... }`, см. существующие методы).
- **Новый шаг pipeline** — extension-функция `ICorChainDsl<MigrationContext>.xxx() = worker { ... }`
  в `workers/`, добавить вызов в нужный `pipelines/*.kt`.

## Тесты

`src/test/kotlin/.../migration/ConditionsEvaluatorTest.kt` и `AttributeResolverTest.kt` —
образец паттерна для остальной rule-engine логики: конструируют `Conditions`/`Attribute` вручную
(без парсинга JSON) и гоняют через реальные функции движка. Использовать этот же подход для новых
правил/стратегий.

## Осознанно не сделано

- `parallel{}` в COR DSL не подключён к `CorParallel` (см. выше) — если понадобится параллелизм на
  уровне pipeline-шагов, а не строк, потребуется сначала это починить.
- `login()` требует интерактивной консоли, не работает под `gradlew run`/CI — только через
  `installDist`-бинарник, запущенный из настоящего терминала.
- `EditObject.upLink` (`up-link`) построен и рабочий, но НЕ вызывается из `MigrationEngine` —
  миграция только создаёт связи (`newLink`, unit передаётся сразу через `NewLinkInputDto.unitId`),
  никогда не правит уже существующие. Задел под будущий сценарий донастройки unit'ов на уже
  смигрированном дереве (см. `docs/link-measure-unit-migration.md`). При использовании `upLink` —
  `delLink` ОБЯЗАТЕЛЬНО `false`, иначе связь удаляется.
  (`ObjectInfo.linkedFast`, построенный тогда же, с тех пор получил реальный вызов — см.
  `MaterialsEngine.createSubstituteChangeGroup` ниже.)
- `MetaData/get-measure-list-for-link` (опциональный safety-check из
  `docs/link-measure-unit-migration.md` — проверка, что величина, пришедшая с `Unit`, вообще
  допустима для пары типов + типа связи) не реализован вообще — сознательно отложено, не
  блокирует основной поток резолва единиц измерения.
