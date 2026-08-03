# Ключевые классы и функции

## Краткая сводка

Детальное описание основных классов и функций проекта с сигнатурами, параметрами и исключениями.

## Миграция

### MigrationContext

**Файл:** `domain/MigrationContext.kt`

**Описание:** Сквозной mutable-состояние всей REPL-сессии и пайплайна миграции.

```kotlin
data class MigrationContext(
    // Путь к конфигу (по умолчанию "settings.json")
    var configFileName: String = "settings.json",
    // Общие настройки
    var settings: Settings = Settings(),
    // Сессия Loodsman
    var loodsmanSession: String = "",
    // Сессия Полином
    var polynomSession: String = "",
    // Клиенты API
    var loodsmanClient: Client? = null,
    var polynomClient: PolynomClient? = null,
    // Кэш типов Loodsman
    var loodsmanTypes: List<LoodsmanType> = emptyList(),
    // Список созданных идентификаторов
    val identifiers: MutableList<Identifier> = mutableListOf(),
    // Кандидаты на материалы
    val materialCandidates: MutableList<MaterialCandidate> = mutableListOf(),
    // Кандидаты на замену материалов
    val materialSubstituteCandidates: MutableList<MaterialCandidate> = mutableListOf(),
    // Спецификации материалов
    val bomMaterialSpecClassifierIds: MutableSet<Int> = mutableSetOf(),
    // Строки раздела Материалы
    val bomMaterialRowClassifierIds: MutableSet<Int> = mutableSetOf(),
    // Статус миграции
    var status: Status = Status.INIT
)
```

**Использование:** Передаётся между воркерами COR DSL как состояние.

**Исключения:** Нет (по умолчанию).

---

### Status

**Файл:** `domain/Status.kt`

**Описание:** Перечисление состояний миграции.

```kotlin
enum class Status {
    INIT,                     // Начальное состояние
    SETTINGS_LOADED,          // Настройки загружены
    CONNECT_CHECKOUT,         // Подключены и заблокированы чекаут
    OBJECTS_MIGRATED,         // Объекты созданы
    LINKS_MIGRATED,           // Связи созданы
    MATERIALS_MIGRATED,       // Материалы мигрированы
    BOM_MATERIALS_MIGRATED,   // Материалы по КД мигрированы
    ERROR                     // Ошибка
}
```

---

### runObjectsMigration()

**Файл:** `migration/MigrationEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runObjectsMigration()
```

**Описание:** Основная функция миграции объектов из Excel. Создаёт объекты в Loodsman на основе данных из листа "Объекты".

**Использует:**
- `ExcelSaxParser` для парсинга Excel
- `processObjectRow` для обработки каждой строки
- `resolvePolynomBackedObjects` для обработки полином-объектов
- `requestGate` для ограничения конкурентности

**Исключения:**
- `IllegalStateException` — если не найдена строка заголовков
- Исключения из `processObjectRow` — при создании объектов

**Пример использования:**
```kotlin
context.runObjectsMigration()
println("Создано объектов: ${context.identifiers.size}")
```

---

### runLinksMigration()

**Файл:** `migration/MigrationEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runLinksMigration()
```

**Описание:** Создаёт связи между объектами на основе данных из листа "Связи". Поддерживает:
- Простые связи
- Связи с количеством
- Связи с единицами измерения
- Аналоговые группы

**Использует:**
- `ExcelSaxParser` для парсинга Excel
- `resolveUnitId` для резолва единиц измерения
- `linkObjects` для создания связей
- Атомарные счётчики для статистики

**Исключения:**
- `IllegalStateException` — если не найдена строка заголовков
- Исключения при создании связей

**Пример использования:**
```kotlin
context.runLinksMigration()
```

---

### runMaterialsMigration()

**Файл:** `migration/MaterialsEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runMaterialsMigration()
```

**Описание:** Миграция материалов через ПОЛИНОМ:MDM. Создаёт материалы и связи материалов с деталями, а также группы замены.

**Использует:**
- `PolynomClient` для поиска материалов
- `MaterialCandidate` для накопления кандидатов
- `processMaterialCandidates` для обработки кандидатов
- `createSubstituteChangeGroups` для групп замены

**Исключения:**
- `ResponseException` — при ошибках API Полинома
- Исключения при создании материалов

