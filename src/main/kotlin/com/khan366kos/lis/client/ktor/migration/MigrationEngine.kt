package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.AnalogGroupCandidate
import com.khan366kos.lis.client.ktor.domain.BlankCandidate
import com.khan366kos.lis.client.ktor.domain.BomMaterialCandidate
import com.khan366kos.lis.client.ktor.domain.Identifier
import com.khan366kos.lis.client.ktor.domain.MappingElement
import com.khan366kos.lis.client.ktor.domain.MaterialCandidate
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.ObjectClassificationCandidate
import com.khan366kos.lis.client.ktor.domain.PolynomBackedObjectCandidate
import com.khan366kos.lis.client.ktor.domain.UnresolvedAnalogGroupCandidate
import com.khan366kos.lis.client.ktor.excel.ExcelSaxParser
import com.khan366kos.lis.client.ktor.loodsman.api.dto.CreateBoObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.ReferenceBoVersionInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.UpAttrValuesByIdsInputDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
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
    // ничего не пишут в identifiers/materialCandidates/bomMaterialSpecClassifierIds/
    // bomMaterialRowClassifierIds напрямую, чтобы не гонять запись в MutableList/MutableSet из
    // нескольких потоков одновременно.
    val created = results.flatMap { it.identifiers }
    identifiers.addAll(created)
    materialCandidates.addAll(results.flatMap { it.materialCandidates })
    materialSubstituteCandidates.addAll(results.flatMap { it.materialSubstituteCandidates })
    bomMaterialSpecClassifierIds.addAll(results.mapNotNull { it.bomMaterialClassifierId })
    bomMaterialRowClassifierIds.addAll(results.mapNotNull { it.bomMaterialRowClassifierId })
    blankCandidates.addAll(results.flatMap { it.blankCandidates })

    // Стандартные/Прочие изделия (mapping.types[].resolveViaPolynom) создаются не сразу в
    // processObjectRow, а батчем здесь — ДО завершения runObjectsMigration(), чтобы
    // runLinksMigration() (следующий шаг пайплайна) увидел их в identifiers как обычные объекты.
    resolvePolynomBackedObjects(results.flatMap { it.polynomBackedCandidates })

    // Классификация в ПОЛИНОМ (BoReference/reference-bo-version) для обычных не-папочных объектов,
    // созданных через EditObject/new-object — см. classifyCreatedObjects. Объекты через
    // create-bo-object (resolvePolynomBackedObjects выше, материалы, заготовки) уже привязаны к
    // ПОЛИНОМ в момент создания, повторная классификация им не нужна.
    classifyCreatedObjects(results.flatMap { it.classificationCandidates })

    println(
        "Объекты: строк ${dataRows.size}, создано объектов ${objectsCreated.get()}, " +
            "с привязкой к классификатору ${created.size}, ошибок ${rowsFailed.get()}, " +
            "DS-объектов ${bomMaterialSpecClassifierIds.size}, " +
            "строк раздела Материалы ${bomMaterialRowClassifierIds.size}, " +
            "кандидатов на заготовки ${blankCandidates.size}"
    )
}

