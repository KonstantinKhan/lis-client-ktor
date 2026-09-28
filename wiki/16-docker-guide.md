# Docker и Docker Compose — полный гайд

##概念: Volume как мостик к файловой системе хоста

**Volume** — это туннель между файловой системой хоста и контейнера:

```yaml
volumes:
  - ./local/path/file.xlsx:/container/path/file.xlsx:ro
    ↑                      ↑                           ↑
    хост (Windows/Mac)     контейнер (Linux)    read-only
```

Контейнер видит файл по пути `/container/path/file.xlsx` и может его читать, как свой собственный. На самом деле это ссылка на хост.

**Без volume:** контейнер изолирован, файлы хоста не видит.

---

## Структура проекта

```
lis-client-ktor/
├── Dockerfile          # многоступенчатая сборка
├── docker-compose.yml  # оркестрация контейнера
├── settings.json       # конфиг приложения
├── data/
│   ├── data.xlsx       # исходный Excel для объектов/связей
│   └── documents.xlsx  # (опционально) Excel со сканами
└── documents/          # (опционально) папка с файлами сканов
    └── *.pdf, *.jpg, ...
```

---

## Офлайн-развёртывание (сервер без интернета и исходников)

`docker-compose.yml` использует `image:` (не `build:`) — на целевом хосте исходники не нужны.

На машине с интернетом:
```bash
docker build -t bom-migration:latest .
docker save -o bom-migration.tar bom-migration:latest
```

Перенести на сервер: `bom-migration.tar`, `settings.json`, `data/*.xlsx`, `documents/`, `docker-compose.yml`, `.env`.

На сервере:
```bash
docker load -i bom-migration.tar
docker compose up
```

Базовые образы и Gradle-зависимости уже внутри tar — отдельно не переносятся.

---

## Сценарий 1: Базовый запуск (Excel в локальной папке)

**Структура файлов на хосте:**
```
C:\migration\
├── settings.json
├── data\
│   └── data.xlsx
└── docker-compose.yml
```

**settings.json:**
```json
{
  "connection": { "url": "http://loodsman:8076/api/v4/", ... },
  "polynom": { "url": "http://polynom:5100/", ... },
  "mapping": {
    "source": {
      "type": "xlsx",
      "path": "/data/data.xlsx"  // путь ВНУ­ТРИ контейнера
    },
    "objectsSheet": { "name": "Объекты", "headersRowIndex": 1 },
    "linksSheet": { "name": "Связи", "headersRowIndex": 1 }
  }
}
```

**docker-compose.yml:**
```yaml
services:
  bom-migration:
    image: bom-migration:latest
    stdin_open: true
    tty: true
    volumes:
      - ${SETTINGS_FILE:-./settings.json}:/data/settings.json:ro
      - ${EXCEL_FILE:-./data/data.xlsx}:/data/data.xlsx:ro
      # - ${DOCUMENTS_EXCEL:-./data/documents.xlsx}:/data/documents.xlsx:ro   # если используется отдельный Excel со сканами
      # - ${DOCUMENTS_FOLDER:-./documents}:/data/documents:ro                 # если сканы лежат в папке (см. Сценарий 3)
      - ${LOGS_DIR:-./logs}:/data/logs                                        # логи приложения (logs/app.log)
```

**Запуск:**
```bash
docker compose up
```

---

## Сценарий 2: DocumentsSheet в отдельном Excel файле

Если сканы документов лежат в отдельном файле `documents.xlsx`:

**Структура:**
```
C:\migration\
├── settings.json
├── data\
│   ├── data.xlsx       # объекты + связи
│   └── documents.xlsx  # сканы
└── docker-compose.yml
```

**settings.json:**
```json
{
  "mapping": {
    "source": { "path": "/data/data.xlsx" },
    "documentsSheet": {
      "name": "Сканы",
      "headersRowIndex": 1,
      "objectNameColumn": "объект",
      "documentTypeColumn": "тип_документа",
      "networkPathColumn": "папка",
      "fileNameColumn": "файл",
      "source": {
        "type": "xlsx",
        "path": "/data/documents.xlsx"  // отдельный файл
      }
    }
  }
}
```

**docker-compose.yml:**
```yaml
services:
  bom-migration:
    image: bom-migration:latest
    stdin_open: true
    tty: true
    volumes:
      - ${SETTINGS_FILE:-./settings.json}:/data/settings.json:ro
      - ${EXCEL_FILE:-./data/data.xlsx}:/data/data.xlsx:ro
      - ${DOCUMENTS_EXCEL:-./data/documents.xlsx}:/data/documents.xlsx:ro
```

