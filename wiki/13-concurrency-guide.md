# Гайд по конкурентности

## Краткая сводка

Конкурентность в `lis-client-ktor` — это ключевой аспект производительности. В проекте используются корутины Kotlin и семафор для контроля конкурентных HTTP-запросов.

## Основные принципы

### Архитектура конкурентности

```
┌─────────────────────────────────────────────────────────────┐
│                      COR DSL Pipeline                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │  Worker 1   │  │  Worker 2   │  │  Worker 3   │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          └────────────────┼────────────────┘
                         │
                ┌────────▼────────┐
                │  requestGate    │  Semaphore(10)
                │   (Semaphore)   │  по умолчанию
                └────────┬────────┘
                         │
                ┌────────▼────────┐
                │  Loodsman API   │
                └─────────────────┘
```

### Ограничитель конкурентности: requestGate

**Файл:** `client/Client.kt`

```kotlin
class Client(
    private val connection: Connection,
) {
    // Ограничивает конкурентные HTTP-запросы к Loodsman независимо от concurrency выше по стеку.
    private val requestGate = Semaphore(connection.maxConcurrentRequests)
```

**Важно:** `requestGate` — это единственная точка троттлинга. Все конкурентные запросы проходят через этот семафор.

---

## Конкурентная обработка объектов

### runObjectsMigration()

**Файл:** `migration/MigrationEngine.kt`

**Описание:** Обработка строк Excel происходит параллельно через `coroutineScope` + `async`.

```kotlin
suspend fun MigrationContext.runObjectsMigration() {
    val objectsCreated = AtomicInteger(0)
    val rowsFailed = AtomicInteger(0)

    // КОНКУРЕНТНАЯ ОБРАБОТКА
    val results = coroutineScope {
        dataRows.map { excelRow ->
            async {
                processObjectRow(RowView(headerMap, excelRow.cells), objectsCreated, rowsFailed)
            }
        }.awaitAll() // ← Ждём завершения всех корутин
    }

    // СЕКВЕНЦИАЛЬНОЕ СЛИЯНИЕ
    // Строки обрабатываются параллельно, но слияние в общие списки идёт последовательно,
    // чтобы не гонять запись в MutableList из нескольких потоков одновременно.
    val created = results.flatMap { it.identifiers }
    identifiers.addAll(created)
    materialCandidates.addAll(results.flatMap { it.materialCandidates })
    // ...
}
```

**Ключевые моменты:**

1. **Параллельная обработка:** Каждая строка обрабатывается в отдельной корутине.
2. **Ограничение через семафор:** Реальная конкурентность к Loodsman ограничена `requestGate`.
3. **Безопасное слияние:** Все изменения в общие списки происходят ПОСЛЕ `awaitAll()`.
4. **Атомарные счётчики:** Используются `AtomicInteger` для статистики.

---

## Конкурентная обработка материалов

### runMaterialsMigration()

**Файл:** `migration/MaterialsEngine.kt`

**Описание:** Обработка материалов использует мьютексы для защиты общих структур данных.

```kotlin
suspend fun MigrationContext.runMaterialsMigration() {
    val elementCache = mutableMapOf<String, Int>()
    val elementCacheMutex = Mutex()

    // КОНКУРЕНТНАЯ ОБРАБОТКА
    coroutineScope {
        materials.map { candidate ->
            async {
                processMaterialCandidate(
                    candidate,
                    elementCache,
                    elementCacheMutex
                )
            }
        }.awaitAll()
    }
}
```

**Защита общих структур:**

```kotlin
suspend fun findOrCreateElementInHierarchy(
    elementCache: MutableMap<String, Int>,
    elementCacheMutex: Mutex,
    // ...
): Int {
    // Проверяем без блокировки
    val cached = elementCache[key]
    if (cached != null) return cached

    // Блокируем для записи
    elementCacheMutex.withLock {
        // Проверяем снова (double-check locking)
        val cachedAgain = elementCache[key]
        if (cachedAgain != null) return cachedAgain

        // Создаём элемент
        val elementId = createElement(...)
        elementCache[key] = elementId
        return elementId
    }
}
```

**Ключевые моменты:**

1. **Double-check locking:** Проверяем кэш дважды для оптимизации.
2. **Мьютексы:** Защищают общие `MutableMap`.
3. **Гранулярная блокировка:** Блокируем только для записи, не для чтения.

---

## Конкурентная обработка связей

### runLinksMigration()

**Файл:** `migration/MigrationEngine.kt`

**Описание:** Создание связей происходит батчами с семафором.

