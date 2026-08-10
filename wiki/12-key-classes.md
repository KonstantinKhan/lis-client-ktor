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
    START,
    EXIST_CONFIG,
    NOT_CONFIG,
    LOGIN,
    LOGIN_SUCCESS,
    API_ERROR,
    EMPTY_SESSION,
    CHECKOUT,
    CONNECT_CHECKOUT,
    NOT_ENOUGH_RIGHTS,
    OBJECTS_MIGRATED,
    LINKS_MIGRATED,
    MATERIALS_MIGRATED,
    BOM_MATERIALS_MIGRATED,
    BLANKS_MIGRATED,           // Поток D (заготовки) завершён
    CASTING_BLANKS_MIGRATED,   // Поток E (литейные заготовки: связи) завершён
    AUX_MATERIALS_MIGRATED     // Поток F (вспомогательные материалы для Детали) завершён
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

---

### runCastingBlanksLinksMigration()

**Файл:** `migration/CastingBlanksEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runCastingBlanksLinksMigration()
```

**Описание:** Поток E ("Литейные заготовки: связи", `mapping.castingBlanks`) — независимый от
остальных потоков лист Excel, читается напрямую (не через `processObjectRow`, как поток D). Родитель
резолвится по `classifierId` среди `identifiers` (как child/parent листа "Связи"). На первое
встреченное значение родителя в группе создаётся "Комплект вспомогательных материалов"
(`createMaterialsKit`, реверсивная связь на родителя). Для каждой строки материал резолвится в
ПОЛИНОМ (`resolveBomMaterialByClassifierCode`) с веткой по "Тип материала"
(Образец/Основной/Вспомогательный → пропуск/`materialTarget`/`auxMaterialTarget`), линкуется к
комплекту прямой связью, на связь ставятся атрибуты "Норма расхода" и "Цех-потребитель"
(`EditObject/up-link-attr-values`, ОБА — атрибуты связи, не объекта).

**Использует:**
- `createMaterialsKit` (`MaterialsKitEngine.kt`) — создание комплекта + реверсивная связь
- `resolveBomMaterialByClassifierCode`/`resolvePropertyDefinitionByAbsoluteCode`/
  `resolveSearchScope` (`MaterialsEngine.kt`)
- `resolveUnitId` (`MigrationEngine.kt`) с тай-брейком `"Масса"` (см. [[03-external-api-quirks.md]])
- `resolveLinkUnitDesignation` (`LinkUnitResolver.kt`)

**Исключения:**
- `ResponseException` — при ошибках API Loodsman/ПОЛИНОМ (лог статуса+тела, проброс дальше)

**Пример использования:**
```kotlin
context.runCastingBlanksLinksMigration()
```

---

### runAuxMaterialsMigration()

**Файл:** `migration/AuxMaterialsEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.runAuxMaterialsMigration()
```

**Описание:** Поток F ("Вспомогательные материалы для Детали", `mapping.auxMaterials`) —
архитектурно идентичен потоку E (`runCastingBlanksLinksMigration()`), тот же
`createMaterialsKit`/`resolveBomMaterialByClassifierCode`, но БЕЗ ветвления по типу материала
(всегда `auxMaterials.materialTarget`) и с колонками родителя/материала, резолвимыми по имени
(`RowView.value`), а не по индексу столбца.

**Использует:** те же функции, что `runCastingBlanksLinksMigration()`.

**Исключения:**
- `ResponseException` — при ошибках API Loodsman/ПОЛИНОМ (лог статуса+тела, проброс дальше)

**Пример использования:**
```kotlin
context.runAuxMaterialsMigration()
```

---

### createMaterialsKit()

**Файл:** `migration/MaterialsKitEngine.kt`

**Сигнатура:**
```kotlin
suspend fun MigrationContext.createMaterialsKit(
    parent: Identifier,
    setTarget: String,
    setState: String,
    setLinkType: String,
    setsCreated: AtomicInteger,
    setLinksCreated: AtomicInteger,
    setFailures: AtomicInteger,
): Int?
```

