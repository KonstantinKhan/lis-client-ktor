# Справочник по API

## Краткая сводка

Документация всех эндпоинтов Loodsman REST API с параметрами, DTO, примерами запросов/ответов.

## Таблица эндпоинтов

### Авторизация

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| POST | `/Auth/login` | `client/Login.kt` | Авторизация в системе |
| GET | `/Auth/current-user` | `client/Client.kt` | Информация о текущем пользователе |

### Чекаут/Чекин

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| GET | `/CheckOut/check-out` | `client/CheckOut.kt` | Создать чекаут (режим 0) |
| GET | `/CheckOut/connect-to-check-out` | `client/CheckOut.kt` | Подключиться к существующему чекауту |
| POST | `/CheckOut/check-in-2` | `client/CheckOut.kt` | Завершить чекаут с файлами |

### Объекты

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| POST | `/EditObject/new-object` | `client/EditObject.kt` | Создать новый объект |
| POST | `/EditObject/new-link` | `client/EditObject.kt` | Создать связь между объектами |
| POST | `/EditObject/up-link` | `client/EditObject.kt` | Обновить существующую связь |
| POST | `/EditObject/up-attr-values-by-ids` | `client/EditObject.kt` | Установить значения атрибутов |
| POST | `/EditObject/create-bo-object` | `client/EditObject.kt` | Создать бизнес-объект |
| POST | `/EditObject/insert-object` | `client/Client.kt` | Вставить объект (гибкий) |

### Метаданные

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| GET | `/ConfMetaData/get-types` | `client/ConfMetaData.kt` | Получить список типов объектов |
| GET | `/ConfMetaData/get-type-attrs` | `client/ConfMetaData.kt` | Получить атрибуты типа |

### Информация об объектах

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| GET | `/ObjectInfo/get-original-attributes` | `client/ObjectInfo.kt` | Получить исходные атрибуты объекта |
| GET | `/ObjectInfo/get-linked-fast` | `client/ObjectInfo.kt` | Получить связи объекта |

### Единицы измерения

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| GET | `/Measure/units-by-designation` | `client/Measure.kt` | Получить единицы по обозначению |

### Конфигурация объектов

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| POST | `/ObjectConfiguration/new-change-group-2` | `client/ObjectConfiguration.kt` | Создать группу замены |
| POST | `/ObjectConfiguration/new-change-variant-2` | `client/ObjectConfiguration.kt` | Создать вариант замены |

### Администрирование

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| GET | `/DbAdministrator/get-activity` | `client/Client.kt` | Получить список активных пользователей |

### Дерево

| Метод | Эндпоинт | Файл | Описание |
|-------|----------|------|----------|
| GET | `/Pdm/get-tree` | `client/Client.kt` | Получить дерево объектов |

## Детальное описание эндпоинтов

### Авторизация

#### POST `/Auth/login`

**Файл:** `client/Login.kt`

**Входные данные:**
```kotlin
data class LoginInputDto(
    val dbName: String,        // Имя базы данных
    val username: String,      // Имя пользователя
    val password: String,      // Пароль
    val rememberMe: Boolean    // Запомнить сессию
)
```

**Выходные данные:**
```kotlin
data class SessionOutputDto(
    val sessionId: String,     // Идентификатор сессии
    // ... другие поля
)
```

**Пример:**
```kotlin
val session = client.login.login(
    userName = "admin",
    userPassword = "password".toCharArray()
)
println("Session ID: ${session.sessionId}")
```

### Чекаут/Чекин

#### GET `/CheckOut/check-out`

**Файл:** `client/CheckOut.kt`

**Параметры:**
- `typeName`: String — тип объекта (например, "ВидИзделия")
- `productName`: String — имя продукта
- `mode`: Int — режим (0 — режим по умолчанию)

**Выходные данные:** String — имя чекаута

**Пример:**
```kotlin
val checkoutName = client.checkout.checkout(
    sessionId = "session123",
    typeName = "ВидИзделия",
    name = "Изделие001"
)
```

#### POST `/CheckOut/check-in-2`

**Файл:** `client/CheckOut.kt`

**Входные данные:**
```kotlin
data class CheckOutInDbInputDto(
    val checkOutName: String,     // Имя чекаута
    val dbName: String,           // Имя базы данных
    val files: List<FileInfoDto>  // Файлы для сохранения
)
```

**Выходные данные:**
```kotlin
data class SaveFilesErrorOutputDto(
    val errors: List<String>?,    // Ошибки сохранения
    val warnings: List<String>?,  // Предупреждения
    val success: Boolean          // Успех операции
)
```

### Объекты

#### POST `/EditObject/new-object`

**Файл:** `client/EditObject.kt`

**Входные данные:**
```kotlin
data class NewObjectInputDto(
    val typeName: String,         // Тип объекта
    val name: String,             // Имя объекта
    val idParent: Int?,           // ID родителя (опционально)
    val folder: Boolean?          // Это папка (опционально)
    // ... другие поля
)
```

**Выходные данные:**
```kotlin
data class IdentifierDto(
    val id: Int                   // ID созданного объекта
)
```

**Пример:**
```kotlin
val objectId = client.editObject.create(
    sessionId = "session123",
    loodsmanObject = NewObjectInputDto(
        typeName = "ВидИзделия",
        name = "Изделие001",
        idParent = 1000,
        folder = false
    )
)
```

#### POST `/EditObject/new-link`

**Файл:** `client/EditObject.kt`