**Пример использования:**
```kotlin
context.runMaterialsMigration()
println("Миграция материалов ПОЛИНОМ завершена")
```

---

### runBomMaterialsMigration()

**Файл:** `migration/MaterialsEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runBomMaterialsMigration()
```

**Описание:** Миграция материалов по конструкторской документации (DS-объекты). Создаёт материалы только по коду классификатора.

**Использует:**
- `PolynomClient` для поиска материалов
- `resolveBomMaterialByClassifierCode` для резолва материалов
- `linkBomMaterialToParent` для связи материалов с родительскими объектами

**Исключения:**
- `ResponseException` — при ошибках API Полинома
- Исключения при создании материалов

**Пример использования:**
```kotlin
context.runBomMaterialsMigration()
println("Миграция материалов по КД (DS) завершена")
```

---

### runBlanksMigration()

**Файл:** `migration/BlanksEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runBlanksMigration()
```

**Описание:** Независимый поток от `runMaterialsMigration()` — на каждого кандидата
(`MigrationContext.blankCandidates`, собраны в `runObjectsMigration()`) создаёт "Заготовку"
(`EditObject/new-object`), реверсивную связь деталь↔заготовка (`blanks.linkType`, субъект —
заготовка), резолвит/связывает "Материал основной" (переиспользует
`resolveBomMaterialByClassifierCode` из `MaterialsEngine.kt` с целевым типом
`blanks.materialTarget`), и, если норма расхода настроена, ставит её на связь заготовка→материал
через `EditObject/up-link-attr-values` (`EditObject.setLinkAttrValues`).

**Использует:**
- `resolveBomMaterialByClassifierCode` (`MaterialsEngine.kt`) — поиск материала без сравнения
  обозначения, без фолбэка на создание
- `resolveUnitId` (`MigrationEngine.kt`) — резолв единицы нормы расхода через
  `Measure/units-by-designation`
- `EditObject.setLinkAttrValues` — простановка атрибута "Норма расхода" на связь

**Исключения:**
- `ResponseException` — при ошибках API Loodsman/ПОЛИНОМ (лог статуса+тела, проброс дальше)

**Пример использования:**
```kotlin
context.runBlanksMigration()
```

## Доменные модели

### Settings

**Файл:** `domain/Settings.kt`

**Описание:** Корневая схема конфигурации `settings.json`.

```kotlin
data class Settings(
    val connection: Connection,
    val mapping: Mapping,
    val materials: MaterialsSettings? = null,
    val bomMaterials: BomMaterialsSettings? = null,
    val analogGroups: AnalogGroupsSettings? = null
)
```

---

### Connection

**Файл:** `domain/Connection.kt`

**Описание:** Параметры подключения к API.

```kotlin
data class Connection(
    val url: String,                    // Базовый URL API
    val dbName: String,                 // Имя базы данных
    val maxConcurrentRequests: Int = 10 // Макс. конкурентных запросов
)
```

---

### Mapping

**Файл:** `domain/Mapping.kt`

**Описание:** Конфигурация маппинга Excel → Loodsman.

```kotlin
data class Mapping(
    val objectsSheet: ObjectsSheet,
    val linksSheet: LinksSheet,
    val types: List<MappingElement>,
    val materials: MaterialsHierarchy? = null
)
```

---

### MappingElement

**Файл:** `domain/MappingElement.kt`

**Описание:** Правило маппинга строки Excel в тип объекта Loodsman.

```kotlin
data class MappingElement(
    val type: String,                   // Тип объекта в Loodsman
    val conditions: Conditions,         // Условия срабатывания
    val attributes: List<Attribute>,    // Атрибуты для установки
    val resolveViaPolynom: Boolean = false, // Резолвить через Полином
    val folder: Boolean = false,        // Это папка
    val createInFolder: Boolean = false // Создавать в папке
)
```

---

### Conditions

**Файл:** `domain/Conditions.kt`

**Описание:** Условия для правила маппинга.

```kotlin
data class Conditions(
    val single: Rule? = null,           // Единое правило
    val or: List<Rule>? = null,         // Логическое ИЛИ
    val and: List<Rule>? = null         // Логическое И
)
```

---

### Rule

**Файл:** `domain/Rule.kt`

**Описание:** Правило для условия.

```kotlin
data class Rule(
    val type: String? = null,           // Тип правила (см. RuleEvaluator)
    val column: String? = null,         // Колонка Excel
    @SerialName("is")
    val isValue: String? = null         // Значение для сравнения
)
```

