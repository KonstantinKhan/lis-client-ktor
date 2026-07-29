package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Identifier
import com.khan366kos.lis.client.ktor.domain.MappingElement
import com.khan366kos.lis.client.ktor.domain.MaterialCandidate
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.excel.ExcelSaxParser
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsInputDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// Реальная конкурентность к Loodsman ограничена только Client.requestGate (Semaphore).
// Здесь строки обрабатываются все сразу: локальная работа (матчинг правил, сборка DTO) дешёвая,
// корутины большую часть времени просто ждут permit — это и есть единственная точка троттлинга.
suspend fun MigrationContext.runObjectsMigration() {
    val objectsSheet = settings.mapping.objectsSheet
    val rows = ExcelSaxParser().parse(excelInputStream(), objectsSheet.name).toList()
    val headerRow = rows.firstOrNull { it.rowIndex == objectsSheet.headersRow }
        ?: throw IllegalStateException(
            "Не найдена строка заголовков (индекс ${objectsSheet.headersRow}) на листе '${objectsSheet.name}'"
        )
    val headerMap = SheetHeaders.build(headerRow.cells)
    val dataRows = rows.filter { it.rowIndex > objectsSheet.headersRow }

    val objectsCreated = AtomicInteger(0)
    val rowsFailed = AtomicInteger(0)

    val results = coroutineScope {
        dataRows.map { excelRow ->
            async {
                processObjectRow(RowView(headerMap, excelRow.cells), objectsCreated, rowsFailed)
            }
        }.awaitAll()
    }

    // Слияние в общие списки идёт последовательно, после awaitAll() — сами row-корутины
    // ничего не пишут в identifiers/materialCandidates напрямую, чтобы не гонять запись в
    // MutableList из нескольких потоков одновременно.
    val created = results.flatMap { it.identifiers }
    identifiers.addAll(created)
    materialCandidates.addAll(results.flatMap { it.materialCandidates })

    println(
        "Объекты: строк ${dataRows.size}, создано объектов ${objectsCreated.get()}, " +
            "с привязкой к классификатору ${created.size}, ошибок ${rowsFailed.get()}"
    )
}

suspend fun MigrationContext.runLinksMigration() {
    val linksSheet = settings.mapping.linksSheet
    val rows = ExcelSaxParser().parse(excelInputStream(), linksSheet.name).toList()
    val headerRow = rows.firstOrNull { it.rowIndex == linksSheet.headersRow }
        ?: throw IllegalStateException(
            "Не найдена строка заголовков (индекс ${linksSheet.headersRow}) на листе '${linksSheet.name}'"
        )
    val headerMap = SheetHeaders.build(headerRow.cells)
    val dataRows = rows.filter { it.rowIndex > linksSheet.headersRow }

    val linksFailed = AtomicInteger(0)

    // childId -> quantity; quantity == null значит колонка количества сконфигурирована, но
    // значение в строке не прочиталось — такая пара позже отбрасывается, а не линкуется с 1.0.
    val classifierLinks = mutableMapOf<Long, MutableMap<Long, Double?>>()
    dataRows.forEach { row ->
        val parentId = row.cells.getOrNull(linksSheet.parentColumn)?.trim()?.toLongOrNull() ?: return@forEach
        val childId = row.cells.getOrNull(linksSheet.childColumn)?.trim()?.toLongOrNull() ?: return@forEach
        val quantity = resolveLinkQuantity(linksSheet.quantityColumn, RowView(headerMap, row.cells))
        classifierLinks.getOrPut(parentId) { mutableMapOf() }[childId] = quantity
    }

    val elementsByClassifierId = identifiers.groupBy { it.classifierId }

    val linkPairs = classifierLinks.flatMap { (parentClassifierId, children) ->
        val parents = elementsByClassifierId[parentClassifierId] ?: return@flatMap emptyList()
        children.flatMap { (childClassifierId, quantity) ->
            if (quantity == null) {
                linksFailed.incrementAndGet()
                System.err.println(
                    "Связь ($parentClassifierId -> $childClassifierId) пропущена: " +
                        "не удалось прочитать '${linksSheet.quantityColumn}'"
                )
                return@flatMap emptyList()
            }
            val children2 = elementsByClassifierId[childClassifierId] ?: return@flatMap emptyList()
            parents.flatMap { parent -> children2.map { child -> Triple(parent, child, quantity) } }
        }
    }

    val linksCreated = coroutineScope {
        linkPairs.map { (parent, child, quantity) ->
            async { linkObjects(parent.loodsmanId, child.loodsmanId, linksSheet.linkType, linksFailed, quantity) }
        }.awaitAll()
    }.count { it }

    println("Связи: строк ${dataRows.size}, пар для линковки ${linkPairs.size}, создано $linksCreated, ошибок ${linksFailed.get()}")
}

private data class ObjectRowResult(
    val identifiers: List<Identifier>,
    val materialCandidates: List<MaterialCandidate>,
)

