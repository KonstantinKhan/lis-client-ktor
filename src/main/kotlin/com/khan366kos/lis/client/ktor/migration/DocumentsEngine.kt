package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.excel.ExcelSaxParser
import com.khan366kos.lis.client.ktor.loodsman.api.dto.FindObjectsSimpleInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsInputDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

// Корень "Сканы документов" — тот же паттерн, что "Миграция" в MigrationContext.root, но, в
// отличие от него, СНАЧАЛА ищется через ObjectSearch/find-by-simple-search — по решению
// пользователя может уже существовать от прошлого запуска, а "Миграция" всегда создаётся заново.
private const val DOCUMENTS_ROOT_NAME = "Сканы документов"
private const val FOLDER_STATE = "Папка для чтения"
private const val DOCUMENT_STATE = "Архив"
private const val DOCUMENT_LINK_TYPE = "Состоит из ..."
private const val DOCUMENT_TYPE_ATTRIBUTE = "Тип документа"

// Пустое значение в documentTypeColumn — не повод пропускать строку (в отличие от остальных трёх
// колонок), фолбэк на явное значение по решению пользователя.
private const val DEFAULT_DOCUMENT_TYPE = "Без типа"

private data class DocumentRow(
    val objectName: String,
    val documentType: String,
    val networkPath: String,
    val fileName: String,
)

// Файл прочитан и провалидирован ДО каких-либо записей в Loodsman — см. runDocumentsMigrationInternal:
// папка/документ/связь для строки с недоступным файлом не создаются вообще.
// Не data class — ByteArray-поле не сравнивается/не хешируется нигде в коде, а structural
// equals()/hashCode() по массиву байт data class бы всё равно не сгенерировал корректно.
private class ValidatedDocumentFile(
    val row: DocumentRow,
    val fileData: ByteArray,
    val createdAt: Instant,
    val modifiedAt: Instant,
)

suspend fun MigrationContext.runDocumentsMigration() {
    try {
        runDocumentsMigrationInternal()
    } catch (e: ResponseException) {
        System.err.println("HTTP ${e.response.status.value}: ${e.response.bodyAsText()}")
        throw e
    }
}

