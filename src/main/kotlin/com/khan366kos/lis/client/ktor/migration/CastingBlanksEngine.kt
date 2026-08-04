package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.CastingBlankLinkCandidate
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

private const val MATERIAL_TYPE_SAMPLE = "Образец"
private const val MATERIAL_TYPE_MAIN = "Основной"
private const val MATERIAL_TYPE_AUXILIARY = "Вспомогательный"

// Тай-брейк для resolveUnitId (см. MigrationEngine.kt) — НЕ фильтр: обозначение норм расхода не
// всегда "Масса" (например "м2"/"м3" для других материалов), поэтому величина не режется заранее,
// только используется, если designation реально неоднозначен между несколькими величинами (как
// "г" между "Масса" и "Год").
private const val RATE_MEASURE_TIE_BREAK = "Масса"

// Литейные заготовки: связи (mapping.castingBlanks) — отдельный, независимый от остальных потоков
// лист Excel. Для первого встреченного значения родителя (parentColumn) создаётся "Комплект
// вспомогательных материалов" с реверсивной связью на родителя (тот же приём, что "Заготовка для"
// в BlanksEngine.kt), к комплекту прицепляются "Материал"/"Материал вспомогательный", резолвленные
// в ПОЛИНОМ по коду классификатора childColumn (тот же resolveBomMaterialByClassifierCode, что
// использует BlanksEngine.kt).
suspend fun MigrationContext.runCastingBlanksLinksMigration() {
    try {
        runCastingBlanksLinksMigrationInternal()
    } catch (e: ResponseException) {
        System.err.println("HTTP ${e.response.status.value}: ${e.response.bodyAsText()}")
        throw e
    }
}

