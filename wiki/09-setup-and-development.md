# Инструкции по запуску и разработке

## Требования

- **JDK 17+** — для компиляции и запуска
- **Gradle** — для сборки (использует Wrapper)
- **Loodsman PLM** — доступный REST API сервер
- **ПОЛИНОМ:MDM** — доступный REST API сервер (опционально, если используются материалы)

## Быстрый старт

### 1. Настройка конфигурации

Создайте файл `settings.json` в рабочей директории:

```json
{
  "connection": {
    "dbName": "loodsman",
    "remember": false,
    "url": "http://localhost:8076/api/v4/",
    "maxConcurrentRequests": 50
  },
  "polynom": {
    "url": "http://localhost:5100/",
    "storageId": null,
    "moduleName": "",
    "clientType": 0,
    "maxConcurrentRequests": 5
  },
  "mapping": {
    "source": {
      "type": "xlsx",
      "path": "/path/to/data.xlsx"
    },
    "objectsSheet": {
      "name": "Объекты",
      "headersRowIndex": 1
    },
    "linksSheet": {
      "name": "Связи",
      "headersRowIndex": 1,
      "parentColumnIndex": 1,
      "childColumnIndex": 2,
      "linkType": "Состоит из ...",
      "quantityColumn": "конструкторское количество",
      "unitColumn": "единица измерения",
      "unitExcludeValues": ["шт"]
    },
    "identifierColumn": "код классификатора",
    "attributes": [
      {
        "attrColumn": "наименование",
        "loodsmanAttr": "Наименование"
      }
    ],
    "types": [
      {
        "target": "Деталь",
        "source": "обозначение",
        "state": "Проектирование",
        "isProject": false,
        "linkToRoot": false,
        "conditions": {
          "single": {
            "type": "check",
            "column": "Раздел спецификации",
            "is": "Детали"
          },
          "or": [],
          "and": []
        }
      }
    ]
  }
}
```

Подробное описание всех полей см. в [[05-settings-reference.md](wiki/05-settings-reference.md)]

### 2. Сборка и запуск

**Linux/WSL/Mac:**
```bash
./run.sh
```

**Windows:**
```cmd
run.cmd
```

**ВАЖНО:** Не используйте `./gradlew run` — авторизация требует интерактивную консоль (`System.console()`), которая недоступна под Gradle.

### 3. Работа в REPL

После запуска вы попадёте в интерактивную консоль:

```
LIS Client Ktor
> hello
Привет! Я LIS Client Ktor для миграции данных из Excel в Loodsman PLM.
> migration
[Выполняется миграция...]
> exit
[Check-in и завершение]
```

**Доступные команды:**
- `hello` — приветствие
- `migration` — запуск миграции
- `exit` — чекаут и выход

## Разработка

### Сборка проекта

```bash
./gradlew build
```

### Запуск тестов

```bash
./gradlew test
```

### Создание дистрибутива

```bash
./gradlew installDist
```

Дистрибутив создаётся в `build/install/lis-client-ktor/`

### Структура проекта

```
src/main/kotlin/com/khan366kos/lis/client/ktor/
├── client/              # HTTP-клиенты к Loodsman
├── polynom/client/      # HTTP-клиенты к ПОЛИНОМ
├── domain/              # Доменные модели
├── dsl/                 # COR DSL
├── pipelines/           # Пайплайны миграции
├── workers/             # Шаги пайплайнов
├── migration/           # Бизнес-логика
├── excel/               # Работа с Excel
├── repl/                # Интерактивная консоль
├── logic/               # Валидация
├── mapping/             # Преобразования моделей
├── loodsman/api/dto/    # DTO Loodsman
└── polynom/api/dto/     # DTO ПОЛИНОМ
```

## Добавление новых правил

### Новое правило условия

Зарегистрируйте новый тип в `RuleEvaluator`:

```kotlin
RuleEvaluators.register("newType") { rule, value ->
    // Логика проверки
    value == rule.isValue
}
```

Использование в `settings.json`:
```json
{
  "type": "newType",
  "column": "столбец",
  "is": "значение"
}
```

### Новая стратегия замены

Зарегистрируйте в реестре `ReplaceRuleStrategies` (если нужно добавить новый тип replace).

### Новый шаг пайплайна

Создайте worker в `workers/`:

```kotlin
fun ICorChainDsl<MigrationContext>.newWorker() = worker {
    on { /* условие выполнения */ }
    handle {
        // основная логика
    }
    except { e ->
        // обработка ошибок
        throw e
    }
}
```

Добавьте в нужный пайплайн:
```kotlin
object SomePipeline : ICorExec<MigrationContext> by pipeline<MigrationContext>({
    newWorker()
}).build()
```

## Отладка

### Логирование

Проект использует Logback. Настройте логирование в `src/main/resources/logback.xml` (если нужно создать).

### Включение детальных логов

Измените уровень логирования в `settings.json` или добавьте логирование в код:

```kotlin
println("Детальная информация: $data")
```

### Работа с большими Excel файлами

Используйте потоковый парсер `ExcelSaxParser` — он не загружает весь файл в память.

### Проблемы с конкурентностью

Настройте `maxConcurrentRequests` в `connection` и `polynom` секциях `settings.json`.

## Известные проблемы и решения

### Проблема: Пароль не запрашивается
**Решение:** Запускайте через `./run.sh` или `run.cmd`, не через `./gradlew run`

### Проблема: Объекты не видны после миграции
**Решение:** Выполните команду `exit` для check-in. Чекаут скрывает изменения до check-in.

### Проблема: Deadlock при создании групп замены
**Решение:** Группы замены создаются последовательно (не конкурентно) — это ограничение API Loodsman.

### Проблема: Ошибки десериализации ответа API
**Решение:** Проверьте соответствие DTO реальному ответу API через Swagger UI.

## Полезные ресурсы

- [[01-architecture-overview.md](wiki/01-architecture-overview.md)] — обзор архитектуры
- [[04-business-logic.md](wiki/04-business-logic.md)] — бизнес-логика миграции
- [[05-settings-reference.md](wiki/05-settings-reference.md)] — справочник по конфигурации
- [[06-glossary.md](wiki/06-glossary.md)] — глоссарий терминов