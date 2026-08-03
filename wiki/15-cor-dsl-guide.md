# Гайд по COR DSL и Rule Engine

## Краткая сводка

Практические примеры использования COR DSL для построения пайплайнов и Rule Engine для обработки правил.

## COR DSL

### Основные концепции

COR DSL — это Chain-of-Responsibility DSL для построения пайплайнов обработки.

**Основные компоненты:**
- `pipeline()` — создаёт цепочку обработки
- `worker()` — добавляет шаг в цепочку
- `parallel()` — добавляет параллельный шаг

---

### Базовый пример

```kotlin
val simplePipeline = pipeline<MigrationContext> {
    worker {
        on { status == Status.INIT }
        handle {
            println("Инициализация...")
            status = Status.SETTINGS_LOADED
        }
    }

    worker {
        on { status == Status.SETTINGS_LOADED }
        handle {
            println("Загрузка настроек...")
            status = Status.CONNECT_CHECKOUT
        }
    }
}

// Запуск
val context = MigrationContext()
simplePipeline.execute(context)
```

---

### Обработка исключений

```kotlin
val pipelineWithErrorHandling = pipeline<MigrationContext> {
    worker {
        on { status == Status.CONNECT_CHECKOUT }
        handle {
            runObjectsMigration()
            status = Status.OBJECTS_MIGRATED
        }
        except { e ->
            System.err.println("Ошибка миграции объектов: ${e.message}")
            status = Status.ERROR
            throw e  // ← Важно: явно выбрасываем исключение
        }
    }
}
```

**Важно:** Если `except()` не вызывает `throw` явно — исключение молча гасится, chain идёт дальше.

---

### Параллельное выполнение

```kotlin
val parallelPipeline = pipeline<MigrationContext> {
    worker {
        on { status == Status.OBJECTS_MIGRATED }
        handle {
            runLinksMigration()
            status = Status.LINKS_MIGRATED
        }
    }

    parallel {
        on { status == Status.LINKS_MIGRATED }
        handle {
            // Выполнится параллельно с другими parallel
            runMaterialsMigration()
        }
    }

    parallel {
        on { status == Status.LINKS_MIGRATED }
        handle {
            // Выполнится параллельно с другими parallel
            runBomMaterialsMigration()
        }
    }
}
```

**Важно:** Реальная конкурентность ограничивается `requestGate` в `Client`.

---

### Вложенные пайплайны

```kotlin
val nestedPipeline = pipeline<MigrationContext> {
    worker {
        on { status == Status.INIT }
        handle {
            println("Шаг 1")
            status = Status.STEP_1
        }
    }

    pipeline {
        on { status == Status.STEP_1 }
        worker {
            handle {
                println("Вложенный шаг 1")
            }
        }
        worker {
            handle {
                println("Вложенный шаг 2")
            }
        }
    }
}
```

---

### Условия (on)

```kotlin
val conditionalPipeline = pipeline<MigrationContext> {
    worker {
        on { status == Status.CONNECT_CHECKOUT }
        handle {
            println("Запуск миграции")
        }
    }

    worker {
        on { status == Status.OBJECTS_MIGRATED && identifiers.size > 0 }
        handle {
            println("Создано объектов: ${identifiers.size}")
        }
    }

    worker {
        on { true }  // ← Всегда выполняется
        handle {
            println("Финализация")
        }
    }
}
```

---

### Extension-функции для воркеров

**Файл:** `workers/MigrationWorkers.kt`

```kotlin
fun ICorChainDsl<MigrationContext>.migrateObjects() = worker {
    on { status == Status.CONNECT_CHECKOUT }
    handle {
        runObjectsMigration()
        status = Status.OBJECTS_MIGRATED
        println("Создано объектов: ${identifiers.size}")
    }
    except { e ->
        System.err.println("Ошибка миграции объектов: ${e.message}")
        throw e
    }
}

fun ICorChainDsl<MigrationContext>.migrateLinks() = worker {
    on { status == Status.OBJECTS_MIGRATED }
    handle {
        runLinksMigration()
        status = Status.LINKS_MIGRATED
    }
}

// Использование
val migrationPipeline = pipeline<MigrationContext> {
    migrateObjects()
    migrateLinks()
}
```

---

## Rule Engine

### Основные концепции

Rule Engine — это система для декларативного описания правил маппинга Excel → Loodsman.

**Основные компоненты:**
- `Rule` — правило
- `Conditions` — условия (single, or, and)
- `RuleEvaluator` — оценщик правил
- `ConditionsEvaluator` — оценщик условий

---

### Типы правил

| Тип | Описание | Пример |
|-----|----------|--------|
| `equals` | Равенство значений | `{"type": "equals", "column": "Тип", "is": "Сборка"}` |
| `contains` | Содержит подстроку | `{"type": "contains", "column": "Наименование", "is": "деталь"}` |
| `startsWith` | Начинается с | `{"type": "startsWith", "column": "Обозначение", "is": "ИМ"}` |
| `endsWith` | Заканчивается на | `{"type": "endsWith", "column": "Обозначение", "is": "00"}` |
| `regex` | Регулярное выражение | `{"type": "regex", "column": "Обозначение", "is": "^ИМ.*00$"}` |
| `notEmpty` | Не пустое значение | `{"type": "notEmpty", "column": "Наименование"}` |

---

### Простое правило (single)

**settings.json:**
```json
{
  "mapping": {
    "types": [
      {
        "type": "ВидИзделия",
        "conditions": {
          "single": {
            "type": "equals",
            "column": "Тип",
            "is": "Изделие"
          }
        },
        "attributes": [
          {"id": 100, "column": "Обозначение"},
          {"id": 101, "column": "Наименование"}
        ]
      }
    ]
  }
}
```