// Классифицирует обычные не-папочные объекты (созданы EditObject/new-object, mapping.types[] без
// resolveViaPolynom) в ПОЛИНОМ: поиск элемента по коду классификатора объекта
// (mapping.identifierColumn, тот же classifierId, что резолвит структуру BOM) через тот же
// classifierCodeProperty/searchScope, что materials/resolveViaPolynom (решение пользователя не
// заводить отдельную настройку под тот же смысл "код классификатора"), затем привязка уже
// созданной версии Loodsman к найденному location через BoReference/reference-bo-version
// (boTypeBindingRuleId зафиксирован в 0). Не найдено в ПОЛИНОМ — лог + счётчик, объект остаётся
// созданным без классификации, миграция продолжается (не ошибка, тот же принцип, что везде в
// проекте для резолва через ПОЛИНОМ).
private suspend fun MigrationContext.classifyCreatedObjects(candidates: List<ObjectClassificationCandidate>) {
    if (candidates.isEmpty()) return

    val materials = settings.mapping.materials
    val classifierCodeProperty = materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(materials.classifierCodePropertyAbsoluteCode)
    val searchScope = resolveSearchScope()

    val classified = AtomicInteger(0)
    val notFoundInPolynom = AtomicInteger(0)
    val failures = AtomicInteger(0)
    val byClassifierId = candidates.groupBy { it.classifierId }

    coroutineScope {
        byClassifierId.entries.map { (classifierId, group) ->
            async {
                val found = try {
                    callPolynom { token ->
                        polynomClient.search.searchByStringProperty(
                            accessToken = token,
                            scope = searchScope,
                            propertyDefinition = classifierCodeProperty,
                            value = classifierId.toString(),
                        )
                    }.firstOrNull()
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Классификация: не удалось выполнить поиск в ПОЛИНОМ по коду классификатора " +
                            "'$classifierId' (объекты ${group.map { it.loodsmanId }}): ${e.message}"
                    )
                    return@async
                }
                if (found == null) {
                    notFoundInPolynom.incrementAndGet()
                    println(
                        "Классификация: код классификатора '$classifierId' не найден в ПОЛИНОМ — объекты " +
                            "${group.map { it.loodsmanId }} остаются без классификации"
                    )
                    return@async
                }

                val location = try {
                    callPolynom { token ->
                        polynomClient.classification.getLocation(
                            token, IdentifiableObjectDto(found.objectId, found.typeId)
                        )
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Классификация: не удалось получить location для кода классификатора " +
                            "'$classifierId' (объекты ${group.map { it.loodsmanId }}): ${e.message}"
                    )
                    return@async
                }

                group.map { candidate ->
                    async {
                        try {
                            loodsmanClient.boReference.referenceBoVersion(
                                sessionId,
                                ReferenceBoVersionInputDto(
                                    versionId = candidate.loodsmanId,
                                    boTypeBindingRuleId = 0,
                                    objectLocation = location,
                                )
                            )
                            classified.incrementAndGet()
                        } catch (e: Exception) {
                            failures.incrementAndGet()
                            System.err.println(
                                "Классификация: не удалось привязать объект ${candidate.loodsmanId} " +
                                    "(код классификатора '$classifierId') к ПОЛИНОМ: ${e.message}"
                            )
                        }
                    }
                }.awaitAll()
            }
        }.awaitAll()
    }

    println(
        "Классификация в ПОЛИНОМ: кандидатов ${candidates.size}, уникальных кодов ${byClassifierId.size}, " +
            "классифицировано ${classified.get()}, не найдено в ПОЛИНОМ ${notFoundInPolynom.get()}, " +
            "ошибок ${failures.get()}"
    )
}

