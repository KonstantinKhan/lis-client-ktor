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
  `CheckOut`/`ObjectInfo`. Все HTTP-вызовы идут через общий `Client.requestGate: Semaphore` —
  единственная точка контроля нагрузки на Loodsman (см. "Конкурентность" ниже).
- `domain/` — классы-зеркала схемы `settings.json` (`Settings`, `Mapping`, `MappingElement`,
  `Conditions`, `Rule`, `Attribute`, `ReplaceRule`, `Connection`, `ObjectsSheet`, `LinksSheet`) +
  `MigrationContext` — общее mutable-состояние REPL-сессии (сквозной контекст для всего pipeline).
- `dsl/` — кастомный Chain-of-Responsibility DSL (см. ниже), общая инфраструктура выполнения шагов.
- `pipelines/` + `workers/` — конкретные шаги на этом DSL: `PreparePipeline` (чтение конфига),
  `LoodsmanInit` (логин → чекаут), `MigrationPipeline` (сама миграция), `LoodsmanExit` (checkin).
- `migration/` — движок правил миграции: `RuleEvaluators` (реестр интерпретаторов `Rule.type`),
  `ConditionsEvaluator` (матчинг `single`/`or`/`and`), `AttributeResolver`+`ReplaceRuleStrategies`
  (резолв значений атрибутов), `MigrationEngine.kt` (`runObjectsMigration`/`runLinksMigration` —
  собственно оркестрация чтения Excel → создания объектов → создания связей).
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
                     "childColumnIndex": 2, "linkType": "Состоит из ..." },
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
    ]
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

## Конкурентность

Единственная реальная точка троттлинга запросов к Loodsman — `Client.requestGate:
Semaphore(connection.maxConcurrentRequests)`, которым обёрнут каждый HTTP-вызов в каждом
суб-клиенте (`Login`/`EditObject`/`ConfMetaData`/`CheckOut`/`ObjectInfo`). Локальная обработка
строк Excel (матчинг условий, сборка DTO) — дешёвая, поэтому в `MigrationEngine.kt` она просто
запускается вся сразу (`rows.map { async { ... } }` внутри `coroutineScope`, `awaitAll()`), без
собственного лимита — корутины большую часть времени просто ждут permit семафора.

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
LOGIN_SUCCESS → CHECKOUT → CONNECT_CHECKOUT → OBJECTS_MIGRATED → LINKS_MIGRATED`. Это два разных
enum на разных уровнях детализации, не путать.

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
