# Основные компоненты и их назначение

## Пакеты проекта

### `client/` — HTTP-клиенты к Loodsman

Тонкие обёртки над группами эндпоинтов Loodsman PLM API:

- **`Client.kt`** — сборка `HttpClient` и агрегация всех суб-клиентов
- **`Login.kt`** — авторизация в системе
- **`EditObject.kt`** — создание объектов/связей/атрибутов, операции `up-link`
- **`ConfMetaData.kt`** — работа с типами и атрибутами Loodsman
- **`CheckOut.kt`** — операции checkout/check-in
- **`ObjectInfo.kt`** — чтение атрибутов объекта, получение связанных объектов
- **`Measure.kt`** — работа с единицами измерения
- **`ObjectConfiguration.kt`** — управление группами и вариантами замены
- **`Helpers.kt`** — общие хелперы для инъекции сессионных заголовков

### `polynom/client/` — HTTP-клиенты к ПОЛИНОМ:MDM

- **`PolynomClient.kt`** — сборка клиента для ПОЛИНОМ
- **`PolynomLogin.kt`** — авторизация (access/refresh токены)
- **`PolynomSearch.kt`** — поиск по свойствам и классификаторам
- **`PolynomClassification.kt`** — работа с классификацией и группами
- **`PolynomConcepts.kt`** — работа с понятиями и справочниками
- **`PolynomHelpers.kt`** — хелперы для bearer-токенов

### `domain/` — доменные модели

- **`Settings.kt`** — корневая схема конфигурации `settings.json`
- **`Mapping.kt`** — настройки маппинга Excel → Loodsman
- **`Rule.kt`**, **`Conditions.kt`** — правила и условия для строк Excel
- **`Attribute.kt`**, **`ReplaceRule.kt`** — атрибуты и правила трансформации
- **`Connection.kt`**, **`MaterialsSettings.kt`** — настройки подключений и материалов
- **`BomMaterialsSettings.kt`** — настройки материалов по КД для DS-объектов
- **`MigrationContext.kt`** — сквозной контекст всей миграции (сессии, клиенты, состояния)
- **`LoodsmanObject.kt`**, **`LoodsmanType.kt`**, **`LoodsmanState.kt`** — сущности Loodsman

### `dsl/` — Chain-of-Responsibility DSL

Собственный DSL для сборки пайплайнов:

- **`core/`** — интерфейсы и базовые классы DSL
  - `ICorExec`, `ICorWorker`, `ICorChainDsl` — базовые контракты
  - `CorWorker`, `CorChain`, `CorParallel` — реализации узлов
- **`builders/`** — билдеры для конструирования пайплайнов
  - `CorWorkerDsl`, `CorChainDsl` — DSL-строители
- **`dsl.kt`** — публичные функции DSL (`worker`, `pipeline`, `parallel`)

### `pipelines/` — готовые пайплайны

Собранные на COR DSL:

- **`PreparePipeline`** — чтение и валидация `settings.json`
- **`LoodsmanInit`** — логин, чекаут, создание root-объекта
- **`MigrationPipeline`** — основной процесс миграции данных
- **`PolynomInit`** — инициализация подключения к ПОЛИНОМ
- **`LoodsmanExit`** — завершение работы (check-in)

### `workers/` — шаги пайплайнов

Extension-функции для `ICorChainDsl<MigrationContext>`:

- **`ConfigWorkers.kt`** — чтение конфигурации
- **`LoginWorkers.kt`** — авторизация
- **`CheckoutWorkers.kt`** — операции чекаута/чекина
- **`CreateWorkers.kt`** — создание root-объекта
- **`MigrationWorkers.kt`** — основные шаги миграции
- **`InfoWorkers.kt`** — информационные операции
- **`LoodsmanTypeWorkers.kt`** — работа с типами Loodsman
- **`PolynomLoginWorkers.kt`** — авторизация в ПОЛИНОМ

### `migration/` — бизнес-логика миграции

- **`MigrationEngine.kt`** — основной движок миграции (объекты, связи)
- **`MaterialsEngine.kt`** — движок материалов (сортаментные, заменители, группы замены)
- **`RuleEvaluator.kt`** — реестр интерпретаторов правил
- **`ConditionsEvaluator.kt`** — матчинг условий (`single`/`or`/`and`)
- **`AttributeResolver.kt`** — резолв значений атрибутов
- **`ConditionsEvaluator.kt`** — оценка условий
- **`LinkQuantityResolver.kt`**, **`LinkUnitResolver.kt`** — резолверы связей
- **`RowView.kt`** — абстракция над строкой Excel

### `excel/` — работа с Excel

- **`ExcelSaxParser.kt`** — потоковый SAX-парсер xlsx через Apache POI
- **`RowSheetContentsHandler.kt`** — обработчик содержимого строк
- **`ExcelParseException.kt`** — исключения парсинга

### `repl/` — интерактивная консоль

- **`ReplConsole.kt`** — основной REPL-цикл
- **`IRepl.kt`** — интерфейс командной строки
- **`ReplStatus.kt`** — статусы REPL
- **`commands/`** — реализации команд
  - `ICommand` — интерфейс команды
  - `ExitCommand`, `HelloCommand`, `MigrationCommand`
- **`DefaultCommands.kt`** — стандартные команды

### `logic/` — валидация

- **`Validator.kt`** — проверка целостности конфигурации

### `mapping/` — преобразования моделей

- **`Domain2LoodsmanDto.kt`** — доменные модели → DTO Loodsman
- **`LoodsmanDto2Domain.kt`** — DTO Loodsman → доменные модели
- **`Mapping.kt`** — класс-корень схемы маппинга

### `loodsman/api/dto/` — DTO Loodsman

Сериализуемые DTO для всех эндпоинтов Loodsman API

### `polynom/api/dto/` — DTO ПОЛИНОМ

Сериализуемые DTO для всех эндпоинтов ПОЛИНОМ API