private data class LinkRowData(val quantity: Double?, val unitDesignation: String?, val analogGroup: AnalogGroupInfo?)
private data class LinkPair(val parent: Identifier, val child: Identifier, val quantity: Double, val unitDesignation: String?)
private data class AnalogGroupInfo(val groupNumber: Int, val variantNumber: Int, val isBasic: Boolean)

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
    val unitsNotFound = AtomicInteger(0)
    val unitsCollision = AtomicInteger(0)
    val unitsAssigned = AtomicInteger(0)
    val analogGroupsFailed = AtomicInteger(0)
    val analogGroups = settings.mapping.analogGroups

    // identifiers уже полностью собраны (runObjectsMigration отработал раньше по pipeline) —
    // можно резолвить classifierId -> Loodsman-объекты ДО прохода по строкам "Связи", это нужно
    // ниже для childRaw, который не парсится как Long.
    val elementsByClassifierId = identifiers.groupBy { it.classifierId }

    // childId -> (quantity, unitDesignation); quantity == null значит колонка количества
    // сконфигурирована, но значение в строке не прочиталось — такая пара позже отбрасывается,
    // а не линкуется с 1.0. unitDesignation == null значит unit связи не трогается вообще
    // (колонка не сконфигурирована / пустая ячейка / значение из unitExcludeValues).
    val classifierLinks = mutableMapOf<Long, MutableMap<Long, LinkRowData>>()
    dataRows.forEach { row ->
        val parentId = row.cells.getOrNull(linksSheet.parentColumn)?.trim()?.toLongOrNull() ?: return@forEach
        val childRaw = row.cells.getOrNull(linksSheet.childColumn)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        val rowView = RowView(headerMap, row.cells)
        // Резолвятся ДО ветвления по childId — обеим веткам (обычный BOM-child и материал по КД
        // через bomMaterials) нужны одни и те же quantity/unitDesignation с ЭТОЙ строки "Связи".
        val quantity = resolveLinkQuantity(linksSheet.quantityColumn, rowView)
        val unitDesignation = resolveLinkUnitDesignation(linksSheet.unitColumn, linksSheet.unitExcludeValues, rowView)

        val childId = childRaw.toLongOrNull()
        if (childId == null) {
            // childRaw не парсится как Long — не может ссылаться на classifierId листа "Объекты"
            // (identifierColumn там всегда числовой), поэтому childClassifierId сверить с
            // bomMaterialRowClassifierIds невозможно — используется только признак DS родителя.
            if (parentId in bomMaterialSpecClassifierIds) {
                if (quantity == null) {
                    linksFailed.incrementAndGet()
                    System.err.println(
                        "Связь ($parentId -> $childRaw) пропущена: не удалось прочитать '${linksSheet.quantityColumn}'"
                    )
                } else {
                    elementsByClassifierId[parentId]?.forEach { parent ->
                        bomMaterialCandidates.add(BomMaterialCandidate(parent.loodsmanId, childRaw, quantity, unitDesignation))
                    }
                }
            }
            return@forEach
        }

        // Разбор "группа аналогов" только в этой ветке (childId — валидный Long) — по решению
        // пользователя, объект варианта это тот же потомок, что уже резолвится через childColumn.
        val analogGroupNumbers = resolveAnalogGroupNumbers(analogGroups.analogGroupColumn, rowView)
        val analogGroup = analogGroupNumbers?.let { (groupNumber, variantNumber) ->
            val production = resolveProductionQuantity(analogGroups.productionQuantityColumn, rowView)
            val withoutMerge = resolveProductionQuantity(analogGroups.productionQuantityWithoutMergeColumn, rowView)
            when {
                production == null || withoutMerge == null -> {
                    analogGroupsFailed.incrementAndGet()
                    System.err.println(
                        "Группа аналогов ($parentId -> $childId, группа $groupNumber-$variantNumber) пропущена: " +
                            "не удалось прочитать '${analogGroups.productionQuantityColumn}' или " +
                            "'${analogGroups.productionQuantityWithoutMergeColumn}'"
                    )
                    null
                }
                production != 0.0 && withoutMerge != 0.0 -> {
                    println("Группа аналогов: строка $parentId -> $childId прочитана, группа $groupNumber-$variantNumber, isBasic=true")
                    AnalogGroupInfo(groupNumber, variantNumber, isBasic = true)
                }
                production == 0.0 && withoutMerge == 0.0 -> {
                    println("Группа аналогов: строка $parentId -> $childId прочитана, группа $groupNumber-$variantNumber, isBasic=false")
                    AnalogGroupInfo(groupNumber, variantNumber, isBasic = false)
                }
                else -> {
                    analogGroupsFailed.incrementAndGet()
                    System.err.println(
                        "Группа аналогов ($parentId -> $childId, группа $groupNumber-$variantNumber) пропущена: " +
                            "несогласованные производственные количества " +
                            "('${analogGroups.productionQuantityColumn}'=$production, " +
                            "'${analogGroups.productionQuantityWithoutMergeColumn}'=$withoutMerge)"
                    )
                    null
                }
            }
        }

        // Реальный инцидент: тот же (parentId, childId) может встретиться в нескольких строках
        // листа "Связи" (дублирующиеся строки BOM) — раньше последняя строка полностью
        // перезаписывала запись, и если группа аналогов была указана в одной из более ранних
        // строк, а более поздняя строка для той же пары эту колонку не заполняла, группа
        // молча терялась. analogGroup теперь не перезаписывается на null последующей строкой —
        // побеждает первое непустое значение, а не последняя строка.
        val parentLinks = classifierLinks.getOrPut(parentId) { mutableMapOf() }
        val mergedAnalogGroup = analogGroup ?: parentLinks[childId]?.analogGroup
        parentLinks[childId] = LinkRowData(quantity, unitDesignation, mergedAnalogGroup)
    }

    val linkPairs = classifierLinks.flatMap { (parentClassifierId, children) ->
        val parents = elementsByClassifierId[parentClassifierId] ?: return@flatMap emptyList()
        children.flatMap { (childClassifierId, rowData) ->
            if (rowData.quantity == null) {
                linksFailed.incrementAndGet()
                System.err.println(
                    "Связь ($parentClassifierId -> $childClassifierId) пропущена: " +
                        "не удалось прочитать '${linksSheet.quantityColumn}'"
                )
                return@flatMap emptyList()
            }
            val children2 = elementsByClassifierId[childClassifierId]
            if (children2 == null) {
                // Child — валидный Long, но не резолвится ни в один созданный объект. Если
                // родитель отмечен DS (bomMaterialSpecClassifierIds) И childClassifierId — это
                // classifierId строки листа "Объекты" раздела "Материалы"
                // (bomMaterialRowClassifierIds, см. mapping.bomMaterials.materialConditions),
                // childClassifierId трактуется как код классификатора "Материала по КД" и
                // откладывается на BomMaterialsEngine, а не молча теряется, как обычная
                // нерезолвленная связь.
                if (rowData.analogGroup != null) {
                    // Потомок группы аналогов не резолвился в структуре (например строка раздела
                    // "Материалы", для которого нет типового правила types[]), но код
                    // классификатора может быть найден в ПОЛИНОМ напрямую — откладывается на
                    // AnalogGroupsEngine.resolveUnresolvedAnalogGroupCandidates, а не молча
                    // теряется, как обычная нерезолвленная связь.
                    //
                    // ПРОВЕРЯЕТСЯ ПЕРВЫМ, до bomMaterialCandidates ниже: реальный инцидент — пара
                    // одновременно проходила и как DS-материал (родитель в bomMaterialSpecClassifierIds
                    // И потомок в bomMaterialRowClassifierIds), и как участник группы аналогов;
                    // при else if бизнес-ветки считались взаимоисключающими, и группа аналогов
                    // молча терялась — при этом объект создавался бы ЕЩЁ РАЗ (второй createBoObject
                    // на тот же location в BomMaterialsEngine, конфликт по уникальному индексу).
                    // Явная группа аналогов — более специфичный сигнал, приоритет за ней.
                    val info = rowData.analogGroup
                    println(
                        "Группа аналогов: потомок $childClassifierId (родитель $parentClassifierId, группа " +
                            "${info.groupNumber}-${info.variantNumber}) не резолвился в структуре — отложен на fallback"
                    )
                    parents.forEach { parent ->
                        unresolvedAnalogGroupCandidates.add(
                            UnresolvedAnalogGroupCandidate(
                                parentLoodsmanId = parent.loodsmanId,
                                childClassifierCode = childClassifierId.toString(),
                                groupNumber = info.groupNumber,
                                variantNumber = info.variantNumber,
                                isBasic = info.isBasic,
                                quantity = rowData.quantity,
                                unitDesignation = rowData.unitDesignation,
                            )
                        )
                    }
                } else if (parentClassifierId in bomMaterialSpecClassifierIds && childClassifierId in bomMaterialRowClassifierIds) {
                    parents.forEach { parent ->
                        bomMaterialCandidates.add(
                            BomMaterialCandidate(parent.loodsmanId, childClassifierId.toString(), rowData.quantity, rowData.unitDesignation)
                        )
                    }
                }
                return@flatMap emptyList()
            }
            parents.flatMap { parent ->
                children2.map { child ->
                    rowData.analogGroup?.let { info ->
                        println(
                            "Группа аналогов: кандидат добавлен напрямую (родитель ${parent.loodsmanId}, потомок " +
                                "${child.loodsmanId}, группа ${info.groupNumber}-${info.variantNumber}, isBasic=${info.isBasic})"
                        )
                        analogGroupCandidates.add(
                            AnalogGroupCandidate(
                                parentLoodsmanId = parent.loodsmanId,
                                childLoodsmanId = child.loodsmanId,
                                groupNumber = info.groupNumber,
                                variantNumber = info.variantNumber,
                                isBasic = info.isBasic,
                            )
                        )
                    }
                    LinkPair(parent, child, rowData.quantity, rowData.unitDesignation)
                }
            }
        }
    }

    // Резолв идёт по уникальным обозначениям, а не по каждой связи — одно и то же обозначение
    // ("шт", "м2") обычно повторяется в сотнях строк. Конкурентно, но без нового Semaphore —
    // троттлинг только через Client.requestGate, как везде в движке.
    val distinctDesignations = linkPairs.mapNotNull { it.unitDesignation }.toSet()
    val unitByDesignation = coroutineScope {
        distinctDesignations.map { designation ->
            async { designation to resolveUnitId(designation, unitsNotFound, unitsCollision) }
        }.awaitAll()
    }.toMap()

    val linksCreated = coroutineScope {
        linkPairs.map { pair ->
            async {
                val unitId = pair.unitDesignation?.let { unitByDesignation[it] }
                if (unitId != null) unitsAssigned.incrementAndGet()
                // childLinkType (например "Технологическая ДСЕ для") — правило связывания в
                // Loodsman задано в обратную сторону относительно обычного BOM: субъект связи —
                // сам объект-потомок (например "Технологическая деталь"), а не структурный
                // родитель листа "Связи". Реальный инцидент: попытка создать связь в обычном
                // направлении (родитель->потомок) с этим типом падает 500 "Нарушение правил
                // связывания объектов".
                //
                // Частный случай: родитель по листу "Связи" САМ является объектом с childLinkType
                // (т.е. тоже "Технологическая деталь") — тогда это не структурная ДСЕ-связь, а
                // "Технологическая деталь входит в Технологическую деталь", и тип связи другой
                // ("Изготавливается из ...", childOfSameTypeLinkType), направление обычное
                // (родитель->потомок, без реверса), как у материалов.
                val childLinkType = pair.child.childLinkType
                val childOfSameTypeLinkType = pair.child.childOfSameTypeLinkType
                when {
                    childLinkType != null && pair.parent.childLinkType != null && childOfSameTypeLinkType != null -> {
                        linkObjects(pair.parent.loodsmanId, pair.child.loodsmanId, childOfSameTypeLinkType, linksFailed, pair.quantity, unitId)
                    }
                    childLinkType != null -> {
                        linkObjects(pair.child.loodsmanId, pair.parent.loodsmanId, childLinkType, linksFailed, pair.quantity, unitId)
                    }
                    else -> {
                        linkObjects(pair.parent.loodsmanId, pair.child.loodsmanId, linksSheet.linkType, linksFailed, pair.quantity, unitId)
                    }
                }
            }
        }.awaitAll()
    }.count { it }

    println(
        "Связи: строк ${dataRows.size}, пар для линковки ${linkPairs.size}, создано $linksCreated, " +
            "ошибок ${linksFailed.get()}, единиц измерения назначено ${unitsAssigned.get()}, " +
            "обозначение не найдено ${unitsNotFound.get()}, коллизий обозначения ${unitsCollision.get()}, " +
            "кандидатов на Материал по КД ${bomMaterialCandidates.size}, " +
            "кандидатов на группы аналогов ${analogGroupCandidates.size}, " +
            "ошибок групп аналогов ${analogGroupsFailed.get()}"
    )

    runAnalogGroupsMigration()
}

