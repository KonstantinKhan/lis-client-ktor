# Обзор архитектуры

## Что делает утилита

`lis-client-ktor` — Kotlin/Ktor CLI-инструмент для миграции данных из Excel (BOM/спецификация
изделия) в Loodsman PLM по REST API. Источник данных — xlsx-файл с двумя листами: "Объекты"
(строки → создаваемые в Loodsman объекты: папки, сборочные единицы, детали) и "Связи" (пары
"родитель-потомок" по числовому коду классификатора → связи `Состоит из ...` между уже
созданными объектами). Дополнительно, отдельным проходом, создаются материалы (через
интеграцию с ПОЛИНОМ:MDM) и группы замены материалов.

Правила миграции (какие строки Excel каким типам объектов Loodsman соответствуют, какие
атрибуты проставлять, как строить связи и материалы) заданы декларативно в `settings.json` —
это JSON-DSL, интерпретируемый общим движком, а не хардкод в коде. Идея: новую вариацию
миграции собирать правкой `settings.json`, при необходимости — регистрацией нового
обработчика правила (см. "Rule-engine" ниже), а не переписывать движок заново под каждую
задачу.

## Запуск

- `./run.sh` (Linux/WSL) / `run.cmd` (Windows) — собирают дистрибутив (`installDist`) и
  запускают его. **Не** `./gradlew run`: авторизация (`login()` в
  `src/main/kotlin/com/khan366kos/lis/client/ktor/workers/LoginWorkers.kt`) читает пароль через
  `System.console()`, а под `gradlew run` JVM форкается через pipe, консоли там нет — упадёт с
  понятной ошибкой при попытке ввода пароля.
- `settings.json` в рабочей директории — конфиг подключения + маппинг Excel→Loodsman (путь
  настраивается через `MigrationContext.configFileName`, по умолчанию `"settings.json"`).
- После логина/чекаута — интерактивный REPL: команда `migration` запускает миграцию, `exit`
  делает `checkin` и завершает сессию. Без `exit` изменения остаются в чекауте и не видны
  снаружи текущей сессии Loodsman — это штатное поведение Loodsman, не баг инструмента.

## Карта пакетов

Всё под `src/main/kotlin/com/khan366kos/lis/client/ktor/`:

- **`client/`** — Ktor HTTP-клиент к Loodsman. `Client.kt` — сборка `HttpClient` + список
  суб-клиентов. Суб-клиенты, каждый — тонкая обёртка над своей группой эндпоинтов:
  `Login.kt`, `EditObject.kt` (создание объектов/связей/атрибутов, `up-link`),
  `ConfMetaData.kt` (типы/атрибуты Loodsman), `CheckOut.kt` (чекаут/чекин),
  `ObjectInfo.kt` (чтение атрибутов объекта, `get-linked-fast`), `Measure.kt` (единицы
  измерения), `ObjectConfiguration.kt` (группы/варианты замены). `Helpers.kt` —
  `getWithSession`/`postWithSession`, общая инъекция заголовка сессии.
- **`domain/`** — классы-зеркала схемы `settings.json` (`Settings`, `Mapping`,
  `MappingElement`, `Conditions`, `Rule`, `Attribute`, `ReplaceRule`, `Connection`,
  `ObjectsSheet`, `LinksSheet`, `MaterialsSettings`, `BomMaterialsSettings` и т.д.) плюс
  сквозной `MigrationContext` — общее mutable-состояние всей REPL-сессии и всего пайплайна
  (сессия, клиенты, накопленные списки объектов/кандидатов на материалы и т.п.).
- **`dsl/`** — кастомный Chain-of-Responsibility DSL (см. раздел ниже).
- **`pipelines/` + `workers/`** — конкретные шаги, собранные на этом DSL:
  `PreparePipeline` (чтение `settings.json`), `LoodsmanInit` (логин → чекаут),
  `MigrationPipeline` (сама миграция), `LoodsmanExit` (checkin). `workers/*.kt` — extension-
  функции `ICorChainDsl<MigrationContext>.xxx() = worker { ... }`, по одной группе на файл
  (`ConfigWorkers.kt`, `LoginWorkers.kt`, `CheckoutWorkers.kt`, `CreateWorkers.kt`,
  `MigrationWorkers.kt`).
