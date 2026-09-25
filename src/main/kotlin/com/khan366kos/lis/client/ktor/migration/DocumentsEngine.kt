package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.excel.ExcelSaxParser
import com.khan366kos.lis.client.ktor.loodsman.api.dto.FindObjectsSimpleInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.file.Files
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

private data class DocumentRow(
    val objectName: String,
    val networkPath: String,
    val fileName: String,
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

    val rows = ExcelSaxParser().parse(documentsInputStream(), documents.name).toList()
    val headerRow = rows.firstOrNull { it.rowIndex == documents.headersRow }
        ?: throw IllegalStateException(
            "Не найдена строка заголовков (индекс ${documents.headersRow}) на листе '${documents.name}'"
        )
    val headerMap = SheetHeaders.build(headerRow.cells)
    val dataRows = rows.filter { it.rowIndex > documents.headersRow }

    val skippedRows = AtomicInteger(0)
    val documentRows = dataRows.mapNotNull { excelRow ->
        val row = RowView(headerMap, excelRow.cells)
        val objectName = row.value(documents.objectNameColumn)
        val networkPath = row.value(documents.networkPathColumn)
        val fileName = row.value(documents.fileNameColumn)
        if (objectName == null || networkPath == null || fileName == null) {
            skippedRows.incrementAndGet()
            System.err.println(
                "Документы: строка ${excelRow.rowIndex + 1} пропущена — не заполнены обязательные поля"
            )
            return@mapNotNull null
        }
        DocumentRow(objectName, networkPath, fileName)
    }

    if (documentRows.isEmpty()) {
        println("Документы: строк-кандидатов нет, пропускаем")
        return
    }

    val rootFolderId = resolveOrCreateDocumentsRoot()

    val foldersCreated = AtomicInteger(0)
    val documentsCreated = AtomicInteger(0)
    val filesAttached = AtomicInteger(0)
    val filesFailed = AtomicInteger(0)
    val groupsFailed = AtomicInteger(0)

    coroutineScope {
        documentRows.groupBy { it.objectName }.map { (objectName, rowsForObject) ->
            async {
                try {
                    val folderId = loodsmanClient.editObject.create(
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
                            childVersionId = folderId,
                            linkType = DOCUMENT_LINK_TYPE,
                        )
                    )
                    foldersCreated.incrementAndGet()

                    rowsForObject.forEach { row ->
                        try {
                            attachDocument(row, folderId, objectName)
                            documentsCreated.incrementAndGet()
                            filesAttached.incrementAndGet()
                        } catch (e: Exception) {
                            filesFailed.incrementAndGet()
                            System.err.println(
                                "Документы: не удалось прикрепить файл '${row.networkPath}/${row.fileName}' " +
                                    "(объект '${row.objectName}'): ${e.message}"
                            )
                            (e as? ResponseException)?.let {
                                System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    groupsFailed.incrementAndGet()
                    System.err.println(
                        "Документы: не удалось создать папку объекта '$objectName' " +
                            "(${rowsForObject.size} файл(ов) пропущено): ${e.message}"
                    )
                    (e as? ResponseException)?.let {
                        System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                    }
                }
            }
        }.awaitAll()
    }

    println(
        "Документы: строк ${documentRows.size} (пропущено ${skippedRows.get()}), " +
            "папок объектов создано ${foldersCreated.get()} (ошибок ${groupsFailed.get()}), " +
            "документов создано ${documentsCreated.get()}, файлов прикреплено ${filesAttached.get()}, " +
            "ошибок прикрепления файлов ${filesFailed.get()}"
    )
}

private suspend fun MigrationContext.attachDocument(row: DocumentRow, folderId: Int, objectName: String) {
    val documentId = loodsmanClient.editObject.create(
        sessionId,
        NewObjectInputDto(
            typeName = "Бумажный документ",
            stateName = DOCUMENT_STATE,
            keyAttribute = objectName,
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

    // Сетевой путь может быть недоступен (обрыв связи с шарой, файл удалён/переименован) — эта
    // ошибка не должна валить всю группу, "Бумажный документ" в Loodsman уже создан и останется
    // без файла, вызывающий код (runDocumentsMigrationInternal) ловит исключение per-row.
    val filePath = Paths.get(row.networkPath, row.fileName)
    val fileData = Files.readAllBytes(filePath)
    val (createdAt, modifiedAt) = readFsTimestamps(filePath)

    loodsmanClient.file.add(
        sessionId = sessionId,
        idDocument = documentId,
        fileName = row.fileName,
        directory = "",
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        fileData = fileData,
    )
}

private fun readFsTimestamps(path: java.nio.file.Path): Pair<Instant, Instant> {
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
