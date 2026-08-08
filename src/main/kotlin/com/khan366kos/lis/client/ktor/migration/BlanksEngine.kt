package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpLinkAttrValuesInputDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

// Тай-брейк для resolveUnitId (см. MigrationEngine.kt) — НЕ фильтр: обозначение норм расхода не
// всегда "Масса" (например "м2"/"м3" для других материалов), поэтому величина не режется заранее,
// только используется, если designation реально неоднозначен между несколькими величинами (как
// "г" между "Масса" и "Год").
private const val RATE_MEASURE_TIE_BREAK = "Масса"

// Постобработка после runObjectsMigration(): blankCandidates уже полностью собраны (по одному на
// каждую строку "Деталь" с непустым mapping.materials.classifierCodeColumn, см.
// MigrationEngine.processObjectRow). Независимый поток от runMaterialsMigration() (поток A) — по
// решению пользователя, деталь может получить и "Материал по КД" напрямую, и "Заготовку" с
// "Материалом основным" одновременно, без взаимного влияния.
suspend fun MigrationContext.runBlanksMigration() {
    try {
        runBlanksMigrationInternal()
    } catch (e: ResponseException) {
        System.err.println("HTTP ${e.response.status.value}: ${e.response.bodyAsText()}")
        throw e
    }
}