private suspend fun MigrationContext.runCastingBlanksLinksMigrationInternal() {
    val castingBlanks = settings.mapping.castingBlanks
    if (castingBlanks.setTarget.isBlank()) {
        println("Литейные заготовки: фича выключена (mapping.castingBlanks.setTarget пуст), пропускаем")
        return
    }

    val rows = ExcelSaxParser().parse(excelInputStream(), castingBlanks.name).toList()
    val headerRow = rows.firstOrNull { it.rowIndex == castingBlanks.headersRow }
        ?: throw IllegalStateException(
            "Не найдена строка заголовков (индекс ${castingBlanks.headersRow}) на листе '${castingBlanks.name}'"
        )
    val headerMap = SheetHeaders.build(headerRow.cells)
    val dataRows = rows.filter { it.rowIndex > castingBlanks.headersRow }

    val rateReadFailures = AtomicInteger(0)
    val candidatesByParent = mutableMapOf<Long, MutableList<CastingBlankLinkCandidate>>()
    dataRows.forEach { row ->
        val parentId = row.cells.getOrNull(castingBlanks.parentColumn)?.trim()?.toLongOrNull() ?: return@forEach
        val childCode = row.cells.getOrNull(castingBlanks.childColumn)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@forEach
        val rowView = RowView(headerMap, row.cells)

        val materialType = castingBlanks.materialTypeColumn.takeIf { it.isNotBlank() }?.let { rowView.value(it) }
        val workshop = castingBlanks.workshopColumn.takeIf { it.isNotBlank() }?.let { rowView.value(it) }

        // Пустая ячейка/не настроенная колонка означает "не проставлять норму" — НЕ "1.0" (как у
        // linksSheet.quantityColumn/resolveLinkQuantity), тот же приём, что BlanksEngine.kt/
        // BlankCandidate.rate.
        val quantity = castingBlanks.quantityColumn.takeIf { it.isNotBlank() }?.let { column ->
            val parsed = rowView.value(column)?.replace(",", ".")?.toDoubleOrNull()
            if (parsed == null) {
                rateReadFailures.incrementAndGet()
                System.err.println(
                    "Литейные заготовки: норма расхода не прочитана в столбце '$column' (родитель " +
                        "'$parentId', код классификатора '$childCode') — связь будет создана без нормы"
                )
            }
            parsed
        }
        val unitDesignation = resolveLinkUnitDesignation(castingBlanks.unitColumn, emptyList(), rowView)

        candidatesByParent.getOrPut(parentId) { mutableListOf() }.add(
            CastingBlankLinkCandidate(
                childClassifierCode = childCode,
                materialType = materialType,
                quantity = quantity,
                unitDesignation = unitDesignation,
                workshop = workshop,
            )
        )
    }

    if (candidatesByParent.isEmpty()) {
        println("Литейные заготовки: строк-кандидатов нет, пропускаем")
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
                        "Литейные заготовки: родитель с кодом классификатора '$parentClassifierId' не найден " +
                            "среди созданных объектов — группа пропущена целиком"
                    )
                    return@async emptyList()
                }
                parents.map { parent ->
                    parent to createMaterialsKit(
                        parent, castingBlanks.setTarget, castingBlanks.setState, castingBlanks.setLinkType,
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

    val samplesSkipped = AtomicInteger(0)
    val unknownMaterialTypes = AtomicInteger(0)
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

                val target = when (candidate.materialType?.trim()) {
                    MATERIAL_TYPE_SAMPLE -> {
                        samplesSkipped.incrementAndGet()
                        println(
                            "Литейные заготовки: '$MATERIAL_TYPE_SAMPLE' не обрабатывается (родитель " +
                                "${parent.loodsmanId}, код классификатора '${candidate.childClassifierCode}')"
                        )
                        return@async
                    }
                    MATERIAL_TYPE_MAIN -> castingBlanks.materialTarget
                    MATERIAL_TYPE_AUXILIARY -> castingBlanks.auxMaterialTarget
                    else -> {
                        unknownMaterialTypes.incrementAndGet()
                        System.err.println(
                            "Литейные заготовки: неизвестный тип материала '${candidate.materialType}' (родитель " +
                                "${parent.loodsmanId}, код классификатора '${candidate.childClassifierCode}') — строка пропущена"
                        )
                        return@async
                    }
                }

                try {
                    val materialId = resolveBomMaterialByClassifierCode(
                        candidate.childClassifierCode,
                        target,
                        classifierCodeProperty,
                        searchScope,
                        elementCache,
                        elementCacheMutex,
                        materialsCreated,
                    )
                    if (materialId == null) {
                        materialsNotFound.incrementAndGet()
                        println(
                            "Литейные заготовки: материал не найден в ПОЛИНОМ по коду классификатора " +
                                "'${candidate.childClassifierCode}' (комплект $kitId)"
                        )
                        return@async
                    }

                    val materialLinkId = loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = kitId,
                            childVersionId = materialId,
                            linkType = castingBlanks.materialLinkType,
                        )
                    ).asInt()
                    materialLinksCreated.incrementAndGet()

                    if (castingBlanks.rateAttribute.isNotBlank() && candidate.quantity != null) {
                        val unitId = candidate.unitDesignation?.let { unitById[it] }
                        val results = loodsmanClient.editObject.setLinkAttrValues(
                            sessionId,
                            listOf(
                                UpLinkAttrValuesInputDto(
                                    linkId = materialLinkId,
                                    attributeName = castingBlanks.rateAttribute,
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
                                    "Литейные заготовки: не удалось проставить '${castingBlanks.rateAttribute}' на " +
                                        "связи $materialLinkId: ${it.errorMessage}"
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
                    if (castingBlanks.workshopAttribute.isNotBlank() && !workshop.isNullOrBlank()) {
                        val results = loodsmanClient.editObject.setLinkAttrValues(
                            sessionId,
                            listOf(
                                UpLinkAttrValuesInputDto(
                                    linkId = materialLinkId,
                                    attributeName = castingBlanks.workshopAttribute,
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
                                    "Литейные заготовки: не удалось проставить '${castingBlanks.workshopAttribute}' на " +
                                        "связи $materialLinkId: ${it.errorMessage}"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Литейные заготовки: не удалось создать/связать материал (комплект $kitId, код " +
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
        "Литейные заготовки: групп ${candidatesByParent.size}, родителей не найдено ${parentsNotFound.get()}, " +
            "создано комплектов ${setsCreated.get()}, связей комплект-родитель ${setLinksCreated.get()}, " +
            "ошибок комплектов ${setFailures.get()}, образцов пропущено ${samplesSkipped.get()}, " +
            "неизвестных типов материала ${unknownMaterialTypes.get()}, создано материалов ${materialsCreated.get()}, " +
            "материал не найден в ПОЛИНОМ ${materialsNotFound.get()}, связей комплект-материал " +
            "${materialLinksCreated.get()}, норм расхода назначено ${ratesAssigned.get()}, ошибок нормы " +
            "${rateAttrFailures.get()}, цехов назначено ${workshopsAssigned.get()}, ошибок цеха " +
            "${workshopAttrFailures.get()}, ошибок нормы (чтение) " +
            "${rateReadFailures.get()}, обозначение единицы не найдено ${unitsNotFound.get()}, коллизий " +
            "обозначения ${unitsCollision.get()}, прочих ошибок ${failures.get()}"
    )
}

