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
  lis-client:
    build: .
    stdin_open: true
    tty: true
    volumes:
      - ./settings.json:/data/settings.json:ro
      - ./data/data.xlsx:/data/data.xlsx:ro
    environment:
      - LIS_EXCEL_PATH=/data/data.xlsx
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
  lis-client:
    build: .
    stdin_open: true
    tty: true
    volumes:
      - ./settings.json:/data/settings.json:ro
      - ./data/data.xlsx:/data/data.xlsx:ro
      - ./data/documents.xlsx:/data/documents.xlsx:ro
    environment:
      - LIS_EXCEL_PATH=/data/data.xlsx
```

---

## Сценарий 3: DocumentsSheet с файлами на диске

Сканы документов хранятся на диске (локально или сетевой путь):

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
  lis-client:
    build: .
    stdin_open: true
    tty: true
    volumes:
      - ./settings.json:/data/settings.json:ro
      - ./data/data.xlsx:/data/data.xlsx:ro
      - ./documents:/data/documents:ro     # монтируем папку со сканами
    environment:
      - LIS_EXCEL_PATH=/data/data.xlsx
```

---

## Сценарий 4: Сетевой путь (SMB/NFS)

Документы лежат на сетевом сервере (типа `\\server\docs`):

**Хост (Windows):** файлы доступны как `Z:\documents\` (маппированный диск) или `\\192.168.1.100\docs`

**docker-compose.yml:**
```yaml
services:
  lis-client:
    build: .
    stdin_open: true
    tty: true
    volumes:
      - ./settings.json:/data/settings.json:ro
      - ./data/data.xlsx:/data/data.xlsx:ro
      - Z:/documents:/data/documents:ro   # маппированный диск Windows
    environment:
      - LIS_EXCEL_PATH=/data/data.xlsx
```

**settings.json:**
```json
{
  "mapping": {
    "documentsSheet": {
      "networkPathColumn": "папка",
      "fileNameColumn": "файл"
    }
  }
}
```

**Excel:**
```
| объект | папка           | файл   |
|--------|-----------------|--------|
| ОБ-001 | /data/documents/| scan1.pdf|
```

---

## Сценарий 5: Несколько источников документов

Документы лежат в разных папках/серверах:

**docker-compose.yml:**
```yaml
volumes:
  - ./settings.json:/data/settings.json:ro
  - ./data/data.xlsx:/data/data.xlsx:ro
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
DOCUMENTS_FOLDER=./documents
```

**docker-compose.yml:**
```yaml
volumes:
  - ${SETTINGS_FILE}:/data/settings.json:ro
  - ${EXCEL_FILE}:/data/data.xlsx:ro
  - ${DOCUMENTS_FOLDER}:/data/documents:ro
```

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

### 2. `LIS_EXCEL_PATH` переопределяет путь из settings.json

```yaml
environment:
  - LIS_EXCEL_PATH=/data/data.xlsx
```

Если эта переменная задана, приложение игнорирует `settings.mapping.source.path`.

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

---

## Чек-лист перед первым запуском

- [ ] `settings.json` существует и содержит корректный URL Loodsman/ПОЛИНОМ
- [ ] Пути в `settings.json` — это пути ВНУ­ТРИ контейнера (`/data/...`)
- [ ] `docker-compose.yml` содержит volume-ы для всех файлов
- [ ] Excel файлы существуют на хосте
- [ ] Если используются документы — папка/файлы документов существуют
- [ ] Запускаете без флага `-d` (чтобы видеть консоль для ввода логина)

---

## Отладка

**Ошибка: "File not found: /data/data.xlsx"**
- Проверьте что volume в `docker-compose.yml` корректен
- Проверьте что файл существует на хосте

**Ошибка: "Нет консоли"**
- Запускайте без `-d`: `docker compose up`
- Убедитесь что `stdin_open: true` и `tty: true` в compose

**Документы не загружаются**
- Проверьте пути в Excel — должны совпадать с mountpoint контейнера
- Проверьте что папки смонтированы с `:ro` (read-only)

**Stdin не работает (Windows): "Нажимаю Enter — ок, ввожу символы — выбрасывает на хост"**
- На Windows Docker может иметь проблемы с forwarding stdin в интерактивный режим
- **Решение:** запусти через bash контейнера:
  ```bash
  docker compose run --rm lis-client bash
  ```
  Потом внутри контейнера:
  ```bash
  /opt/lis-client-ktor/bin/lis-client-ktor
  ```
  Stdin будет работать стабильнее, потому что идет через bash контейнера
- Альтернатива: используй `cmd` вместо PowerShell: `cmd` → `docker compose up`

---

## Переход между хостами

1. Скопируйте весь проект на новый хост
2. Отредактируйте `settings.json` — URL, mapping
3. Положите Excel файлы в нужные папки
4. Отредактируйте `docker-compose.yml` — volume пути если нужно
5. `docker compose up`

Если использовать `.env` — достаточно обновить только его, `docker-compose.yml` не трогать.