---

## Сценарий 3: DocumentsSheet с файлами на диске (Excel уже хранит контейнерный путь)

Сканы документов хранятся на диске. `DocumentsEngine.kt` читает файл напрямую по пути
из колонки "Расположение файла" (`Paths.get(networkPath, fileName)`).

**Структура:**
```
C:\migration\
├── settings.json
├── data\
│   └── data.xlsx
├── documents\          # папка с PDF/JPG/etc
│   ├── doc001.pdf
│   ├── doc002.pdf
│   └── ...
└── docker-compose.yml
```

**settings.json:**
```json
{
  "mapping": {
    "source": { "path": "/data/data.xlsx" },
    "documentsSheet": {
      "name": "Сканы",
      "headersRowIndex": 1,
      "objectNameColumn": "объект",
      "documentTypeColumn": "тип",
      "networkPathColumn": "папка",      // содержит /data/documents/
      "fileNameColumn": "файл",          // содержит имя файла
      "source": null                     // читаем тот же data.xlsx
    }
  }
}
```

**Excel содержит:**
```
| объект | тип        | папка            | файл      |
|--------|------------|------------------|-----------|
| ОБ-001 | Чертёж     | /data/documents/ | doc001.pdf|
| ОБ-002 | Спецификация | /data/documents/ | doc002.pdf|
```

**docker-compose.yml:**
```yaml
services:
  bom-migration:
    image: bom-migration:latest
    stdin_open: true
    tty: true
    volumes:
      - ${SETTINGS_FILE:-./settings.json}:/data/settings.json:ro
      - ${EXCEL_FILE:-./data/data.xlsx}:/data/data.xlsx:ro
      - ${DOCUMENTS_FOLDER:-./documents}:/data/documents:ro     # монтируем папку со сканами
```

Работает, только если сам Excel уже содержит контейнерный путь `/data/documents/...` —
чаще так не бывает (реальные данные приходят с готовыми виндовыми путями до шары). Для
этого случая — Сценарий 4.

---

## Сценарий 4: Сетевая шара с реальными виндовыми путями в Excel (`pathRewrite`)

Частый реальный случай: Excel формируется другой системой и уже содержит боевой виндовый
путь до шары (UNC `\\server\docs\...` или буква диска `Z:\docs\...`) — трогать эти значения
нельзя/не нужно, они одинаковые что на тесте, что на проде. Контейнер (Linux) такой путь
понять не может — нужны два независимых шага:

**1. Физическое монтирование шары на хост Docker** — контейнер должен реально видеть файлы
на файловой системе. Способ зависит от хоста:
- Linux-хост Docker: cifs-mount шары куда-то в файловую систему хоста, например `/mnt/docs`
- Docker Desktop (Windows): шара доступна как маппированный диск/UNC, Docker Desktop сам
  транслирует такой хостовый путь при монтировании volume

Хостовый путь из этого шага — в `.env`:
```bash
DOCUMENTS_FOLDER=/mnt/docs
```

Контейнерный путь монтирования — **фиксированный**, как в Сценарии 3:
```yaml
- ${DOCUMENTS_FOLDER:-./documents}:/data/documents:ro
```

**2. Перевод пути в `settings.json`** — `mapping.documentsSheet.pathRewrite` меняет префикс
виндового пути из Excel на фактическую точку монтирования из шага 1:
```json
{
  "mapping": {
    "documentsSheet": {
      "networkPathColumn": "Расположение файла",
      "fileNameColumn": "Имя документа",
      "pathRewrite": {
        "from": "\\\\server\\docs",
        "to": "/data/documents"
      }
    }
  }
}
```
`from` — ровно то, с чего реально начинаются значения колонки "Расположение файла" в вашем
Excel (без учёта регистра). `to` — всегда правая часть volume из шага 1 (`/data/documents`).
Строки, которые с `from` не совпадают, читаются как есть, без изменений. `pathRewrite` не
задан вовсе (дефолт) — поведение как в Сценарии 3, никакой подмены.

**Excel (не переписывается, реальный путь до шары):**
```
| объект | папка              | файл   |
|--------|--------------------|--------|
| ОБ-001 | \\server\docs      | scan1.pdf|
```

`\\server\docs\scan1.pdf` → (шаг 2, `pathRewrite`) → `/data/documents/scan1.pdf` →
(шаг 1, реальное монтирование шары туда) → файл читается.