// Без private — переиспользуется в MaterialsEngine.kt (runBomMaterialsMigrationInternal) для
// резолва unit'а материалов по КД, связанных под DS-объектами; тот же реестр Measure/
// units-by-designation, та же логика "не найдено/коллизия -> null, лог, не ошибка".
//
// preferredMeasureName — НЕ фильтр (нельзя резать все совпадения по величине заранее: у "Нормы
// расхода" величина сама по себе не фиксирована, "м2"/"м3" — валидные обозначения ДРУГИХ величин,
// не "Масса" — их и резать нельзя). Используется только как тай-брейк, когда designation реально
// неоднозначен (>1 совпадения) — реальный инцидент: "г" одновременно существует в величинах
// "Масса" И "Год". Если среди совпадений ровно одно с этой величиной — берём его; если совпадений
// одно и без того (обычный случай, никакой неоднозначности) — оно берётся как раньше, независимо
// от preferredMeasureName; если неоднозначность не по этой величине (или несколько совпадений С
// этой величиной) — коллизия как раньше, лог + null.
suspend fun MigrationContext.resolveUnitId(
    designation: String,
    unitsNotFound: AtomicInteger,
    unitsCollision: AtomicInteger,
    preferredMeasureName: String? = null,
): String? {
    val matches = loodsmanClient.measure.unitsByDesignation(sessionId, designation)
    return when {
        matches.isEmpty() -> {
            unitsNotFound.incrementAndGet()
            System.err.println("Единица измерения '$designation' не найдена в Measure/units-by-designation")
            null
        }
        matches.size == 1 -> matches.single().id
        else -> {
            val preferred = preferredMeasureName?.let { name -> matches.filter { it.measureName == name } }
            if (preferred?.size == 1) {
                preferred.single().id
            } else {
                unitsCollision.incrementAndGet()
                System.err.println(
                    "Обозначение '$designation' неоднозначно (${matches.size} совпадений в разных величинах) — " +
                        "unit для этих связей не проставляется"
                )
                null
            }
        }
    }
}