private suspend fun MigrationContext.runBlanksMigrationInternal() {
    if (blankCandidates.isEmpty()) {
        println("Заготовки: строк-кандидатов нет, пропускаем")
        return
    }

    val blanks = settings.mapping.blanks
    val materials = settings.mapping.materials
    val classifierCodeProperty = materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(materials.classifierCodePropertyAbsoluteCode)
    val searchScope = resolveSearchScope()

    // Материал ("Материал основной") резолвится по коду классификатора через тот же приём, что
    // поток C (resolveBomMaterialByClassifierCode) — общий кэш "элемент ПОЛИНОМ -> Loodsman id"
    // нужен здесь же (несколько заготовок разных деталей могут разрешиться в один и тот же
    // элемент, см. resolveOrCreateMaterial). Заготовка при этом НЕ дедуплицируется — она создаётся
    // ровно одна на каждую строку-кандидат (1:1 с деталью), в отличие от материала.
    val elementCache = mutableMapOf<Pair<Int, Int>, Int>()
    val elementCacheMutex = Mutex()

    val blanksCreated = AtomicInteger(0)
    val materialsCreated = AtomicInteger(0)
    val blankLinksCreated = AtomicInteger(0)
    val materialLinksCreated = AtomicInteger(0)
    val materialsNotFound = AtomicInteger(0)
    val failures = AtomicInteger(0)
    val ratesAssigned = AtomicInteger(0)
    val rateAttrFailures = AtomicInteger(0)
    val unitsNotFound = AtomicInteger(0)
    val unitsCollision = AtomicInteger(0)
    val blankAttrsAssigned = AtomicInteger(0)
    val blankAttrFailures = AtomicInteger(0)
    val materialAttrsAssigned = AtomicInteger(0)
    val materialAttrFailures = AtomicInteger(0)

    // Норма расхода (mapping.blanks.rateUnitColumn) — резолв уникальных обозначений один раз,
    // тот же паттерн, что resolveUnitId везде в движке (одно обозначение обычно повторяется на
    // многих строках).
    val distinctRateUnits = blankCandidates.mapNotNull { it.rateUnitDesignation }.toSet()
    val rateUnitById = coroutineScope {
        distinctRateUnits.map { designation ->
            async { designation to resolveUnitId(designation, unitsNotFound, unitsCollision, RATE_MEASURE_TIE_BREAK) }
        }.awaitAll()
    }.toMap()

    // Единицы измерения атрибутов "Заготовка" (mapping.blanks.attributes[].unit, например "мм") —
    // константы из настроек, а не значения строк Excel, поэтому резолвятся один раз на уникальное
    // обозначение из конфига (а не по кандидатам, как rateUnitById выше). БЕЗ тай-брейка
    // RATE_MEASURE_TIE_BREAK ("Масса") — он специфичен для нормы расхода, для линейных размеров
    // ("мм") не подходит.
    val distinctAttrUnits = blanks.attributes.mapNotNull { it.unit }.toSet()
    val attrUnitById = coroutineScope {
        distinctAttrUnits.map { designation ->
            async { designation to resolveUnitId(designation, unitsNotFound, unitsCollision) }
        }.awaitAll()
    }.toMap()

    coroutineScope {
        blankCandidates.map { candidate ->
            async {
                try {
                    val blankId = loodsmanClient.editObject.create(
                        sessionId,
                        NewObjectInputDto(
                            typeName = blanks.target,
                            stateName = blanks.state,
                            keyAttribute = candidate.detailDesignation,
                            isProject = false,
                        )
                    ).asInt()
                    blanksCreated.incrementAndGet()

                    // Атрибуты ОБЪЕКТА "Заготовка" (mapping.blanks.attributes, например
                    // "Диаметр"/"Длина" с фиксированной единицей "мм") — EditObject/up-attr-values-by-ids,
                    // versionId только что созданной заготовки.
                    if (candidate.objectAttributes.isNotEmpty()) {
                        val results = loodsmanClient.editObject.setValues(
                            sessionId,
                            candidate.objectAttributes.map { attr ->
                                UpAttrValuesByIdsInputDto(
                                    versionId = blankId,
                                    attributeName = attr.loodsmanAttr,
                                    attributeValue = attr.value,
                                    unitGuid = attr.unitDesignation?.let { attrUnitById[it] },
                                )
                            }
                        )
                        val failed = results.filterNot { it.isSuccess }
                        if (failed.isEmpty()) {
                            blankAttrsAssigned.incrementAndGet()
                        } else {
                            blankAttrFailures.incrementAndGet()
                            failed.forEach {
                                System.err.println(
                                    "Заготовки: не удалось проставить атрибут '${it.attributeName}' на заготовку " +
                                        "$blankId: ${it.errorMessage}"
                                )
                            }
                        }
                    }

                    // Реверсивная связь: субъект — заготовка, объект — деталь (правило связывания
                    // в Loodsman для типа blanks.linkType сконфигурировано в эту сторону, как у
                    // "Технологическая ДСЕ для").
                    loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = blankId,
                            childVersionId = candidate.detailLoodsmanId,
                            linkType = blanks.linkType,
                        )
                    )
                    blankLinksCreated.incrementAndGet()

                    val materialId = resolveBomMaterialByClassifierCode(
                        candidate.classifierCode,
                        blanks.materialTarget,
                        classifierCodeProperty,
                        searchScope,
                        elementCache,
                        elementCacheMutex,
                        materialsCreated,
                        attributeValues = candidate.materialObjectAttributes,
                    )
                    if (materialId == null) {
                        materialsNotFound.incrementAndGet()
                        println(
                            "Заготовки: материал основной не найден в ПОЛИНОМ по коду классификатора " +
                                "'${candidate.classifierCode}' (деталь ${candidate.detailLoodsmanId}, " +
                                "заготовка $blankId создана без материала)"
                        )
                        return@async
                    }

                    val materialLinkId = loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = blankId,
                            childVersionId = materialId,
                            linkType = blanks.materialLinkType,
                        )
                    ).asInt()
                    materialLinksCreated.incrementAndGet()

                    // Норма расхода — АТРИБУТ этой связи (величина "Масса" в схеме Loodsman), НЕ
                    // встроенное minQuantity/maxQuantity/unitId связи — отдельный эндпоинт
                    // EditObject/up-link-attr-values, по linkId только что созданной связи.
                    if (blanks.rateAttribute.isNotBlank() && candidate.rate != null) {
                        val rateUnitId = candidate.rateUnitDesignation?.let { rateUnitById[it] }
                        val results = loodsmanClient.editObject.setLinkAttrValues(
                            sessionId,
                            listOf(
                                UpLinkAttrValuesInputDto(
                                    linkId = materialLinkId,
                                    attributeName = blanks.rateAttribute,
                                    attributeValue = candidate.rate.toString(),
                                    unitGuid = rateUnitId,
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
                                    "Заготовки: не удалось проставить '${blanks.rateAttribute}' на связи " +
                                        "$materialLinkId: ${it.errorMessage}"
                                )
                            }
                        }
                    }

                    // Атрибуты СВЯЗИ "Материал основной" (mapping.blanks.materialAttributes,
                    // например "Единица нормирования материала"/"Форма сортамента") — отдельный от
                    // нормы расхода вызов up-link-attr-values на ТУ ЖЕ связь materialLinkId.
                    if (candidate.materialLinkAttributes.isNotEmpty()) {
                        val results = loodsmanClient.editObject.setLinkAttrValues(
                            sessionId,
                            candidate.materialLinkAttributes.map { (name, value) ->
                                UpLinkAttrValuesInputDto(
                                    linkId = materialLinkId,
                                    attributeName = name,
                                    attributeValue = value,
                                )
                            }
                        )
                        val failed = results.filterNot { it.isSuccess }
                        if (failed.isEmpty()) {
                            materialAttrsAssigned.incrementAndGet()
                        } else {
                            materialAttrFailures.incrementAndGet()
                            failed.forEach {
                                System.err.println(
                                    "Заготовки: не удалось проставить атрибут '${it.attributeName}' на связь " +
                                        "$materialLinkId: ${it.errorMessage}"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Не удалось создать заготовку/материал (деталь ${candidate.detailLoodsmanId}): ${e.message}"
                    )
                    (e as? ResponseException)?.let {
                        System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
                    }
                }
            }
        }.awaitAll()
    }

    println(
        "Заготовки: кандидатов ${blankCandidates.size}, создано заготовок ${blanksCreated.get()}, " +
            "связей деталь-заготовка ${blankLinksCreated.get()}, создано материалов ${materialsCreated.get()}, " +
            "связей заготовка-материал ${materialLinksCreated.get()}, " +
            "материал не найден в ПОЛИНОМ ${materialsNotFound.get()}, ошибок ${failures.get()}, " +
            "норм расхода назначено ${ratesAssigned.get()}, ошибок назначения нормы ${rateAttrFailures.get()}, " +
            "атрибутов заготовки назначено ${blankAttrsAssigned.get()}, ошибок атрибутов заготовки ${blankAttrFailures.get()}, " +
            "атрибутов материала основного назначено ${materialAttrsAssigned.get()}, " +
            "ошибок атрибутов материала основного ${materialAttrFailures.get()}, " +
            "обозначение единицы не найдено ${unitsNotFound.get()}, коллизий обозначения ${unitsCollision.get()}"
    )
}