---

## Сценарий 5: Несколько источников документов

Документы лежат в разных папках/серверах — `pathRewrite` (Сценарий 4) поддерживает ровно
одну пару `from`/`to`, для нескольких разных корней путей в Excel этого не хватит. Тогда
либо монтируете каждый источник отдельно и переписываете Excel под контейнерные пути (как
раньше, вручную), либо это повод расширить `pathRewrite` до списка правил — не делали,
конкретной необходимости пока не было.

**docker-compose.yml:**
```yaml
volumes:
  - ${SETTINGS_FILE:-./settings.json}:/data/settings.json:ro
  - ${EXCEL_FILE:-./data/data.xlsx}:/data/data.xlsx:ro
  - Z:/documents:/data/server1/docs:ro     # первый источник
  - Y:/scans:/data/server2/scans:ro        # второй источник
  - ./local-docs:/data/local/docs:ro       # локальная папка
```

**Excel содержит полные пути внутри контейнера:**
```
| объект | папка                  | файл   |
|--------|------------------------|--------|
| ОБ-001 | /data/server1/docs/    | scan.pdf|
| ОБ-002 | /data/server2/scans/   | doc.pdf |
| ОБ-003 | /data/local/docs/      | note.pdf|
```

---

## Использование .env для хостовых путей

Чтобы не редактировать `docker-compose.yml` на каждом хосте, используйте `.env`:

**.env.example** (коммитится в проект):
```bash
SETTINGS_FILE=./settings.json
EXCEL_FILE=./data/data.xlsx
DOCUMENTS_EXCEL=./data/documents.xlsx
DOCUMENTS_FOLDER=./documents
LOGS_DIR=./logs
```

**docker-compose.yml:**
```yaml
volumes:
  - ${SETTINGS_FILE}:/data/settings.json:ro
  - ${EXCEL_FILE}:/data/data.xlsx:ro
  - ${DOCUMENTS_EXCEL}:/data/documents.xlsx:ro
  - ${DOCUMENTS_FOLDER}:/data/documents:ro
  - ${LOGS_DIR}:/data/logs
```

`DOCUMENTS_FOLDER` — хостовая точка, где реально доступна шара/папка со сканами (Сценарий 4:
`/mnt/docs` на Linux-хосте, маппированный диск на Docker Desktop). Контейнерный путь всегда
фиксирован (`/data/documents`) — если в Excel лежит реальный виндовый путь, а не этот
контейнерный, нужен `pathRewrite` в `settings.json` (Сценарий 4).

**На каждом хосте:**
```bash
# Windows PowerShell
$env:SETTINGS_FILE="C:\migration\settings.json"
$env:EXCEL_FILE="C:\data\data.xlsx"
$env:DOCUMENTS_FOLDER="C:\documents"

# Linux/Mac .bash_profile или .zshrc
export SETTINGS_FILE=./settings.json
export EXCEL_FILE=./data/data.xlsx
export DOCUMENTS_FOLDER=./documents

docker compose up
```

Или создать локальный `.env`:
```bash
cp .env.example .env
# отредактировать .env с хостовыми путями
docker compose up
```

---

## Важные моменты

### 1. Path в settings.json — всегда пути ВНУ­ТРИ контейнера

```json
{
  "mapping": {
    "source": {
      "path": "/data/data.xlsx"   // ВНУ­ТРИ контейнера, не C:\...
    }
  }
}
```

### 2. Путь к Excel берётся из settings.json

```json
{
  "mapping": {
    "source": {
      "path": "/data/data.xlsx"   // путь ВНУТРИ контейнера — куда смонтирован EXCEL_FILE
    }
  }
}
```

Переменная окружения `LIS_EXCEL_PATH` в compose НЕ задаётся. В коде она существует
как опциональный override (MigrationEngine.excelInputStream()) — можно подменить файл
без правки settings.json, но по умолчанию используется путь из settings.json.

### 3. `:ro` (read-only) безопаснее

```yaml
- ./settings.json:/data/settings.json:ro  # контейнер не может менять файл
```

Без `:ro` контейнер может менять файл на хосте.

### 4. Контейнер НЕ видит Windows пути напрямую

**Не работает:**
```yaml
volumes:
  - C:\Users\khan\data.xlsx:/data/data.xlsx
```

**Работает:**
```yaml
volumes:
  - ./data/data.xlsx:/data/data.xlsx      # относительный путь
  - /c/Users/khan/data/data.xlsx:/data/data.xlsx  # WSL путь
```