private suspend fun MigrationContext.processObjectRow(
    row: RowView,
    objectsCreated: AtomicInteger,
    rowsFailed: AtomicInteger
): ObjectRowResult {
    val matches = settings.mapping.types.filter { ConditionsEvaluator.matches(it.conditions, row) }
    if (matches.isEmpty()) return ObjectRowResult(emptyList(), emptyList())

    val createdObjects = matches.mapNotNull { mappingElement ->
        val keyAttr = row.value(mappingElement.source) ?: return@mapNotNull null
        val loodsmanId = createLoodsmanObject(mappingElement, keyAttr, row, objectsCreated, rowsFailed)
            ?: return@mapNotNull null
        mappingElement to loodsmanId
    }

    // Несколько правил могут совпасть с одной строкой (например, Папка + головная Сборочная
    // единица с тем же обозначением) — не-папочные объекты этой строки вешаются на папку той
    // же строки, а не на root. Папка при этом не участвует в BOM-структуре (см. identifiersForRow
    // ниже) — иначе её classifierId коллизирует с classifierId настоящего BOM-объекта той же
    // строки в резолве листа "Связи".
    val folder = createdObjects.firstOrNull { (mappingElement, _) -> mappingElement.isFolder }
    val nonFolderObjects = createdObjects.filter { (mappingElement, _) -> !mappingElement.isFolder }

    if (folder != null) {
        nonFolderObjects.forEach { (_, loodsmanId) ->
            linkObjects(folder.second, loodsmanId, settings.mapping.linksSheet.linkType, rowsFailed)
        }
    }

    // Кандидаты на материалы ПОЛИНОМ собираются здесь (для целевых типов из
    // materials.appliesToTargets, например "Деталь") и обрабатываются отдельным проходом
    // постобработки после того, как все строки Excel уже прочитаны (runMaterialsMigration) —
    // столбцы (1)/(2) читаются независимо от identifierColumn листа "Связи".
    val materials = settings.mapping.materials
    val materialCandidatesForRow = createdObjects
        .filter { (mappingElement, _) -> mappingElement.target in materials.appliesToTargets }
        .map { (_, loodsmanId) ->
            MaterialCandidate(
                detailLoodsmanId = loodsmanId,
                drawingDesignation = row.value(materials.drawingDesignationColumn),
                classifierCode = row.value(materials.classifierCodeColumn),
                detailClassifierCode = row.value(settings.mapping.identifierColumn),
            )
        }

    // В identifiers (резолв листа "Связи") попадают только не-папочные объекты — папка не должна
    // становиться родителем в структуре, только организационным контейнером.
    val classifierId = row.value(settings.mapping.identifierColumn)?.toLongOrNull()
    val identifiersForRow = if (classifierId == null) {
        emptyList()
    } else {
        nonFolderObjects.map { (_, loodsmanId) -> Identifier(loodsmanId = loodsmanId, classifierId = classifierId) }
    }

    return ObjectRowResult(identifiersForRow, materialCandidatesForRow)
}

private suspend fun MigrationContext.createLoodsmanObject(
    mappingElement: MappingElement,
    keyAttr: String,
    row: RowView,
    objectsCreated: AtomicInteger,
    rowsFailed: AtomicInteger
): Int? = try {
    val created = loodsmanClient.editObject.create(
        sessionId,
        NewObjectInputDto(
            typeName = mappingElement.target,
            stateName = mappingElement.state,
            keyAttribute = keyAttr,
            isProject = mappingElement.isProject
        )
    )
    val loodsmanId = created.asInt()
    objectsCreated.incrementAndGet()

    val attributeValues = resolveAttributes(settings.mapping.attributes, row)
    if (attributeValues.isNotEmpty()) {
        loodsmanClient.editObject.setValues(
            sessionId,
            attributeValues.map { (name, value) ->
                UpAttrValuesByIdsInputDto(versionId = loodsmanId, attributeName = name, attributeValue = value)
            }
        )
    }

    if (mappingElement.linkToRoot) {
        linkObjects(rootId, loodsmanId, settings.mapping.linksSheet.linkType, rowsFailed)
    }

    loodsmanId
} catch (e: Exception) {
    rowsFailed.incrementAndGet()
    System.err.println("Не удалось создать объект (${mappingElement.target}, $keyAttr): ${e.message}")
    null
}

private suspend fun MigrationContext.linkObjects(
    parentLoodsmanId: Int,
    childLoodsmanId: Int,
    linkType: String,
    failCounter: AtomicInteger,
    quantity: Double = 1.0
): Boolean = try {
    loodsmanClient.editObject.newLink(
        sessionId,
        NewLinkInputDto(
            parentVersionId = parentLoodsmanId,
            childVersionId = childLoodsmanId,
            linkType = linkType,
            minQuantity = quantity,
            maxQuantity = quantity
        )
    )
    true
} catch (e: Exception) {
    failCounter.incrementAndGet()
    System.err.println("Не удалось создать связь ($parentLoodsmanId -> $childLoodsmanId): ${e.message}")
    false
}

private fun MigrationContext.excelInputStream() = File(settings.mapping.source.path).inputStream()