private suspend fun MigrationContext.runDocumentsMigrationInternal() {
    val documents = settings.mapping.documentsSheet
    if (documents.name.isBlank()) {
        println("Документы: не настроено, пропускаем")
        return
    }

    println("Парсинг Excel: лист '${documents.name}'...")
    val rows = ExcelSaxParser().parse(documentsInputStream(), documents.name).toList()
    println("  загружено строк: ${rows.size}")

    val headerRow = rows.firstOrNull { it.rowIndex == documents.headersRow }
        ?: throw IllegalStateException(
            "Не найдена строка заголовков (индекс ${documents.headersRow}) на листе '${documents.name}'"
        )
    println("  найдена строка заголовков (индекс ${documents.headersRow})")

    val headerMap = SheetHeaders.build(headerRow.cells)
    println("  построена карта полей (${headerMap.size} полей)")

    val dataRows = rows.filter { it.rowIndex > documents.headersRow }
    println("  отфильтровано строк для обработки: ${dataRows.size}")

    val skippedRows = AtomicInteger(0)
    val documentRows = dataRows.mapNotNull { excelRow ->
        val row = RowView(headerMap, excelRow.cells)
        val objectName = row.value(documents.objectNameColumn)
        val networkPath = row.value(documents.networkPathColumn)
        val fileName = row.value(documents.fileNameColumn)
        val documentType = row.value(documents.documentTypeColumn) ?: DEFAULT_DOCUMENT_TYPE
        if (objectName == null || networkPath == null || fileName == null) {
            skippedRows.incrementAndGet()
            System.err.println(
                "Документы: строка ${excelRow.rowIndex + 1} пропущена — не заполнены обязательные поля"
            )
            return@mapNotNull null
        }
        DocumentRow(objectName, documentType, networkPath, fileName)
    }

    if (documentRows.isEmpty()) {
        println("Документы: строк-кандидатов нет, пропускаем")
        return
    }

    // Фаза 1: читаем и валидируем файлы ДО любых обращений к Loodsman — если файла нет или он не
    // читается, для этой строки не создаётся ни папка объекта, ни "Бумажный документ", ни связь
    // (по решению пользователя — "не нужно создавать всё, что перед ним").
    val readFailed = AtomicInteger(0)
    val validatedFiles = documentRows.mapNotNull { row ->
        val filePath = Paths.get(row.networkPath, row.fileName)
        runCatching {
            val fileData = Files.readAllBytes(filePath)
            val (createdAt, modifiedAt) = readFsTimestamps(filePath)
            ValidatedDocumentFile(row, fileData, createdAt, modifiedAt)
        }.onFailure { e ->
            readFailed.incrementAndGet()
            System.err.println(
                "Документы: файл '$filePath' недоступен (объект '${row.objectName}', тип " +
                    "'${row.documentType}') — папка/документ/связь не создаются: ${e.message}"
            )
        }.getOrNull()
    }

    if (validatedFiles.isEmpty()) {
        println("Документы: ни один файл не прочитан (ошибок чтения ${readFailed.get()}), создавать нечего")
        return
    }

    val rootFolderId = resolveOrCreateDocumentsRoot()

    val foldersCreated = AtomicInteger(0)
    val documentsCreated = AtomicInteger(0)
    val filesAttached = AtomicInteger(0)
    val attachFailed = AtomicInteger(0)
    val objectGroupsFailed = AtomicInteger(0)
    val typeGroupsFailed = AtomicInteger(0)

    coroutineScope {
        validatedFiles.groupBy { it.row.objectName }.map { (objectName, filesForObject) ->
            async {
                val folderId = try {
                    val id = loodsmanClient.editObject.create(
                        sessionId,
                        NewObjectInputDto(
                            typeName = "Папка",
                            stateName = FOLDER_STATE,
                            keyAttribute = objectName,
                            isProject = false,
                        )
                    ).asInt()
                    loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = rootFolderId,
                            childVersionId = id,
                            linkType = DOCUMENT_LINK_TYPE,
                        )
                    )
                    foldersCreated.incrementAndGet()
                    id
                } catch (e: Exception) {
                    objectGroupsFailed.incrementAndGet()
                    System.err.println(
                        "Документы: не удалось создать папку объекта '$objectName' " +
                            "(${filesForObject.size} файл(ов) пропущено): ${e.message}"
                    )
                    (e as? ResponseException)?.let {
                        System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                    }
                    return@async
                }

                // Один "Бумажный документ" на пару (объект, тип) — несколько файлов одного типа
                // прикрепляются к одному и тому же объекту через несколько вызовов File/add,
                // вместо создания дублей документа на каждую строку.
                filesForObject.groupBy { it.row.documentType }.forEach { (documentType, filesForType) ->
                    try {
                        val keyAttribute = "$objectName - $documentType"
                        val documentId = loodsmanClient.editObject.create(
                            sessionId,
                            NewObjectInputDto(
                                typeName = "Бумажный документ",
                                stateName = DOCUMENT_STATE,
                                keyAttribute = keyAttribute,
                                isProject = false,
                            )
                        ).asInt()
                        loodsmanClient.editObject.newLink(
                            sessionId,
                            NewLinkInputDto(
                                parentVersionId = folderId,
                                childVersionId = documentId,
                                linkType = DOCUMENT_LINK_TYPE,
                            )
                        )
                        loodsmanClient.editObject.setValues(
                            sessionId,
                            listOf(
                                UpAttrValuesByIdsInputDto(
                                    versionId = documentId,
                                    attributeName = DOCUMENT_TYPE_ATTRIBUTE,
                                    attributeValue = documentType,
                                )
                            )
                        ).filterNot { it.isSuccess }.forEach {
                            System.err.println(
                                "Документы: не удалось проставить атрибут '$DOCUMENT_TYPE_ATTRIBUTE' " +
                                    "на документ $documentId: ${it.errorMessage}"
                            )
                        }
                        documentsCreated.incrementAndGet()

                        filesForType.forEach { validated ->
                            try {
                                loodsmanClient.file.add(
                                    sessionId = sessionId,
                                    idDocument = documentId,
                                    fileName = validated.row.fileName,
                                    directory = "",
                                    createdAt = validated.createdAt,
                                    modifiedAt = validated.modifiedAt,
                                    fileData = validated.fileData,
                                )
                                filesAttached.incrementAndGet()
                            } catch (e: Exception) {
                                attachFailed.incrementAndGet()
                                System.err.println(
                                    "Документы: не удалось прикрепить файл '${validated.row.networkPath}/" +
                                        "${validated.row.fileName}' к документу $documentId: ${e.message}"
                                )
                                (e as? ResponseException)?.let {
                                    System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        typeGroupsFailed.incrementAndGet()
                        System.err.println(
                            "Документы: не удалось создать документ '$objectName - $documentType' " +
                                "(${filesForType.size} файл(ов) пропущено): ${e.message}"
                        )
                        (e as? ResponseException)?.let {
                            System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                        }
                    }
                }
            }
        }.awaitAll()
    }

    println(
        "Документы: строк ${documentRows.size} (пропущено ${skippedRows.get()}, файлов не прочитано " +
            "${readFailed.get()}), папок объектов создано ${foldersCreated.get()} (ошибок ${objectGroupsFailed.get()}), " +
            "документов создано ${documentsCreated.get()} (ошибок ${typeGroupsFailed.get()}), " +
            "файлов прикреплено ${filesAttached.get()}, ошибок прикрепления ${attachFailed.get()}"
    )
}

private fun readFsTimestamps(path: Path): Pair<Instant, Instant> {
    val now = Instant.now()
    return runCatching {
        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        attrs.creationTime().toInstant() to attrs.lastModifiedTime().toInstant()
    }.getOrDefault(now to now)
}

private suspend fun MigrationContext.resolveOrCreateDocumentsRoot(): Int {
    val found = loodsmanClient.objectSearch.findBySimpleSearch(
        sessionId,
        FindObjectsSimpleInputDto(
            searchText = DOCUMENTS_ROOT_NAME,
            searchVariant = 0,
            needSearchByKeyAttribute = true,
            types = listOf("Папка"),
        )
    ).firstOrNull { it.name == DOCUMENTS_ROOT_NAME }

    if (found != null) {
        return found.id
    }

    return loodsmanClient.editObject.create(
        sessionId,
        NewObjectInputDto(
            typeName = "Папка",
            stateName = FOLDER_STATE,
            keyAttribute = DOCUMENTS_ROOT_NAME,
            isProject = true,
        )
    ).asInt()
}

private fun MigrationContext.documentsInputStream() =
    settings.mapping.documentsSheet.source?.let { File(it.path).inputStream() } ?: excelInputStream()