### 5. Логин/пароль вводятся в консоль, не в .env

```bash
docker compose up  # без -d
# Контейнер запустится, в консоль выведется "Введите логин:"
# Вы вводите логин/пароль прямо в терминал
```

### 6. Логи (logs/app.log) — без volume теряются при пересоздании контейнера

`WORKDIR /data` внутри контейнера, `logback.xml` пишет `logs/app.log` относительно рабочей
директории → фактически `/data/logs/app.log`. `/data` целиком не монтируется (монтируются
только отдельные файлы), поэтому без отдельного volume `logs/` живёт только в writable-слое
контейнера и исчезает при `docker compose down`/пересоздании.

```yaml
volumes:
  - ${LOGS_DIR:-./logs}:/data/logs
```

После этого лог на хосте: `./logs/app.log` (путь настраивается через `LOGS_DIR` в `.env`).
Ротация (см. `src/main/resources/logback.xml`): по размеру (10MB) и дате, хранится 14 дней,
общий лимит 200MB.

### 7. Сканы с реальным виндовым путём в Excel — два независимых шага (Сценарий 4)

Оба обязательны, один без другого не работает:
1. Шара физически смонтирована туда, куда указывает `DOCUMENTS_FOLDER` (иначе файлов там
   просто нет — `pathRewrite` не поможет, читать нечего).
2. `mapping.documentsSheet.pathRewrite` в `settings.json` — переводит виндовый путь из Excel
   (`\\server\docs\...`) в контейнерный (`/data/documents/...`), потому что `Paths.get()`
   внутри Linux-контейнера UNC/буквы дисков не понимает вообще.

---

## Чек-лист перед первым запуском

- [ ] `settings.json` существует и содержит корректный URL Loodsman/ПОЛИНОМ
- [ ] Пути в `settings.json` — это пути ВНУ­ТРИ контейнера (`/data/...`)
- [ ] `docker-compose.yml` содержит volume-ы для всех файлов
- [ ] Есть volume для `logs/` (`${LOGS_DIR:-./logs}:/data/logs`) — иначе логи теряются при пересоздании контейнера
- [ ] Excel файлы существуют на хосте
- [ ] Если используются документы — папка/файлы документов существуют, шара реально
      смонтирована на хосте Docker (`DOCUMENTS_FOLDER`)
- [ ] Если в Excel-колонке "Расположение файла" реальный виндовый путь (не `/data/documents/...`) —
      задан `mapping.documentsSheet.pathRewrite` в `settings.json` (Сценарий 4)
- [ ] Образ `bom-migration` загружен на хост (`docker load`) или собран локально
- [ ] Запускаете без флага `-d` (чтобы видеть консоль для ввода логина)

---

## Отладка

**Ошибка: "File not found: /data/data.xlsx"**
- Проверьте что volume в `docker-compose.yml` корректен
- Проверьте что файл существует на хосте

**Ошибка: "Нет консоли"**
- Запускайте без `-d`: `docker compose up`
- Убедитесь что `stdin_open: true` и `tty: true` в compose

**Документы не загружаются (в логе `DocumentsEngine - ... недоступен`)**
- `DocumentsEngine.kt` читает путь по колонке "Расположение файла" (после `pathRewrite`,
  если он задан) — итоговый путь должен существовать ВНУТРИ контейнера, а не только на хосте
- Если в Excel реальный виндовый путь (`\\server\...`, `Z:\...`) — без `pathRewrite` в
  `settings.json` он и не может резолвиться, контейнер такой синтаксис не понимает (Сценарий 4)
- Если `pathRewrite` уже настроен, но всё равно недоступен — проверьте, что `from` в
  `settings.json` совпадает с реальным началом значений колонки (регистр не важен, а вот
  лишний/недостающий слэш в конце — важен) и что шара физически смонтирована в `DOCUMENTS_FOLDER`
  на хосте (шаг 1 из Сценария 4 — без него `pathRewrite` подставит правильный путь к
  несуществующему файлу)
- Проверьте что папка смонтирована с `:ro` (read-only)

---

## Переход между хостами

1. Загрузите образ на новый хост: `docker load -i bom-migration.tar` (исходники не нужны)
2. Отредактируйте `settings.json` — URL, mapping
3. Положите Excel файлы в нужные папки
4. Отредактируйте `docker-compose.yml` — volume пути если нужно
5. `docker compose up`

Если использовать `.env` — достаточно обновить только его, `docker-compose.yml` не трогать.