```kotlin
suspend fun MigrationContext.runLinksMigration() {
    val elementsByClassifierId = identifiers.groupBy { it.classifierId }

    // Строим пары parent-child
    val linkPairs = buildList {
        dataRows.forEach { excelRow ->
            val rowView = RowView(headerMap, excelRow.cells)
            // ... собираем пары
        }
    }

    // КОНКУРЕНТНОЕ СОЗДАНИЕ СВЯЗЕЙ
    coroutineScope {
        linkPairs.map { (parent, child, quantity, unitDesignation) ->
            async {
                val unitId = unitDesignation?.let { resolveUnitId(it) }
                linkObjects(parent.loodsmanId, child.loodsmanId, quantity, unitId)
            }
        }.awaitAll()
    }
}
```

---

## Ограничения и нюансы

### Локальная работа vs HTTP запросы

```kotlin
// ЛОКАЛЬНАЯ РАБОТА (дешёвая)
val designation = rowView["Обозначение"]
val name = rowView["Наименование"]

// HTTP ЗАПРОС (дорогой, контролируется семафором)
val unitId = resolveUnitId(unitDesignation)  // ← Проходит через requestGate
```

**Важно:** Корутины большую часть времени просто ждут `permit` от семафора — это и есть единственная точка троттлинга.

### Безопасность общих структур

**Правило:** Никогда не модифицируйте `MutableList`/`MutableMap` из нескольких корутин одновременно.

**Плохо:**
```kotlin
// ❌ НЕБЕЗОПАСНО
coroutineScope {
    dataRows.map { excelRow ->
        async {
            identifiers.add(processRow(excelRow))  // RACE CONDITION!
        }
    }.awaitAll()
}
```

**Хорошо:**
```kotlin
// ✅ БЕЗОПАСНО
val results = coroutineScope {
    dataRows.map { excelRow ->
        async { processRow(excelRow) }
    }.awaitAll()
}
identifiers.addAll(results)  // Сливаем последовательно
```

### Атомарные счётчики

```kotlin
val objectsCreated = AtomicInteger(0)

coroutineScope {
    dataRows.map { excelRow ->
        async {
            // ✅ Атомарно
            objectsCreated.incrementAndGet()
        }
    }.awaitAll()
}

println("Создано объектов: ${objectsCreated.get()}")
```

---

## Таймауты

### Конфигурация HTTP-клиента

**Файл:** `client/Client.kt`

```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = 30_000    // Таймаут запроса
    connectTimeoutMillis = 10_000    // Таймаут подключения
}
```

**Важно:** При большом количестве конкурентных запросов возможны таймауты на стороне Loodsman.

---

## Мониторинг конкурентности

### Логирование статистики

```kotlin
println(
    "Объекты: строк ${dataRows.size}, " +
    "создано объектов ${objectsCreated.get()}, " +
    "ошибок ${rowsFailed.get()}"
)
```

### Настройка уровня конкурентности

**Файл:** `settings.json`

```json
{
  "connection": {
    "url": "http://loodsman-api",
    "dbName": "production",
    "maxConcurrentRequests": 10
  }
}
```

**Рекомендации:**
- По умолчанию: 10 запросов
- Для медленных API: уменьшить до 3-5
- Для быстрых API: увеличить до 20-50

---

## Известные проблемы и решения

### Проблема: Race Condition при работе с кэшем

**Проблема:**
```kotlin
val cached = elementCache[key]
if (cached != null) return cached  // ← Другая корутина может добавить значение здесь
elementCache[key] = createElement()  // ← Перезапись!
```

**Решение:** Double-check locking
```kotlin
elementCacheMutex.withLock {
    val cachedAgain = elementCache[key]
    if (cachedAgain != null) return cachedAgain
    elementCache[key] = createElement()
}
```

### Проблема: Memory pressure при больших Excel файлах

**Проблема:** При конкурентной обработке большого файла может не хватить памяти.

**Решение:** Использовать SAX-парсер (`ExcelSaxParser`) и батчинг.

---

## Лучшие практики

1. **Используйте `requestGate` для контроля конкурентности HTTP запросов.**
2. **Не модифицируйте общие `MutableList`/`MutableMap` из нескольких корутин.**
3. **Используйте `AtomicInteger` для счётчиков.**
4. **Сливайте результаты последовательно ПОСЛЕ `awaitAll()`.**
5. **Используйте мьютексы для защиты кэшей.**
6. **Мониторьте таймауты при большом количестве запросов.**
7. **Настройте `maxConcurrentRequests` под ваш API.**

---

## Связанные документы

- [[01-architecture-overview.md]] - Обзор архитектуры
- [[12-key-classes.md]] - Ключевые классы и функции
- [[14-known-issues.md]] - Известные проблемы