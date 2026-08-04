package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.AuxMaterialLinkCandidate
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.excel.ExcelSaxParser
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpLinkAttrValuesInputDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

// Тай-брейк для resolveUnitId (см. MigrationEngine.kt) — НЕ фильтр: обозначение норм расхода не
// всегда "Масса" (например "м2"/"м3" для других материалов), поэтому величина не режется заранее,
// только используется, если designation реально неоднозначен между несколькими величинами (как
// "г" между "Масса" и "Год" — см. CastingBlanksEngine.kt, тот же реальный инцидент).
private const val RATE_MEASURE_TIE_BREAK = "Масса"

// Вспомогательные материалы для Детали (mapping.auxMaterials) — отдельный, независимый от
// остальных потоков лист Excel, архитектурно аналогичный CastingBlanksEngine.kt ("Литейные
// заготовки: связи"), но БЕЗ ветвления по типу материала — на каждую строку всегда создаётся один
// тип объекта, "Материал вспомогательный" (mapping.auxMaterials.materialTarget). Комплект
// создаётся общим приёмом createMaterialsKit (MaterialsKitEngine.kt), материал резолвится общим
// resolveBomMaterialByClassifierCode (MaterialsEngine.kt) — тем же путём, что у castingBlanks.
suspend fun MigrationContext.runAuxMaterialsMigration() {
    try {
        runAuxMaterialsMigrationInternal()
    } catch (e: ResponseException) {
        System.err.println("HTTP ${e.response.status.value}: ${e.response.bodyAsText()}")
        throw e
    }
}

