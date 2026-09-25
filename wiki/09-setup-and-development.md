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

Подробное описание всех полей см. в [[05-settings-reference.md]]

### 2. Сборка и запуск

**Вариант 1 — distribution-скрипт:**
```bash
./gradlew installDist
./build/install/lis-client-ktor/bin/lis-client-ktor        # Linux/WSL/Mac
.\build\install\lis-client-ktor\bin\lis-client-ktor.bat    # Windows
```

**Вариант 2 — fat jar:**
```bash
./gradlew shadowJar
java -Dfile.encoding=UTF-8 -jar build/libs/lis-client-ktor-0.0.1-all.jar
```
(`-Dfile.encoding=UTF-8` руками обязателен для `java -jar` — вариант 1 подставляет его сам через `applicationDefaultJvmArgs`.)

**ВАЖНО:** Не используйте `./gradlew run` — авторизация требует интерактивную консоль (`System.console()`), которая недоступна под Gradle (JVM форкается через pipe).

### 3. Запуск через Docker

```bash
cp .env.example .env   # поправить пути к settings.json / xlsx на хосте
docker compose up --build
```

- `Dockerfile` — multi-stage: сборка через `installDist`, в рантайме только JRE + собранный
  дистрибутив.
- `docker-compose.yml` — `stdin_open: true` + `tty: true` обязательны: без реального tty
  `System.console()` в контейнере тоже будет `null` (та же причина, что у `gradlew run` выше) —
  `LoginWorkers.kt` упадёт с "Нет консоли".
- Логин/пароль — только через консоль (`docker compose up` без `-d`), в `.env` или образ не
  кладутся.
- `.env` (`SETTINGS_FILE`/`EXCEL_FILE`) задаёт только хостовые пути для volume-монтирования
  `settings.json` и исходного xlsx в `/data` — не credentials.
- `settings.mapping.source.path` в `settings.json` обычно хранит хостовый путь (для локального
  запуска) — внутри контейнера это переопределяет переменная окружения `LIS_EXCEL_PATH`
  (задана в `docker-compose.yml` как `/data/data.xlsx`, см. `MigrationEngine.excelInputStream()`).
  Один и тот же `settings.json` подходит и для локального запуска, и для докера.

### 4. Работа в REPL

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
**Решение:** Запускайте собранный дистрибутив (`installDist`) или fat jar (`shadowJar`) напрямую,
не через `./gradlew run` — см. "Сборка и запуск" выше.

### Проблема: Объекты не видны после миграции
**Решение:** Выполните команду `exit` для check-in. Чекаут скрывает изменения до check-in.

### Проблема: Deadlock при создании групп замены
**Решение:** Группы замены создаются последовательно (не конкурентно) — это ограничение API Loodsman.

### Проблема: Ошибки десериализации ответа API
**Решение:** Проверьте соответствие DTO реальному ответу API через Swagger UI.

## Полезные ресурсы

- [[01-architecture-overview.md]] — обзор архитектуры
- [[04-business-logic.md]] — бизнес-логика миграции
- [[05-settings-reference.md]] — справочник по конфигурации
- [[06-glossary.md]] — глоссарий терминов