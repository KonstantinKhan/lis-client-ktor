# Технологический стек

## Язык и runtime

- **Kotlin 2.4.10** — основной язык разработки
- **JVM 17** — целевая платформа (kotlin.jvmToolchain = 17)
- **Kotlin Coroutines 1.11.0** — асинхронное программирование

## HTTP-клиент

- **Ktor Client 2.4.10** — HTTP-клиент для интеграции с внешними системами
  - `ktor-client-core` — основа клиента
  - `ktor-client-cio` — async HTTP engine (CIO)
  - `ktor-client-content-negotiation` — контент-неготация для REST API
  - `ktor-serialization-kotlinx-json` — сериализация JSON

## Сериализация

- **Kotlinx Serialization 1.11.0** — типобезопасная сериализация/десериализация JSON

## Работа с Excel

- **Apache POI 5.5.1** — работа с xlsx файлами (streaming SAX-парсер для больших файлов)

## Логирование

- **Logback Classic 1.5.38** — фреймворк логирования

## Сборка и запуск

- **Gradle** — система сборки
- **Kotlin DSL** — скрипты сборки на Kotlin (*.kts файлы)

## Интегрируемые системы

### Loodsman PLM
- **REST API v4** — основная целевая система
- **Checkout/Check-in** — работа с чекаутами
- **Object editing** — создание/изменение объектов
- **Links management** — управление связями
- **Metadata** — работа с типами и атрибутами

### ПОЛИНОМ:MDM
- **REST API** — справочник материалов и классификаторов
- **Classification** — поиск материалов по кодам
- **Property search** — поиск по свойствам
- **BO-objects** — создание связанных бизнес-объектов

## Паттерны и подходы

- **COR DSL** — собственный DSL для Chain-of-Responsibility
- **Repository pattern** — тонкие обёртки над HTTP-эндпоинтами
- **DSL конфигурация** — JSON-DSL для бизнес-правил миграции
- **Rule Engine** — регистрируемая система правил
- **Streaming processing** — потоковая обработка больших Excel файлов (SAX)
- **Concurrent processing** — конкурентная обработка с семафорами
- **Functional programming** — чистые функции для резолверов