private suspend fun MigrationContext.runAuxMaterialsMigrationInternal() {
    val auxMaterials = settings.mapping.auxMaterials
    if (auxMaterials.setTarget.isBlank()) {
        println("Вспомогательные материалы: фича выключена (mapping.auxMaterials.setTarget пуст), пропускаем")
        return
    }

    val rows = ExcelSaxParser().parse(excelInputStream(), auxMaterials.name).toList()
    val headerRow = rows.firstOrNull { it.rowIndex == auxMaterials.headersRow }
        ?: throw IllegalStateException(
            "Не найдена строка заголовков (индекс ${auxMaterials.headersRow}) на листе '${auxMaterials.name}'"
        )
    val headerMap = SheetHeaders.build(headerRow.cells)
    val dataRows = rows.filter { it.rowIndex > auxMaterials.headersRow }

    val rateReadFailures = AtomicInteger(0)
    val candidatesByParent = mutableMapOf<Long, MutableList<AuxMaterialLinkCandidate>>()
    dataRows.forEach { row ->
        val rowView = RowView(headerMap, row.cells)
        val parentId = rowView.value(auxMaterials.parentColumn)?.toLongOrNull() ?: return@forEach
        val childCode = rowView.value(auxMaterials.childColumn) ?: return@forEach

        val workshop = auxMaterials.workshopColumn.takeIf { it.isNotBlank() }?.let { rowView.value(it) }

        // Пустая ячейка/не настроенная колонка означает "не проставлять норму" — НЕ "1.0" (как у
        // linksSheet.quantityColumn/resolveLinkQuantity), тот же приём, что в CastingBlanksEngine.kt.
        val quantity = auxMaterials.quantityColumn.takeIf { it.isNotBlank() }?.let { column ->
            val parsed = rowView.value(column)?.replace(",", ".")?.toDoubleOrNull()
            if (parsed == null) {
                rateReadFailures.incrementAndGet()
                System.err.println(
                    "Вспомогательные материалы: норма расхода не прочитана в столбце '$column' (родитель " +
                        "'$parentId', код классификатора '$childCode') — связь будет создана без нормы"
                )
            }
            parsed
        }
        val unitDesignation = resolveLinkUnitDesignation(auxMaterials.unitColumn, emptyList(), rowView)

        candidatesByParent.getOrPut(parentId) { mutableListOf() }.add(
            AuxMaterialLinkCandidate(
                childClassifierCode = childCode,
                quantity = quantity,
                unitDesignation = unitDesignation,
                workshop = workshop,
            )
        )
    }

    if (candidatesByParent.isEmpty()) {
        println("Вспомогательные материалы: строк-кандидатов нет, пропускаем")
        return
    }

    val elementsByClassifierId = identifiers.groupBy { it.classifierId }

    val setsCreated = AtomicInteger(0)
    val setLinksCreated = AtomicInteger(0)
    val parentsNotFound = AtomicInteger(0)
    val setFailures = AtomicInteger(0)

    // Комплект создаётся один раз на первое встреченное значение parentColumn (группу) — по одному
    // на каждый резолвленный Loodsman-объект родителя (обычно один, но строка "Объекты" может
    // совпасть с несколькими правилами mapping.types[] и породить несколько объектов с общим
    // classifierId, см. processObjectRow).
    val kitIdByParentLoodsmanId = coroutineScope {
        candidatesByParent.keys.map { parentClassifierId ->
            async {
                val parents = elementsByClassifierId[parentClassifierId]
                if (parents == null) {
                    parentsNotFound.incrementAndGet()
                    println(
                        "Вспомогательные материалы: родитель с кодом классификатора '$parentClassifierId' не " +
                            "найден среди созданных объектов — группа пропущена целиком"
                    )
                    return@async emptyList()
                }
                parents.map { parent ->
                    parent to createMaterialsKit(
                        parent, auxMaterials.setTarget, auxMaterials.setState, auxMaterials.setLinkType,
                        setsCreated, setLinksCreated, setFailures,
                    )
                }
            }
        }.awaitAll()
    }.flatten().mapNotNull { (parent, kitId) -> kitId?.let { parent.loodsmanId to it } }.toMap()

    val classifierCodeProperty = settings.mapping.materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(settings.mapping.materials.classifierCodePropertyAbsoluteCode)
    val searchScope = resolveSearchScope()
    val elementCache = mutableMapOf<Pair<Int, Int>, Int>()
    val elementCacheMutex = Mutex()

    val unitsNotFound = AtomicInteger(0)
    val unitsCollision = AtomicInteger(0)
    val distinctUnits = candidatesByParent.values.flatten().mapNotNull { it.unitDesignation }.toSet()
    val unitById = coroutineScope {
        distinctUnits.map { designation ->
            async { designation to resolveUnitId(designation, unitsNotFound, unitsCollision, RATE_MEASURE_TIE_BREAK) }
        }.awaitAll()
    }.toMap()

    val materialsCreated = AtomicInteger(0)
    val materialsNotFound = AtomicInteger(0)
    val materialLinksCreated = AtomicInteger(0)
    val ratesAssigned = AtomicInteger(0)
    val rateAttrFailures = AtomicInteger(0)
    val workshopsAssigned = AtomicInteger(0)
    val workshopAttrFailures = AtomicInteger(0)
    val failures = AtomicInteger(0)

    coroutineScope {
        candidatesByParent.entries.flatMap { (parentClassifierId, candidates) ->
            val parents = elementsByClassifierId[parentClassifierId] ?: emptyList()
            parents.flatMap { parent -> candidates.map { parent to it } }
        }.map { (parent, candidate) ->
            async {
                val kitId = kitIdByParentLoodsmanId[parent.loodsmanId] ?: return@async

                try {
                    val materialId = resolveBomMaterialByClassifierCode(
                        candidate.childClassifierCode,
                        auxMaterials.materialTarget,
                        classifierCodeProperty,
                        searchScope,
                        elementCache,
                        elementCacheMutex,
                        materialsCreated,
                    )
                    if (materialId == null) {
                        materialsNotFound.incrementAndGet()
                        println(
                            "Вспомогательные материалы: материал не найден в ПОЛИНОМ по коду классификатора " +
                                "'${candidate.childClassifierCode}' (комплект $kitId)"
                        )
                        return@async
                    }

                    val materialLinkId = loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = kitId,
                            childVersionId = materialId,
                            linkType = auxMaterials.materialLinkType,
                        )
                    ).asInt()
                    materialLinksCreated.incrementAndGet()

                    if (auxMaterials.rateAttribute.isNotBlank() && candidate.quantity != null) {
                        val unitId = candidate.unitDesignation?.let { unitById[it] }
                        val results = loodsmanClient.editObject.setLinkAttrValues(
                            sessionId,
                            listOf(
                                UpLinkAttrValuesInputDto(
                                    linkId = materialLinkId,
                                    attributeName = auxMaterials.rateAttribute,
                                    attributeValue = candidate.quantity.toString(),
                                    unitGuid = unitId,
                                )
                            )
                        )
                        val failed = results.filterNot { it.isSuccess }
                        if (failed.isEmpty()) {
                            ratesAssigned.incrementAndGet()
                        } else {
                            rateAttrFailures.incrementAndGet()
                            failed.forEach {
                                System.err.println(
                                    "Вспомогательные материалы: не удалось проставить '${auxMaterials.rateAttribute}' " +
                                        "на связи $materialLinkId: ${it.errorMessage}"
                                )
                            }
                        }
                    }

                    // Цех-потребитель — АТРИБУТ СВЯЗИ "Состоит из ..." (комплект->материал), НЕ
                    // атрибут объекта материала — тот же механизм и linkId, что у нормы расхода
                    // чуть выше (реальный инцидент: сначала было по ошибке реализовано через
                    // up-attr-values-by-ids/versionId материала, Loodsman отвечал 410202 "Атрибут
                    // не соответствует типу" — атрибут в схеме заведён на связи, не на объекте).
                    val workshop = candidate.workshop
                    if (auxMaterials.workshopAttribute.isNotBlank() && !workshop.isNullOrBlank()) {
                        val results = loodsmanClient.editObject.setLinkAttrValues(
                            sessionId,
                            listOf(
                                UpLinkAttrValuesInputDto(
                                    linkId = materialLinkId,
                                    attributeName = auxMaterials.workshopAttribute,
                                    attributeValue = workshop,
                                )
                            )
                        )
                        val failed = results.filterNot { it.isSuccess }
                        if (failed.isEmpty()) {
                            workshopsAssigned.incrementAndGet()
                        } else {
                            workshopAttrFailures.incrementAndGet()
                            failed.forEach {
                                System.err.println(
                                    "Вспомогательные материалы: не удалось проставить '${auxMaterials.workshopAttribute}' " +
                                        "на связи $materialLinkId: ${it.errorMessage}"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Вспомогательные материалы: не удалось создать/связать материал (комплект $kitId, код " +
                            "классификатора '${candidate.childClassifierCode}'): ${e.message}"
                    )
                    (e as? ResponseException)?.let {
                        System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                    }
                }
            }
        }.awaitAll()
    }

    println(
        "Вспомогательные материалы: групп ${candidatesByParent.size}, родителей не найдено " +
            "${parentsNotFound.get()}, создано комплектов ${setsCreated.get()}, связей комплект-родитель " +
            "${setLinksCreated.get()}, ошибок комплектов ${setFailures.get()}, создано материалов " +
            "${materialsCreated.get()}, материал не найден в ПОЛИНОМ ${materialsNotFound.get()}, связей " +
            "комплект-материал ${materialLinksCreated.get()}, норм расхода назначено ${ratesAssigned.get()}, " +
            "ошибок нормы ${rateAttrFailures.get()}, цехов назначено ${workshopsAssigned.get()}, ошибок цеха " +
            "${workshopAttrFailures.get()}, ошибок нормы (чтение) ${rateReadFailures.get()}, обозначение " +
            "единицы не найдено ${unitsNotFound.get()}, коллизий обозначения ${unitsCollision.get()}, " +
            "прочих ошибок ${failures.get()}"
    )
}