---

### Attribute

**Файл:** `domain/Attribute.kt`

**Описание:** Атрибут объекта для установки.

```kotlin
data class Attribute(
    val id: Int?,                       // ID атрибута
    val name: String?,                  // Имя атрибута
    val column: String?,                // Колонка Excel для значения
    val replaceRule: ReplaceRule? = null // Правило замены значения
)
```

---

### ReplaceRule

**Файл:** `domain/ReplaceRule.kt`

**Описание:** Правило замены значения атрибута.

```kotlin
data class ReplaceRule(
    val strategy: String?,              // Стратегия замены
    val source: String?                 // Источник для замены
)
```

---

### MaterialCandidate

**Файл:** `domain/MaterialCandidate.kt`

**Описание:** Кандидат на создание материала.

```kotlin
data class MaterialCandidate(
    val designation: String,            // Обозначение материала
    val name: String,                   // Имя материала
    val classifierId: Int?,             // ID классификатора
    val quantity: Double?,              // Количество
    val unitDesignation: String?,       // Единица измерения
    val isSubstitute: Boolean,          // Это заменитель
    val detailIds: List<Int>            // ID деталей для связи
)
```

---

### BomMaterialCandidate

**Файл:** `domain/BomMaterialCandidate.kt`

**Описание:** Кандидат на создание материала по КД.

```kotlin
data class BomMaterialCandidate(
    val classifierId: Int,              // ID классификатора
    val parentIds: List<Int>,           // ID родительских объектов
    val quantity: Double?,              // Количество
    val unitDesignation: String?,       // Единица измерения
)
```

---

### BlankCandidate

**Файл:** `domain/BlankCandidate.kt`

**Описание:** Кандидат на создание "Заготовки" + "Материала основного" (`mapping.blanks`).
Собирается только когда `materials.classifierCodeColumn` на строке непустой — в отличие от
`MaterialCandidate`, `classifierCode` здесь не nullable.

```kotlin
data class BlankCandidate(
    val detailLoodsmanId: Int,
    val detailDesignation: String,
    val classifierCode: String,
    val rate: Double? = null,             // норма расхода, null = не проставлять
    val rateUnitDesignation: String? = null,
)
```

---

### AnalogGroupCandidate

**Файл:** `domain/AnalogGroupCandidate.kt`

**Описание:** Кандидат на создание аналоговой группы.

```kotlin
data class AnalogGroupCandidate(
    val groupNumber: Int,               // Номер группы
    val variantNumber: Int,             // Номер варианта
    val isBasic: Boolean,               // Базовый вариант
    val parentIds: List<Int>            // ID родительских объектов
)
```

---

### Identifier

**Файл:** `domain/Identifier.kt`

**Описание:** Идентификатор созданного объекта.

```kotlin
data class Identifier(
    val classifierId: Int?,             // ID классификатора (из Excel)
    val loodsmanId: Int,                // ID в Loodsman
    val isFolder: Boolean,              // Это папка
    val children: MutableList<Identifier> = mutableListOf() // Потомки
)
```

## COR DSL

### pipeline()

**Файл:** `dsl/dsl.kt`

**Сигнатура:**
```kotlin
fun <T> pipeline(block: CorChainDsl<T>.() -> Unit): CorChain<T>
```

**Описание:** Создаёт цепочку обработки (pipeline) с типом контекста T.

**Пример:**
```kotlin
val migrationPipeline = pipeline<MigrationContext> {
    worker { /* воркер 1 */ }
    worker { /* воркер 2 */ }
}
```

---

### worker()

**Файл:** `dsl/dsl.kt`

**Сигнатура:**
```kotlin
fun <T> ICorChainDsl<T>.worker(block: CorWorkerDsl<T>.() -> Unit)
```

**Описание:** Добавляет воркер в цепочку.

**Параметры:**
- `on: T.() -> Boolean` — условие запуска (по умолчанию true)
- `handle: suspend T.() -> Unit` — основная логика
- `except: T.(Throwable) -> Unit` — обработка исключений

**Пример:**
```kotlin
pipeline<MigrationContext> {
    worker {
        on { status == Status.CONNECT_CHECKOUT }
        handle {
            runObjectsMigration()
            status = Status.OBJECTS_MIGRATED
        }
        except { e ->
            System.err.println("Ошибка: ${e.message}")
            throw e
        }
    }
}
```