**Описание:** Общий приём "создать Комплект вспомогательных материалов на родителя + реверсивная
связь на родителя", вынесенный из `CastingBlanksEngine.kt` в отдельный файл, чтобы переиспользовать
в `AuxMaterialsEngine.kt` (потоки E и F создают один и тот же тип объекта Loodsman тем же
способом — разница только в источнике строк Excel и в резолве материалов). Параметризован голыми
строками (`setTarget`/`setState`/`setLinkType`), а не общим объектом настроек — единственная общая
часть между `CastingBlanksSettings`/`AuxMaterialsSettings`. `keyAttribute` комплекта =
`parent.designation`, с фолбэком на `parent.classifierId.toString()`, если designation пуст.

**Пример использования:**
```kotlin
val kitId = createMaterialsKit(parent, castingBlanks.setTarget, castingBlanks.setState,
    castingBlanks.setLinkType, setsCreated, setLinksCreated, setFailures)
```

---

### callPolynom()

**Файл:** `migration/PolynomAuthRetry.kt`

**Сигнатура:**
```kotlin
suspend fun <T> MigrationContext.callPolynom(block: suspend (accessToken: String) -> T): T
```

**Описание:** Оборачивает один вызов ПОЛИНОМ — реальный инцидент: `access_token` живёт 600с,
на длинной миграции протухает посреди прогона, сервер отвечает `401` на любой следующий вызов (см.
[[03-external-api-quirks.md]]). Ловит `ResponseException` со статусом `401`, обновляет токен через
`polynomClient.login.updateToken(accessToken, refreshToken)`, повторяет `block` РОВНО один раз с
новым токеном. Обёрнуты ВСЕ вызовы `polynomClient.*` в движке (`MigrationEngine.kt`,
`MaterialsEngine.kt`, `AnalogGroupsEngine.kt`) — 19 точек вызова.

**Использует:**
- `MigrationContext.polynomTokenMutex` (через приватную `refreshPolynomToken`) — coalesce
  конкурентных обновлений: если к моменту захвата лока `polynomAccessToken` уже не равен значению,
  с которым стартовал текущий вызов, значит другая корутина уже обновила токен — используется он,
  без второго сетевого запроса на обновление.

**Пример использования:**
```kotlin
val found = callPolynom { token ->
    polynomClient.search.searchByStringProperty(token, scope, propertyDefinition, value)
}
```

---

### classifyCreatedObjects()

**Файл:** `migration/MigrationEngine.kt`

**Сигнатура:**
```kotlin
private suspend fun MigrationContext.classifyCreatedObjects(candidates: List<ObjectClassificationCandidate>)
```

**Описание:** Классифицирует в ПОЛИНОМ:MDM обычные не-папочные объекты, созданные на шаге
"Объекты" через `EditObject/new-object` (`mapping.types[]` без `resolveViaPolynom`) — кандидаты
собираются в `processObjectRow` и передаются сюда из `runObjectsMigration()`, после
`resolvePolynomBackedObjects(...)`. Группирует кандидатов по `classifierId` (один поиск в ПОЛИНОМ
на уникальный код), затем для каждого `loodsmanId` группы отдельным вызовом
`BoReference/reference-bo-version` (`EditObject.boReference.referenceBoVersion`) привязывает
версию Loodsman к найденному `location`. Не найдено в ПОЛИНОМ / ошибка на любом шаге — лог +
счётчик, объект остаётся созданным без классификации, исключение не пробрасывается (не должно
прерывать обработку остальных кандидатов).

**Использует:**
- `resolvePropertyDefinitionByAbsoluteCode`/`resolveSearchScope` (`MaterialsEngine.kt`) — тот же
  `classifierCodeProperty`/scope, что у материалов и `resolvePolynomBackedObjects`
- `polynomClient.search.searchByStringProperty`, `polynomClient.classification.getLocation`
- `loodsmanClient.boReference.referenceBoVersion` (`client/BoReference.kt`, см.
  [[11-api-reference.md]])