// Стандартные/Прочие изделия (mapping.types[].resolveViaPolynom) — тот же путь, что materials/
// AnalogGroups fallback: поиск по коду классификатора в ПОЛИНОМ (общий с mapping.materials
// справочник "Коды", по решению пользователя не заводить отдельную настройку), create-bo-object
// по location. Без сравнения обозначений (в отличие от resolveOrCreateMaterial) и без отдельного
// кэша-дедупликации — group by classifierId уже даёт ровно один resolve на уникальный код, тот же
// приём, что в AnalogGroupsEngine.resolveUnresolvedAnalogGroupCandidates. Состояние объекту не
// проставляется явно — Loodsman резолвит его сам через настроенную в админке привязку типа к
// свойству "применяемость" ПОЛИНОМ (тот же механизм, что уже используется для "Материал по КД").
private suspend fun MigrationContext.resolvePolynomBackedObjects(candidates: List<PolynomBackedObjectCandidate>) {
    if (candidates.isEmpty()) return

    val materials = settings.mapping.materials
    val classifierCodeProperty = materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(materials.classifierCodePropertyAbsoluteCode)
    val searchScope = resolveSearchScope()

    val objectsCreated = AtomicInteger(0)
    val notFoundInPolynom = AtomicInteger(0)
    val failures = AtomicInteger(0)
    val byClassifierId = candidates.groupBy { it.classifierId }

    val resolved = coroutineScope {
        byClassifierId.entries.map { (classifierId, group) ->
            async {
                val target = group.first().target
                try {
                    val found = callPolynom { token ->
                        polynomClient.search.searchByStringProperty(
                            accessToken = token,
                            scope = searchScope,
                            propertyDefinition = classifierCodeProperty,
                            value = classifierId.toString(),
                        )
                    }.firstOrNull()
                    if (found == null) {
                        notFoundInPolynom.incrementAndGet()
                        println("'$target': код классификатора '$classifierId' не найден в ПОЛИНОМ, объект не создан")
                        return@async null
                    }
                    val location = callPolynom { token ->
                        polynomClient.classification.getLocation(
                            token, IdentifiableObjectDto(found.objectId, found.typeId)
                        )
                    }
                    val created = loodsmanClient.editObject.createBoObject(
                        sessionId,
                        CreateBoObjectInputDto(type = target, location = location, withLinks = false)
                    )
                    objectsCreated.incrementAndGet()
                    Identifier(loodsmanId = created, classifierId = classifierId)
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println("Не удалось создать '$target' по коду классификатора '$classifierId': ${e.message}")
                    null
                }
            }
        }.awaitAll()
    }.filterNotNull()

    identifiers.addAll(resolved)
    println(
        "Объекты через ПОЛИНОМ (Стандартные/Прочие изделия): кодов ${byClassifierId.size}, " +
            "создано ${objectsCreated.get()}, не найдено в ПОЛИНОМ ${notFoundInPolynom.get()}, ошибок ${failures.get()}"
    )
}