---

### parallel()

**Файл:** `dsl/dsl.kt`

**Сигнатура:**
```kotlin
fun <T> ICorChainDsl<T>.parallel(block: CorWorkerDsl<T>.() -> Unit)
```

**Описание:** Добавляет воркер, который выполняется параллельно.

**Важно:** Реальная конкурентность ограничивается `requestGate` в `Client`.

## Rule Engine

### RuleEvaluator

**Файл:** `migration/RuleEvaluator.kt`

**Описание:** Интерфейс для оценки правил.

```kotlin
interface RuleEvaluator {
    fun evaluate(rule: Rule, context: Any): Boolean
}
```

---

### RuleEvaluators

**Файл:** `migration/RuleEvaluator.kt`

**Описание:** Реестр оценщиков правил.

```kotlin
object RuleEvaluators {
    private val evaluators: Map<String, RuleEvaluator> = mapOf(
        // Стратегии: "equals", "contains", "startsWith", "endsWith", "regex"
    )

    fun evaluate(rule: Rule, context: Any): Boolean {
        val evaluator = evaluators[rule.type] ?: error("Unknown rule type: ${rule.type}")
        return evaluator.evaluate(rule, context)
    }
}
```

---

### ConditionsEvaluator

**Файл:** `migration/ConditionsEvaluator.kt`

**Сигнатура:**
```kotlin
class ConditionsEvaluator(private val context: Any) {
    fun evaluate(conditions: Conditions): Boolean
}
```

**Описание:** Оценивает условия (single, or, and).

---

### AttributeResolver

**Файл:** `migration/AttributeResolver.kt`

**Сигнатура:**
```kotlin
suspend fun AttributeResolver.resolveAttributes(
    context: MigrationContext,
    attributes: List<Attribute>,
    rowView: RowView
): List<UpAttrValuesByIdsInputDto>
```

**Описание:** Резолвит значения атрибутов для объекта.

**Использует:**
- `ReplaceRuleStrategies` для применения правил замены
- `RowView` для доступа к значениям из Excel

---

## Utils

### RowView

**Файл:** `migration/RowView.kt`

**Описание:** Обёртка над строкой Excel для доступа к ячейкам по имени колонки.

```kotlin
class RowView(
    private val headers: SheetHeaders,
    private val cells: List<String>
) {
    operator fun get(columnName: String): String?
}
```

**Пример:**
```kotlin
val designation = rowView["Обозначение"]
val name = rowView["Наименование"]
```

---

### SheetHeaders

**Файл:** `migration/RowView.kt`

**Описание:** Карта имён колонок в индексы.

```kotlin
class SheetHeaders(
    private val headers: Map<String, Int>
) {
    companion object {
        fun build(cells: List<String>): SheetHeaders
    }
}
```

---

### LinkQuantityResolver

**Файл:** `migration/LinkQuantityResolver.kt`

**Сигнатура:**
```kotlin
fun resolveLinkQuantity(rowView: RowView, config: LinksSheet): Double?
```

**Описание:** Резолвит количество связи из строки Excel.

**Возвращает:** Double или null, если колонка не настроена или значение не парсится.

---

### LinkUnitResolver

**Файл:** `migration/LinkUnitResolver.kt`

**Сигнатура:**
```kotlin
suspend fun resolveLinkUnitDesignation(
    rowView: RowView,
    config: LinksSheet,
    loodsmanClient: Client
): String?
```

**Описание:** Резолвит единицу измерения связи.

**Возвращает:** Обозначение единицы или null.

---

## Исключения

### Типичные исключения

| Исключение | Где возникает | Причина |
|-----------|--------------|---------|
| `IllegalStateException` | `MigrationEngine` | Не найдена строка заголовков |
| `ResponseException` | API вызовы | Ошибка HTTP запроса |
| `SerializationException` | DTO | Ошибка десериализации |
| `RuntimeException` | Валидация | Некорректная конфигурация |

## Связанные документы

- [[01-architecture-overview.md]] - Обзор архитектуры
- [[11-api-reference.md]] - Справочник по API
- [[13-concurrency-guide.md]] - Гайд по конкурентности
- [[15-cor-dsl-guide.md]] - Гайд по COR DSL и Rule Engine