**Пример использования:**
```kotlin
classifyCreatedObjects(results.flatMap { it.classificationCandidates })
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

**Файл:** `mapping/Mapping.kt`

**Описание:** Конфигурация маппинга Excel → Loodsman. Актуальный состав (обновлено по факту кода,
предыдущая версия этого раздела была устаревшей заготовкой):

```kotlin
data class Mapping(
    val source: Source,
    val objectsSheet: ObjectsSheet,
    val linksSheet: LinksSheet,
    val identifierColumn: String,
    val attributes: List<Attribute>,
    val types: List<MappingElement>,
    val materials: MaterialsSettings,
    val bomMaterials: BomMaterialsSettings = BomMaterialsSettings.None,
    val analogGroups: AnalogGroupsSettings = AnalogGroupsSettings.None,
    val blanks: BlanksSettings = BlanksSettings.None,
    val castingBlanks: CastingBlanksSettings = CastingBlanksSettings.None,
    val auxMaterials: AuxMaterialsSettings = AuxMaterialsSettings.None
)
```

---

### MappingElement

**Файл:** `domain/MappingElement.kt`

**Описание:** Правило маппинга строки Excel в тип объекта Loodsman (`mapping.types[]`). Актуальный
состав (предыдущая версия этого раздела была устаревшей заготовкой, поля не совпадали с реальными
именами в JSON — см. [[05-settings-reference.md]] за полным описанием семантики каждого поля):

```kotlin
data class MappingElement(
    val target: String,
    val source: String,
    val state: String? = null,
    val isProject: Boolean = false,
    val isFolder: Boolean = false,
    val linkToRoot: Boolean = false,
    val conditions: Conditions,
    val childLinkType: String? = null,
    val childOfSameTypeLinkType: String? = null,
    val resolveViaPolynom: Boolean = false,
    // Атрибуты, специфичные ТОЛЬКО для этого правила — в дополнение к глобальному
    // mapping.attributes[], см. Attribute ниже. Игнорируется при resolveViaPolynom=true и для
    // isFolder=true (см. 04-business-logic.md).
    val attributes: List<Attribute> = emptyList(),
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

**Описание:** Атрибут объекта/связи для установки — используется в `mapping.attributes[]`,
`mappingElement.attributes`, `materials.attributes`, `blanks.attributes`,
`blanks.materialAttributes`, `blanks.materialObjectAttributes` (см. [[05-settings-reference.md]]).
Актуальный состав (предыдущая версия этого раздела была устаревшей заготовкой):

```kotlin
data class Attribute(
    val attrColumn: String,        // столбец Excel — источник значения
    val loodsmanAttr: String,      // имя атрибута в Loodsman
    val replace: ReplaceRule? = null,
    val unit: String? = null,      // фиксированное обозначение единицы (например "мм")
    val numeric: Boolean = false,  // запятая -> точка перед отправкой, см. AttributeResolver
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

**Описание:** Кандидат на создание "Материала по КД" (потоки A/B), собирается в `processObjectRow`
на строке Детали. Актуальный состав (предыдущая версия этого раздела была устаревшей заготовкой):

```kotlin
data class MaterialCandidate(
    val detailLoodsmanId: Int,
    val drawingDesignation: String?,
    val classifierCode: String?,
    val detailClassifierCode: String? = null,
    // mapping.materials.attributes, резолвлены с той же строки Детали — см. 05-settings-reference.md
    val attributeValues: Map<String, String> = emptyMap(),
) {
    val dedupKey: String?  // classifierCode, фолбэк на drawingDesignation, оба пустые -> null
}
```

---

### BomMaterialCandidate

**Файл:** `domain/BomMaterialCandidate.kt`

**Описание:** Кандидат на создание "Материала по КД" для потока C (DS-объекты). Актуальный состав:

```kotlin
data class BomMaterialCandidate(
    val parentLoodsmanId: Int,
    val classifierCode: String,
    val quantity: Double,
    val unitDesignation: String?,
    // mapping.materials.attributes, резолвлены со строки раздела "Материалы" листа "Объекты"
    // (не со строки Детали, в отличие от MaterialCandidate выше) — см. MigrationContext.bomMaterialRowAttributes.
    val attributeValues: Map<String, String> = emptyMap(),
)
```

---

### BlankCandidate

**Файл:** `domain/BlankCandidate.kt`

**Описание:** Кандидат на создание "Заготовки" + "Материала основного" (`mapping.blanks`).
Собирается только когда `materials.classifierCodeColumn` на строке непустой — в отличие от
`MaterialCandidate`, `classifierCode` здесь не nullable. Актуальный состав:

```kotlin
data class BlankCandidate(
    val detailLoodsmanId: Int,
    val detailDesignation: String,
    val classifierCode: String,
    val rate: Double? = null,             // норма расхода, null = не проставлять
    val rateUnitDesignation: String? = null,
    val objectAttributes: List<ResolvedAttribute> = emptyList(),      // blanks.attributes
    val materialLinkAttributes: Map<String, String> = emptyMap(),     // blanks.materialAttributes
    val materialObjectAttributes: Map<String, String> = emptyMap(),   // blanks.materialObjectAttributes
)
```

---

### CastingBlanksSettings / AuxMaterialsSettings

**Файлы:** `domain/CastingBlanksSettings.kt`, `domain/AuxMaterialsSettings.kt`

**Описание:** Настройки потоков E/F (см. [[04-business-logic.md]], [[05-settings-reference.md]]).
`CastingBlanksSettings` реализует `DataSheet` (координаты листа + бизнес-поля в одном классе, тот
же приём, что `LinksSheet`), `parentColumn`/`childColumn` — по ИНДЕКСУ столбца.
`AuxMaterialsSettings` — то же самое, но `parentColumn`/`childColumn` — ИМЕНА столбцов (резолв через
`RowView`, не `row.cells.getOrNull(index)`), и без `materialTypeColumn`/`auxMaterialTarget` — поток F
не ветвится по типу материала.

```kotlin
data class CastingBlanksSettings(
    override val name: String = "",
    override val rawHeadersRow: Int = -1,
    val rawParentColumnIndex: Int = -1,
    val rawChildColumnIndex: Int = -1,
    val quantityColumn: String = "",
    val unitColumn: String = "",
    val workshopColumn: String = "",
    val materialTypeColumn: String = "",
    val setTarget: String = "",        // "" выключает поток целиком
    val setState: String = "",
    val setLinkType: String = "",
    val materialTarget: String = "",
    val auxMaterialTarget: String = "",
    val materialLinkType: String = "",
    val rateAttribute: String = "",
    val workshopAttribute: String = "",
) : DataSheet
```

`setTarget.isBlank()` — тумблер потока целиком, тот же паттерн, что `BlanksSettings.target`.

---

### CastingBlankLinkCandidate / AuxMaterialLinkCandidate

**Файлы:** `domain/CastingBlankLinkCandidate.kt`, `domain/AuxMaterialLinkCandidate.kt`

**Описание:** Одна строка своего листа Excel (потоки E/F). Родитель НЕ хранится в кандидате —
кандидаты группируются по `classifierId` родителя (`Map<Long, MutableList<...>>` в движке), сам
родитель резолвится в `Identifier` один раз на группу.

```kotlin
data class CastingBlankLinkCandidate(
    val childClassifierCode: String,
    val materialType: String?,          // "Образец"/"Основной"/"Вспомогательный" — только поток E
    val quantity: Double? = null,
    val unitDesignation: String? = null,
    val workshop: String? = null,
)

data class AuxMaterialLinkCandidate(
    val childClassifierCode: String,
    val quantity: Double? = null,       // без materialType — поток F не ветвится
    val unitDesignation: String? = null,
    val workshop: String? = null,
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

### ObjectClassificationCandidate

**Файл:** `domain/ObjectClassificationCandidate.kt`

**Описание:** Кандидат на классификацию в ПОЛИНОМ (`BoReference/reference-bo-version`) — не-папочный
объект, созданный обычным `EditObject/new-object`. Собирается в `processObjectRow`, обрабатывается
`classifyCreatedObjects()`.

```kotlin
data class ObjectClassificationCandidate(
    val loodsmanId: Int,                // Loodsman id созданного объекта
    val classifierId: Long,             // код классификатора объекта (mapping.identifierColumn)
)
```

---

### Identifier

**Файл:** `domain/Identifier.kt`

**Описание:** Идентификатор созданного объекта — резолвится по `classifierId` листом "Связи" и
потоками E/F (свои листы Excel). Актуальный состав (обновлено по факту кода):

```kotlin
data class Identifier(
    val loodsmanId: Int,
    val classifierId: Long,
    // Обозначение объекта (mappingElement.source на его строке "Объекты") — пусто для объектов,
    // резолвленных через ПОЛИНОМ (resolveViaPolynom). Добавлено для потоков E/F — keyAttribute
    // комплекта = обозначение родителя, а родитель резолвится не на своей исходной строке Excel,
    // а по classifierId из совсем другого листа.
    val designation: String = "",
    val childLinkType: String? = null,           // см. "Технологические детали" в 04-business-logic.md
    val childOfSameTypeLinkType: String? = null,
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

**Описание:** НЕ suspend, чистые функции (предыдущая версия этого раздела была устаревшей
заготовкой). Две функции, обе применяют `ReplaceRuleStrategies.resolve` + `normalizeIfNumeric`
(запятая → точка для `Attribute.numeric`, см. [[03-external-api-quirks.md]]), различаются формой
результата:

```kotlin
// Схлопывает несколько attrColumn на один loodsmanAttr в Map (для materials.attributes/
// blanks.materialAttributes и т.п., где unit не нужен) — обычные атрибуты объекта/связи.
fun resolveAttributes(attributes: List<Attribute>, row: RowView): Map<String, String>

// Возвращает List<ResolvedAttribute> с unitDesignation (для blanks.attributes, где нужна единица
// измерения) — Map<String,String> для этого не годится. Сама дедуплицирует результат по
// loodsmanAttr (последняя по порядку запись побеждает) — реальный инцидент, см.
// 03-external-api-quirks.md/04-business-logic.md (поток D).
fun resolveAttributesWithUnits(attributes: List<Attribute>, row: RowView): List<ResolvedAttribute>
```

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

**Сигнатура (актуальный состав, предыдущая версия этого раздела была устаревшей заготовкой):**
```kotlin
fun resolveLinkQuantity(quantityColumn: String, row: RowView): Double?
```

**Описание:** Резолвит количество связи из строки Excel. НЕ suspend, чистая функция.
`quantityColumn.isBlank()` — всегда возвращает `1.0` (столбец не настроен, столбец не читается).

**Возвращает:** Double или null, если колонка настроена, но значение не парсится (запятая
предварительно заменяется на точку).

---

### LinkUnitResolver

**Файл:** `migration/LinkUnitResolver.kt`

**Сигнатура (актуальный состав, предыдущая версия этого раздела была устаревшей заготовкой):**
```kotlin
fun resolveLinkUnitDesignation(unitColumn: String, unitExcludeValues: List<String>, row: RowView): String?
```

**Описание:** Резолвит ОБОЗНАЧЕНИЕ единицы измерения связи из строки Excel (не suspend, без
похода в Loodsman — сам идентификатор единицы резолвится отдельно, `resolveUnitId` в
`MigrationEngine.kt`, по уникальным обозначениям сразу для всех связей). `null` означает "unit
связи не трогать вообще" — для пустого `unitColumn`, пустой ячейки и значений из
`unitExcludeValues` (например "шт", "компл") одинаково.

**Возвращает:** Обозначение единицы или null.

---

### LinkCommentResolver

**Файл:** `migration/LinkCommentResolver.kt`

**Сигнатура:**
```kotlin
fun resolveLinkComment(commentColumn: String, row: RowView): String?
```

**Описание:** Резолвит произвольный текстовый комментарий связи (`linksSheet.commentColumn`) из
строки листа "Связи" — тот же приём, что `LinkQuantityResolver`/`LinkUnitResolver` (НЕ suspend,
чистая функция). `commentColumn.isBlank()` — всегда `null` (фича выключена). Значение
переносится в атрибут связи `linksSheet.commentAttribute` через `EditObject/up-link-attr-values`
сразу после создания связи в `runLinksMigration()` (`MigrationEngine.kt`) — см.
[[04-business-logic.md]], шаг 2.

**Возвращает:** Текст комментария или null (столбец не настроен / ячейка пуста).

---

### retryOnTransientError()

**Файл:** `client/Helpers.kt`

**Сигнатура:**
```kotlin
suspend fun <T> retryOnTransientError(
    times: Int = 3,
    initialDelayMs: Long = 1_000,
    block: suspend () -> T
): T
```

**Описание:** Повтор на транзиентных сбоях (таймаут, 5xx) с экспоненциальным backoff — реальный
инцидент: `EditObject/create-bo-object` под нагрузкой иногда не укладывается в
`requestTimeoutMillis`, см. [[03-external-api-quirks.md]]. Ловит только
`HttpRequestTimeoutException` и `ResponseException` со статусом 5xx; остальные исключения
пробрасываются без повтора. Используется **только** в `EditObject.createBoObject` и
`BoReference.referenceBoVersion` (`client/EditObject.kt`, `client/BoReference.kt`) — вызов
находится снаружи `requestGate.withPermit`, чтобы не держать permit семафора на время задержки
между попытками. Осознанно не применяется к `new-object`/`new-link`: таймаут не гарантирует, что
запрос не выполнился на сервере, повтор неидемпотентного вызова может создать дубль.

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