private data class ObjectRowResult(
    val identifiers: List<Identifier>,
    val materialCandidates: List<MaterialCandidate>,
    val materialSubstituteCandidates: List<MaterialCandidate> = emptyList(),
    val bomMaterialClassifierId: Long? = null,
    val bomMaterialRowClassifierId: Long? = null,
    val polynomBackedCandidates: List<PolynomBackedObjectCandidate> = emptyList(),
    val blankCandidates: List<BlankCandidate> = emptyList(),
    val classificationCandidates: List<ObjectClassificationCandidate> = emptyList(),
)

private suspend fun MigrationContext.processObjectRow(
    row: RowView,
    objectsCreated: AtomicInteger,
    rowsFailed: AtomicInteger
): ObjectRowResult {
    val bomMaterials = settings.mapping.bomMaterials
    val classifierIdForRow = row.value(settings.mapping.identifierColumn)?.toLongOrNull()

    // Строка раздела "Материалы" (mapping.bomMaterials.materialConditions, например
    // "Раздел спецификации"="Материалы") обычно НЕ матчит ни одно mapping.types[] правило (нет
    // типа для этого раздела) и ниже вернётся без создания объекта — но её classifierId должен
    // попасть в bomMaterialRowClassifierIds ДО этого early return, иначе runLinksMigration()
    // никогда не увидит такие строки (см. BomMaterialsEngine).
    val bomMaterialRowClassifierId = if (
        classifierIdForRow != null &&
        ConditionsEvaluator.isConfigured(bomMaterials.materialConditions) &&
        ConditionsEvaluator.matches(bomMaterials.materialConditions, row)
    ) classifierIdForRow else null

    val matches = settings.mapping.types.filter { ConditionsEvaluator.matches(it.conditions, row) }
    if (matches.isEmpty()) {
        return ObjectRowResult(emptyList(), emptyList(), bomMaterialRowClassifierId = bomMaterialRowClassifierId)
    }

    // Правила с resolveViaPolynom (Стандартное/Прочее изделие) не создают объект здесь — объект
    // резолвится по коду классификатора через ПОЛИНОМ батчем после того, как все строки Excel уже
    // прочитаны (см. resolvePolynomBackedObjects в runObjectsMigration), поэтому source/keyAttr
    // для них не читается вовсе.
    val (polynomRules, directRules) = matches.partition { it.resolveViaPolynom }
    val polynomBackedCandidatesForRow = if (classifierIdForRow == null) {
        emptyList()
    } else {
        polynomRules.map { PolynomBackedObjectCandidate(classifierIdForRow, it.target) }
    }

    val createdObjects = directRules.mapNotNull { mappingElement ->
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
    val materialTargetObjects = createdObjects.filter { (mappingElement, _) -> mappingElement.target in materials.appliesToTargets }
    val materialCandidatesForRow = materialTargetObjects
        .map { (_, loodsmanId) ->
            MaterialCandidate(
                detailLoodsmanId = loodsmanId,
                drawingDesignation = row.value(materials.drawingDesignationColumn),
                classifierCode = row.value(materials.classifierCodeColumn),
                detailClassifierCode = row.value(settings.mapping.identifierColumn),
            )
        }

    // Материал-заменитель (mapping.materials.substituteDrawingDesignationColumn) — второй,
    // независимый кандидат на ту же деталь: тот же classifierCode (см. решение пользователя —
    // отдельного столбца кода для заменителя нет), но другое обозначение по чертежу. Кладётся в
    // ОТДЕЛЬНЫЙ список (materialSubstituteCandidates), а не в materialCandidatesForRow — иначе
    // основной и заменитель схлопнулись бы в одну группу дедупликации по общему classifierCode
    // и связался бы только один из двух (см. MaterialsEngine.runMaterialsMigrationInternal).
    // Ячейка должна быть непустой: classifierCode общий с основным материалом и почти всегда
    // непустой сам по себе, поэтому дедупликация по нему (dedupKey в MaterialCandidate) НЕ спасает
    // от фантомного кандидата на каждую деталь без заменителя — без явной проверки на пустую
    // ячейку резолв (поиск в ПОЛИНОМ, лог "пропущен"/попытка создать) гонялся бы вообще на все
    // детали подряд, а не только на те, где заменитель реально указан.
    val substituteDesignation = materials.substituteDrawingDesignationColumn
        .takeIf { it.isNotBlank() }
        ?.let { row.value(it) }
    val materialSubstituteCandidatesForRow = if (substituteDesignation == null) {
        emptyList()
    } else {
        materialTargetObjects.map { (_, loodsmanId) ->
            MaterialCandidate(
                detailLoodsmanId = loodsmanId,
                drawingDesignation = substituteDesignation,
                classifierCode = row.value(materials.classifierCodeColumn),
                detailClassifierCode = row.value(settings.mapping.identifierColumn),
            )
        }
    }

    // Заготовка + материал основной (mapping.blanks) — независимый поток, добавленный к потоку A
    // выше: тот же classifierCodeColumn, но собственный список таргетов (blanks.appliesToTargets,
    // фолбэк на materials.appliesToTargets если не задан — см. BlanksSettings.kt), и триггерится
    // только когда код классификатора на строке реально непустой (в отличие от потока A, где
    // пустой код просто ведёт к фолбэку на создание элемента по имени). blanks.target.isBlank()
    // выключает фичу целиком.
    val blanks = settings.mapping.blanks
    val blankTargetObjects = createdObjects.filter { (mappingElement, _) ->
        mappingElement.target in blanks.appliesToTargets.ifEmpty { materials.appliesToTargets }
    }
    val blankCandidatesForRow = if (blanks.target.isBlank()) {
        emptyList()
    } else {
        blankTargetObjects.mapNotNull { (mappingElement, loodsmanId) ->
            val classifierCode = row.value(materials.classifierCodeColumn)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val designation = row.value(mappingElement.source) ?: return@mapNotNull null

            // Норма расхода на будущую связь заготовка -> материал основной (не reused
            // resolveLinkQuantity — её "пустая колонка = 1.0" здесь не нужна, пустая колонка
            // должна значить "не трогать вообще", а не молчаливый дефолт 1.0). Колонка настроена,
            // но ячейка не читается как число — не блокирует создание заготовки/материала
            // (решение пользователя), только предупреждение в лог.
            val rate = blanks.rateColumn.takeIf { it.isNotBlank() }?.let { column ->
                val parsed = row.value(column)?.replace(",", ".")?.toDoubleOrNull()
                if (parsed == null) {
                    System.err.println(
                        "Заготовки: норма расхода не прочитана в столбце '$column' (деталь " +
                            "'$designation', код классификатора материала '$classifierCode') — " +
                            "заготовка/материал будут созданы без нормы"
                    )
                }
                parsed
            }
            val rateUnitDesignation = resolveLinkUnitDesignation(blanks.rateUnitColumn, emptyList(), row)

            BlankCandidate(loodsmanId, designation, classifierCode, rate, rateUnitDesignation)
        }
    }

    // В identifiers (резолв листа "Связи") попадают только не-папочные объекты — папка не должна
    // становиться родителем в структуре, только организационным контейнером.
    val identifiersForRow = if (classifierIdForRow == null) {
        emptyList()
    } else {
        nonFolderObjects.map { (mappingElement, loodsmanId) ->
            Identifier(
                loodsmanId = loodsmanId,
                classifierId = classifierIdForRow,
                designation = row.value(mappingElement.source) ?: "",
                childLinkType = mappingElement.childLinkType,
                childOfSameTypeLinkType = mappingElement.childOfSameTypeLinkType,
            )
        }
    }

    // Классификация в ПОЛИНОМ (BoReference/reference-bo-version) — только не-папочные объекты,
    // созданные здесь обычным EditObject/new-object; папка организационный контейнер, в BOM не
    // участвует и классифицировать её нечем (см. classifyCreatedObjects в runObjectsMigration).
    val classificationCandidatesForRow = if (classifierIdForRow == null) {
        emptyList()
    } else {
        nonFolderObjects.map { (_, loodsmanId) ->
            ObjectClassificationCandidate(loodsmanId = loodsmanId, classifierId = classifierIdForRow)
        }
    }

    // Признак "у объекта есть собственная конструкторская спецификация" (DS) — см.
    // mapping.bomMaterials в settings.json. specificationConditions не задан вовсе (isConfigured
    // == false) значит фича выключена — Conditions() по умолчанию матчит любую строку, поэтому
    // isConfigured проверяется явно, а не просто ConditionsEvaluator.matches(...).
    val bomMaterialClassifierId = if (
        classifierIdForRow != null &&
        ConditionsEvaluator.isConfigured(bomMaterials.specificationConditions) &&
        ConditionsEvaluator.matches(bomMaterials.specificationConditions, row)
    ) classifierIdForRow else null

    return ObjectRowResult(
        identifiersForRow,
        materialCandidatesForRow,
        materialSubstituteCandidatesForRow,
        bomMaterialClassifierId,
        bomMaterialRowClassifierId,
        polynomBackedCandidatesForRow,
        blankCandidatesForRow,
        classificationCandidatesForRow,
    )
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
    quantity: Double = 1.0,
    unitId: String? = null
): Boolean = try {
    loodsmanClient.editObject.newLink(
        sessionId,
        NewLinkInputDto(
            parentVersionId = parentLoodsmanId,
            childVersionId = childLoodsmanId,
            linkType = linkType,
            minQuantity = quantity,
            maxQuantity = quantity,
            unitId = unitId
        )
    )
    true
} catch (e: Exception) {
    failCounter.incrementAndGet()
    System.err.println("Не удалось создать связь ($parentLoodsmanId -> $childLoodsmanId): ${e.message}")
    false
}

// Без private — переиспользуется в CastingBlanksEngine.kt (свой отдельный лист Excel, тот же
// источник settings.mapping.source).
fun MigrationContext.excelInputStream() = File(settings.mapping.source.path).inputStream()