**Обработка:**
```kotlin
val conditions = MappingElement(...).conditions
val rowView = RowView(headers, cells)

val evaluator = ConditionsEvaluator(rowView)
if (evaluator.evaluate(conditions)) {
    // Правило сработало
    val objectName = rowView["Наименование"]
    val designation = rowView["Обозначение"]
    // Создаём объект
}
```

---

### Логическое ИЛИ (or)

**settings.json:**
```json
{
  "conditions": {
    "or": [
      {"type": "equals", "column": "Тип", "is": "Сборка"},
      {"type": "equals", "column": "Тип", "is": "Комплект"}
    ]
  }
}
```

**Обработка:**
```kotlin
val evaluator = ConditionsEvaluator(rowView)
if (evaluator.evaluate(conditions)) {
    // Хотя бы одно правило сработало
}
```

---

### Логическое И (and)

**settings.json:**
```json
{
  "conditions": {
    "and": [
      {"type": "startsWith", "column": "Обозначение", "is": "ИМ"},
      {"type": "notEmpty", "column": "Наименование"}
    ]
  }
}
```

**Обработка:**
```kotlin
val evaluator = ConditionsEvaluator(rowView)
if (evaluator.evaluate(conditions)) {
    // Все правила сработали
}
```

---

### Вложенные условия

**settings.json:**
```json
{
  "conditions": {
    "or": [
      {
        "and": [
          {"type": "equals", "column": "Тип", "is": "Сборка"},
          {"type": "notEmpty", "column": "Наименование"}
        ]
      },
      {
        "type": "equals",
        "column": "Тип",
        "is": "Комплект"
      }
    ]
  }
}
```

**Логика:**
- (Тип == "Сборка" И Наименование не пусто) ИЛИ (Тип == "Комплект")

---

### Custom Rule Evaluator

**Создание кастомного оценщика:**

```kotlin
class CustomRuleEvaluator : RuleEvaluator {
    override fun evaluate(rule: Rule, context: Any): Boolean {
        val rowView = context as RowView
        val value = rowView[rule.column ?: ""] ?: return false

        return when (rule.type) {
            "customGreaterThan" -> {
                val threshold = rule.isValue?.toDoubleOrNull() ?: 0.0
                value.toDoubleOrNull()?.let { it > threshold } ?: false
            }
            else -> false
        }
    }
}

// Регистрация
RuleEvaluators.register("customGreaterThan", CustomRuleEvaluator())
```

**Использование:**
```json
{
  "conditions": {
    "single": {
      "type": "customGreaterThan",
      "column": "Количество",
      "is": "10"
    }
  }
}
```

---

### Атрибуты с правилами замены

**settings.json:**
```json
{
  "attributes": [
    {
      "id": 100,
      "column": "Обозначение",
      "replaceRule": {
        "strategy": "uppercase",
        "source": "column"
      }
    },
    {
      "id": 101,
      "column": "Наименование",
      "replaceRule": {
        "strategy": "trim",
        "source": "column"
      }
    }
  ]
}
```

**Стратегии замены:**
- `uppercase` — привести к верхнему регистру
- `lowercase` — привести к нижнему регистру
- `trim` — удалить пробелы по краям
- `replace` — заменить по паттерну

---

### Полный пример миграции

**settings.json:**
```json
{
  "connection": {
    "url": "http://loodsman-api",
    "dbName": "production",
    "maxConcurrentRequests": 10
  },
  "mapping": {
    "objectsSheet": {
      "name": "Объекты",
      "headersRow": 1
    },
    "linksSheet": {
      "name": "Связи",
      "headersRow": 1
    },
    "types": [
      {
        "type": "ВидИзделия",
        "folder": true,
        "conditions": {
          "single": {
            "type": "equals",
            "column": "Тип",
            "is": "Изделие"
          }
        },
        "attributes": [
          {"id": 100, "column": "Обозначение"},
          {"id": 101, "column": "Наименование"}
        ]
      },
      {
        "type": "СборочнаяЕдиница",
        "conditions": {
          "or": [
            {"type": "equals", "column": "Тип", "is": "Сборка"},
            {"type": "equals", "column": "Тип", "is": "Комплект"}
          ]
        },
        "attributes": [
          {"id": 100, "column": "Обозначение"},
          {"id": 101, "column": "Наименование"},
          {"id": 102, "column": "Масса"}
        ]
      },
      {
        "type": "Деталь",
        "conditions": {
          "and": [
            {"type": "equals", "column": "Тип", "is": "Деталь"},
            {"type": "notEmpty", "column": "Наименование"}
          ]
        },
        "attributes": [
          {"id": 100, "column": "Обозначение"},
          {"id": 101, "column": "Наименование"}
        ]
      }
    ]
  },
  "materials": {
    "materialTarget": "Материал",
    "sheetName": "Материалы"
  }
}
```

---

## Лучшая практика

### COR DSL

1. **Используйте extension-функции** для переиспользования воркеров.
2. **Всегда обрабатывайте исключения** с явным `throw`.
3. **Используйте `parallel`** для независимых операций.
4. **Используйте `on`** для условного выполнения.

### Rule Engine

1. **Начинайте с простых правил** (`single`, `equals`).
2. **Используйте `or`** для альтернативных вариантов.
3. **Используйте `and`** для строгих условий.
4. **Создавайте кастомные оценщики** для специфической логики.
5. **Используйте `replaceRule`** для форматирования значений.

---

## Связанные документы

- [[01-architecture-overview.md]] - Обзор архитектуры
- [[04-business-logic.md]] - Бизнес-логика
- [[10-concepts-and-patterns.md]] - Концепции и паттерны
- [[12-key-classes.md]] - Ключевые классы и функции