**Входные данные:**
```kotlin
data class NewLinkInputDto(
    val idParent: Int,            // ID родителя
    val idChild: Int,             // ID потомка
    val idTypeLink: Int,          // Тип связи
    val quantity: Double?,        // Количество (опционально)
    val idUnit: Int?,             // Единица измерения (опционально)
    // ... другие поля
)
```

**Выходные данные:** `IdentifierDto` с ID созданной связи

#### POST `/EditObject/up-attr-values-by-ids`

**Файл:** `client/EditObject.kt`

**Входные данные:**
```kotlin
data class UpAttrValuesByIdsInputDto(
    val idObject: Int,            // ID объекта
    val idAttr: Int,              // ID атрибута
    val value: String,            // Значение атрибута
    val idTypeValue: Int?         // Тип значения (опционально)
)
```

**Выходные данные:**
```kotlin
data class UpAttrValuesByIdsOutputDto(
    val idObject: Int,            // ID объекта
    val idAttr: Int,              // ID атрибута
    val result: Boolean           // Результат операции
)
```

### Метаданные

#### GET `/ConfMetaData/get-types`

**Файл:** `client/ConfMetaData.kt`

**Выходные данные:**
```kotlin
data class GetTypesOutputDto(
    val id: Int,                  // ID типа
    val name: String,             // Имя типа
    val idParent: Int?,           // ID родительского типа
    // ... другие поля
)
```

#### GET `/ConfMetaData/get-type-attrs`

**Файл:** `client/ConfMetaData.kt`

**Параметры:**
- `typeId`: Int — ID типа

**Выходные данные:**
```kotlin
data class GetTypeAttrsOutputDto(
    val id: Int,                  // ID атрибута
    val name: String,             // Имя атрибута
    val idType: Int,              // ID типа
    val idTypeAttr: Int?,         // ID типа атрибута
    // ... другие поля
)
```

### Информация об объектах

#### GET `/ObjectInfo/get-original-attributes`

**Файл:** `client/ObjectInfo.kt`

**Параметры:**
- `typeId`: Int — ID типа

**Выходные данные:**
```kotlin
data class GetOriginalAttributesOutputDto(
    val idObject: Int,            // ID объекта
    val idAttr: Int,              // ID атрибута
    val idTypeValue: Int?,        // ID типа значения
    val value: String?,           // Значение
    // ... другие поля
)
```

#### GET `/ObjectInfo/get-linked-fast`

**Файл:** `client/ObjectInfo.kt`

**Параметры:**
- `idVersion`: Int — ID версии объекта
- `linkType`: String — тип связи
- `inverse`: Boolean — инвертировать связь (по умолчанию false)

**Выходные данные:**
```kotlin
data class GetLinkedFastOutputDto(
    val idObject: Int,            // ID объекта
    val idTypeLink: Int,          // Тип связи
    val quantity: Double?,        // Количество
    val idUnit: Int?,             // Единица измерения
    // ... другие поля
)
```

### Единицы измерения

#### GET `/Measure/units-by-designation`

**Файл:** `client/Measure.kt`

**Параметры:**
- `designation`: String — обозначение единицы (например, "шт", "кг")

**Выходные данные:**
```kotlin
data class MeasureUnitOutputDto(
    val id: Int,                  // ID единицы измерения
    val name: String,             // Название
    val designation: String,      // Обозначение
    // ... другие поля
)
```

### Конфигурация объектов

#### POST `/ObjectConfiguration/new-change-group-2`

**Файл:** `client/ObjectConfiguration.kt`

**Входные данные:**
```kotlin
data class NewChangeGroup2InputDto(
    val name: String,             // Имя группы замены
    val description: String?,     // Описание (опционально)
    // ... другие поля
)
```

**Выходные данные:** `IdentifierDto` с ID созданной группы

## Работа с HTTP-клиентом

### Конфигурация клиента

**Файл:** `client/Client.kt`

```kotlin
class Client(
    private val connection: Connection
) {
    // Ограничивает конкурентные HTTP-запросы к Loodsman
    private val requestGate = Semaphore(connection.maxConcurrentRequests)

    private val client: HttpClient = HttpClient(CIO) {
        expectSuccess = true  // Важно для корректной обработки ошибок
        defaultRequest {
            contentType(ContentType.Application.Json)
            url(connection.url)
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }
}
```

### Хелперы для запросов с сессией

**Файл:** `client/Helpers.kt`

```kotlin
suspend inline fun <reified T> HttpClient.getWithSession(
    path: String,
    sessionId: String,
    block: HttpRequestBuilder.() -> Unit = {}
): T {
    return get("$path?sessionId=$sessionId") { block() }.body()
}

suspend inline fun <reified T> HttpClient.postWithSession(
    path: String,
    sessionId: String,
    block: HttpRequestBuilder.() -> Unit = {}
): T {
    return post("$path?sessionId=$sessionId") { block() }.body()
}
```

## Важные нюансы

1. **Ограничение конкурентности:** Все запросы проходят через `requestGate` (Semaphore), который ограничивает количество одновременных запросов к Loodsman.

2. **Обработка ошибок:** В `Client.kt` установлен `expectSuccess = true`, что критично для корректной обработки ошибок Loodsman.

3. **Таймауты:** Запросы имеют таймауты:
   - Запрос: 30 секунд
   - Подключение: 10 секунд

4. **Сериализация:** Используется `kotlinx.serialization` с JSON, все DTO сериализуемы.

## Связанные документы

- [[01-architecture-overview.md]] - Обзор архитектуры
- [[03-external-api-quirks.md]] - Особенности внешнего API
- [[07-tech-stack.md]] - Технологический стек