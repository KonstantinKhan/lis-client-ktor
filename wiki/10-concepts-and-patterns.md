# Ключевые концепции и паттерны

## COR DSL — Chain-of-Responsibility DSL

Собственный DSL для построения пайплайнов миграции.

### Основные концепции

**Worker** — атомарный шаг обработки:
```kotlin
worker {
    on { condition }           // Условие выполнения (опционально)
    handle { /* логика */ }     // Основная логика шага
    except { e -> /* обработка */ } // Обработка исключений
}
```

**Pipeline** — цепочка workers, выполняющихся последовательно:
```kotlin
pipeline<MigrationContext> {
    worker1()
    worker2()
    worker3()
}
```

**Status Machine** — ручное управление состоянием через `MigrationContext.status`:
```kotlin
on { status == Status.START }
handle { status = Status.LOGIN_SUCCESS }
```

### Особенности обработки ошибок

- **Молчаливое поглощение** — если `except` не бросает исключение, цепочка продолжается
- **Проброс исключений** — `except { throw it }` пробрасывает ошибку дальше
- **Вложенные пайплайны** — исключение долетает до родительской цепочки

## Rule Engine — Регистрируемая система правил

### Принцип работы

Все правила (условия, трансформации) регистрируются в реестрах:

```kotlin
RuleEvaluators.register("check") { rule, value ->
    value == rule.isValue
}
```

### Расширение без правки движка

Добавление нового типа правила = одна строка регистрации:

```kotlin
RuleEvaluators.register("newType") { rule, value ->
    // Новая логика
}
```

Использование в `settings.json`:
```kotlin
{
  "type": "newType",
  "column": "столбец",
  "is": "значение"
}
```

## Конкурентная обработка с семафорами

### Единственная точка контроля

`Client.requestGate: Semaphore(maxConcurrentRequests)` — глобальный семафор для всех запросов к Loodsman.

### Паттерн конкурентной обработки

```kotlin
val results = coroutineScope {
    items.map { item -> async { process(item) } }.awaitAll()
}
// Слияние результатов ПОСЛЕ awaitAll() — избегаем гонок
```

### Резолв "по уникальному значению"

Экономия HTTP-вызовов для повторяющихся значений:

```kotlin
val uniqueValues = items.map { it.value }.toSet()
val resolved = uniqueValues.map { async { resolve(it) } }.awaitAll()
val resultMap = uniqueValues.zip(resolved).toMap()
```

### Дедупликация ресурсов

Кэш + Mutex для предотвращения дублирования:

```kotlin
val elementCache = mutableMapOf<String, Element>()
val elementCacheMutex = Mutex()

suspend fun getOrCreate(key: String): Element {
    elementCacheMutex.withLock {
        elementCache.getOrPut(key) { createNew(key) }
    }
}
```

## DSL конфигурации

### Декларативный JSON-DSL

Вся бизнес-логика задаётся в `settings.json`, код — только интерпретатор:

```json
{
  "types": [
    {
      "target": "Деталь",
      "conditions": {
        "single": {
          "type": "check",
          "column": "Раздел спецификации",
          "is": "Детали"
        }
      }
    }
  ]
}
```

### Преимущества

- Новая миграция = правка JSON, не кода
- Читаемость для не-программистов
- Версионирование конфигураций

## Repository Pattern — Тонкие обёртки над API

### Структура

Каждый суб-клиент — тонкая обёртка над группой эндпоинтов:

```kotlin
suspend fun someMethod(sessionId: String, body: InputDto): OutputDto =
    requestGate.withPermit {
        client.postWithSession("Controller/endpoint", sessionId) {
            setBody(body)
        }.body()
    }
```

### Преимущества

- Единая точка троттлинга
- Автоматическая инъекция сессий
- Централизованная обработка ошибок

## Streaming Processing — Потоковая обработка Excel

### SAX-парсер для больших файлов

Использование Apache POI SAX вместо DOM:

```kotlin
ExcelSaxParser.parse(excelPath, sheetName)
    .collect { row ->
        // Обработка строки без загрузки всего файла
    }
```

### Важно: сбор в List перед конкурентной обработкой

```kotlin
// ПРАВИЛЬНО
val rows = ExcelSaxParser.parse(...).toList()
val results = coroutineScope {
    rows.map { async { process(it) } }.awaitAll()
}

// НЕПРАВИЛЬНО (вызывает deadlock)
ExcelSaxParser.parse(...)
    .map { async { process(it) } }
    .toList() // проблема: flatMapMerge блокирует чужой Java-код
```

## Двухуровневая стейт-машина

### ReplStatus — верхний уровень

Управление REPL-циклом:
```
START → AUTH → COMMAND → EXIT
```

### Status — детальный уровень

Управление шагами миграции:
```
START → LOGIN → CHECKOUT → OBJECTS_MIGRATED → LINKS_MIGRATED → MATERIALS_MIGRATED
```

## Domain-Driven Design — Доменные модели

### Чистая доменная модель

Все бизнес-сущности в `domain/` пакете:

```kotlin
data class Settings(
    val connection: Connection,
    val polynom: PolynomSettings,
    val mapping: Mapping
)
```

### Преимущества

- Типобезопасность
- Легкое тестирование
- Чёткое разделение домена и инфраструктуры

## Functional Programming — Чистые функции

### Резолверы как чистые функции

```kotlin
fun resolveQuantity(row: RowView, column: String?): Double? {
    // Нет сайд-эффектов, легко тестировать
}
```

### Преимущества

- Предсказуемость
- Тестируемость
- Компонуемость

## Error Handling Philosophy — "Пропустить + залогировать + посчитать"

### Два класса проблем

**Ожидаемые проблемы** — не останавливают миграцию:
```kotlin
try {
    process(item)
} catch (e: Exception) {
    errorCount.incrementAndGet()
    System.err.println("Ошибка при обработке $item: ${e.message}")
}
```

**Критические проблемы** — падают весь процесс:
```kotlin
if (!config.isValid) {
    throw IllegalStateException("Некорректная конфигурация")
}
```

## DTO Mapping — Двунаправленное преобразование

### Domain ↔ DTO

```kotlin
// Domain → DTO
fun LoodsmanObject.toApiDto(): NewObjectInputDto = ...

// DTO → Domain
fun SessionOutputDto.toDomain(): Session = ...
```

### Преимущества

- Изоляция от API-специфики
- Легкое тестирование
- Централизованная логика трансформации