- **`migration/`** — собственно движок правил и вся бизнес-логика миграции:
  - `RuleEvaluators` (`RuleEvaluator.kt`) — реестр интерпретаторов `Rule.type`.
  - `ConditionsEvaluator` — матчинг `single`/`or`/`and`.
  - `AttributeResolver` + `ReplaceRuleStrategies` — резолв значений атрибутов объекта.
  - `RowView`/`SheetHeaders` — доступ к ячейке строки Excel по имени столбца.
  - `MigrationEngine.kt` — `runObjectsMigration`/`runLinksMigration`: чтение Excel →
    создание объектов → создание связей (включая резолв единиц измерения и сбор кандидатов
    на материалы по КД для DS-объектов).
  - `MaterialsEngine.kt` — `runMaterialsMigration` (сортаментные материалы + заменители через
    ПОЛИНОМ), `runBomMaterialsMigration` (материалы по КД для DS-объектов, только по коду
    классификатора), группы замены материала.
  - `LinkQuantityResolver.kt`/`LinkUnitResolver.kt` — чистые функции резолва
    количества/единицы измерения связи из строки Excel.
- **`excel/`** — `ExcelSaxParser`: потоковый (SAX, не DOM) парсер xlsx через Apache POI,
  отдаёт `Flow<ExcelRow>` с опциональным фильтром по имени листа. SAX выбран ради памяти
  (файлы могут быть большими), но реальная конкурентная обработка строк требует сначала
  полностью собрать результат в `List` — см. `[[03-external-api-quirks.md]], антипаттерн
  `flatMapMerge`.
- **`repl/`** — интерактивная консоль `ReplConsole`, команды реализуют `ICommand`
  (`suspend fun execute`), диспетчеризуются по первому слову введённой строки в
  `repl/commands/` + `DefaultCommands.kt`. Состояние REPL — `ReplStatus`.
- **`mapping/`** — тонкие Dto↔Domain конвертеры (`toApiDto`, `toDomain`) плюс сам класс
  `Mapping` (корень схемы `settings.json.mapping`).
- **`logic/`** — `Validator`: проверяет, что все `target` из `mapping.types[]` и
  `materials.materialTarget` существуют как реальные типы в Loodsman — гейт перед стартом
  миграции, чтобы не упасть на середине большого файла.
- **`loodsman/api/dto/`** — сериализуемые (`kotlinx.serialization`) DTO под конкретные
  эндпоинты Loodsman.
- **`polynom/client/` + `polynom/api/dto/`** — аналогичная пара клиент+DTO для ПОЛИНОМ:MDM
  (поиск по классификатору, работа со справочниками/группами/элементами, авторизация).

## COR DSL (`dsl/`)

```kotlin
object SomePipeline : ICorExec<MigrationContext> by pipeline<MigrationContext>({
    worker {
        on { status == Status.SOME_STATE }        // T.() -> Boolean, дефолт — всегда true
        handle { /* suspend T.() -> Unit */ }      // основная логика шага
        except { e -> /* T.(Throwable) -> Unit */ } // дефолт — молча глотает исключение!
    }
}).build()
```

- `ICorWorker.execute()` (`dsl/core/ICorWorker.kt`): если `on()` истинно — выполняется
  `handle()`; исключение из `handle()` ловится и передаётся в `except()`. **Если `except()` не
  вызывает `throw` явно — исключение молча гасится**, chain идёт дальше как ни в чём не бывало.
  При добавлении worker'а с реальной вероятностью падения — либо явный `except { throw it }`,
  либо осознанное решение, что тихое продолжение — ожидаемое поведение (например, для
  read-only шагов).
- Исключение, переброшенное из `except{}` worker'а внутри вложенного `pipeline{}`, долетает до
  **родительского** chain и там либо гасится (если у родителя своего `except{}` нет — дефолт
  молчит), либо тоже перебрасывается дальше. У `PreparePipeline`/`LoodsmanInit`/
  `MigrationPipeline` верхнеуровневого `except{}` нет — падение проброшенного исключения
  долетает до вызывающего кода (`ReplConsole`/команда `migration`).
- `worker{}`/`pipeline{}` — билдеры, добавляют узел в `ICorChainDsl<T>`. `CorChain` выполняет
  своих детей строго последовательно (`execs.forEach { it.execute(context) }`).
- Активация каждого шага идёт через `MigrationContext.status: Status` — простая ручная
  стейт-машина: worker меняет `status` на выходе, следующий worker гейтится на новое значение
  (см. раздел про Status ниже).
- **`parallel{}` в DSL существует синтаксически, но не подключён к реальному `CorParallel`**
  (`dsl/dsl.kt`) — билдер `parallel` фактически собирает обычный последовательный
  `CorWorkerDsl`. Реальная конкурентность в проекте реализована на уровне `kotlinx.coroutines`
  (`async`/`awaitAll`) прямо внутри `migration/*.kt`, не через DSL.

## Две стейт-машины

Не путать: это разные enum на разных уровнях детализации.

1. **`ReplStatus`** (`repl/ReplStatus.kt`) — верхнеуровневый цикл `ReplConsole`:
   `START` → `PreparePipeline.execute()` (чтение `settings.json`) → `AUTH` →
   `LoodsmanInit.execute()` (логин → `allTypes()` → `createRoot()` → `checkout()` →
   `connectCheckout()`) → `COMMAND` (REPL принимает ввод, диспетчеризует в `ICommand`).
2. **`Status`** (`domain/Status.kt`) — детальный, гейтит шаги внутри `LoodsmanInit`/
   `MigrationPipeline`:
   `START → EXIST_CONFIG/NOT_CONFIG → LOGIN → LOGIN_SUCCESS → CHECKOUT → CONNECT_CHECKOUT →
   OBJECTS_MIGRATED → LINKS_MIGRATED → MATERIALS_MIGRATED → BOM_MATERIALS_MIGRATED`.

## Пайплайн миграции целиком

```
PreparePipeline        — читает settings.json, поднимает Client/PolynomClient
LoodsmanInit            — логин, allTypes(), createRoot(), checkout(), connectCheckout()
MigrationPipeline:
  validateMapping()     — logic/Validator: все target-типы существуют в Loodsman
  migrateObjects()      — runObjectsMigration(): лист "Объекты" → объекты Loodsman
  migrateLinks()         — runLinksMigration(): лист "Связи" → связи "Состоит из ..." +
                            резолв единиц измерения + сбор кандидатов на материалы по КД
  migrateMaterials()     — runMaterialsMigration(): сортаментные материалы + заменители
                            через ПОЛИНОМ, группы замены материала
  migrateBomMaterials()  — runBomMaterialsMigration(): материалы по КД для DS-объектов
                            (только по коду классификатора, без ПОЛИНОМ-фолбэка)
LoodsmanExit            — CheckOut/check-in-2, изменения становятся видны снаружи сессии
```

Каждый шаг — `worker{}` в `workers/MigrationWorkers.kt`, вызов подключён в
`pipelines/MigrationPipeline.kt`. Подробности каждого шага — [[04-business-logic.md]].

## Rule-engine: как устроено расширение без правки движка

Схема `settings.json` подробно описана в `[[05-settings-reference.md]]; здесь — принцип.

- `Conditions.single/or/and` — три независимые группы условий на строку Excel. Каждая
  непустая группа должна пройти (AND между группами); пустая/неприменимая группа (`{}` или
  `[]`) не блокирует — считается `true`. Проверка "применимо ли `single`" идёт по
  `single.column != null`, а не по `single != null` (частая ловушка — `single: {}` в JSON
  всегда объект, не `null`).
- `Rule.type` (`"check"` — точное совпадение значения, `"parse"`/`"notEndsWith"` и т.д.)
  резолвится через реестр `RuleEvaluators` — новый тип условия добавляется одной строкой
  `RuleEvaluators.register("новый_тип") { rule, value -> ... }`, без правки
  `ConditionsEvaluator`.
- `Attribute.replace` — аналогичный реестр `ReplaceRuleStrategies` для преобразования
  сырого значения атрибута перед записью в Loodsman.
- Один и тот же принцип "реестр + регистрация" используется и там, где не видно сразу:
  например, `ConditionsEvaluator.matches` переиспользуется для проверки строк совершенно
  разных листов (`mapping.types[].conditions` — лист "Объекты"; `mapping.bomMaterials.*` —
  тоже лист "Объекты", но по другому смыслу строки) — `RowView` абстрагирует конкретный лист.

Практический вывод: типовая новая миграция = правка `settings.json`. Код трогается только
если нужен принципиально новый тип условия/replace-стратегии, новый Loodsman-эндпоинт или
новый шаг пайплайна — все три случая описаны в CLAUDE.md, раздел "Как добавить".
