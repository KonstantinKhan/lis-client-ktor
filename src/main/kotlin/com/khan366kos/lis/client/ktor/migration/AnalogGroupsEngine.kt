package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.AnalogGroupCandidate
import com.khan366kos.lis.client.ktor.domain.AnalogGroupsSettings
import com.khan366kos.lis.client.ktor.domain.MaterialsSettings
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.UnresolvedAnalogGroupCandidate
import com.khan366kos.lis.client.ktor.loodsman.api.dto.CreateBoObjectInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewChangeGroup2InputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewChangeVariant2InputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

// Тот же код группы (2), что CHANGE_GROUP_TYPE_MATERIAL в MaterialsEngine.kt — подтверждено
// пользователем как один и тот же тип группы замены в Loodsman, новый тип не заводим.
private const val CHANGE_GROUP_TYPE_ANALOG = 2

// Постобработка runLinksMigration(): analogGroupCandidates собраны там (flatMap classifierLinks ->
// linkPairs) уже ПОСЛЕ фактического создания структурных связей (linkObjects(linkPairs, ...)) —
// idLink, нужный для new-change-variant-2, существует только у реально созданных связей. Вызывается
// напрямую из конца runLinksMigration(), не отдельным шагом пайплайна — см. решение пользователя
// (нет отдельного резолва через ПОЛИНОМ, только Loodsman, над уже полностью резолвленными данными).
//
// ПОСЛЕДОВАТЕЛЬНО по группам (parentLoodsmanId, groupNumber), без async/awaitAll — тот же
// подтверждённый инцидент, что в MaterialsEngine.createSubstituteChangeGroups: конкурентные вызовы
// ObjectConfiguration/new-change-group-2 (даже на разные объекты) роняют Postgres в deadlock (40P01)
// внутри хранимой процедуры Loodsman. Групп на прогон обычно немного, последовательность не
// бутылочное горлышко.
suspend fun MigrationContext.runAnalogGroupsMigration() {
    if (unresolvedAnalogGroupCandidates.isNotEmpty()) {
        resolveUnresolvedAnalogGroupCandidates()
    }

    if (analogGroupCandidates.isEmpty()) {
        println("Группы аналогов: кандидатов нет, пропускаем")
        return
    }

    val analogGroups = settings.mapping.analogGroups
    val groupsByKey = analogGroupCandidates.groupBy { it.parentLoodsmanId to it.groupNumber }

    val groupsCreated = AtomicInteger(0)
    val variantsCreated = AtomicInteger(0)
    val groupsSkipped = AtomicInteger(0)
    val failures = AtomicInteger(0)

    groupsByKey.forEach { (key, variants) ->
        createAnalogChangeGroup(
            key.first, key.second, variants, analogGroups,
            groupsCreated, variantsCreated, groupsSkipped, failures,
        )
    }

    println(
        "Группы аналогов: групп-кандидатов ${groupsByKey.size}, создано групп ${groupsCreated.get()}, " +
            "создано вариантов ${variantsCreated.get()}, пропущено групп целиком ${groupsSkipped.get()}, " +
            "ошибок ${failures.get()}"
    )
}

private suspend fun MigrationContext.createAnalogChangeGroup(
    parentLoodsmanId: Int,
    groupNumber: Int,
    variants: List<AnalogGroupCandidate>,
    analogGroups: AnalogGroupsSettings,
    groupsCreated: AtomicInteger,
    variantsCreated: AtomicInteger,
    groupsSkipped: AtomicInteger,
    failures: AtomicInteger,
) {
    try {
        // Один get-linked-fast на родителя закрывает все варианты группы разом — тот же приём,
        // что MaterialsEngine.createSubstituteChangeGroup.
        val links = loodsmanClient.objectInfo.linkedFast(sessionId, parentLoodsmanId, settings.mapping.linksSheet.linkType)
        val idLinkByChild = variants.associate { variant ->
            variant.childLoodsmanId to links.firstOrNull { it.idVersion == variant.childLoodsmanId }?.idLink
        }
        val missing = variants.filter { idLinkByChild[it.childLoodsmanId] == null }

        if (missing.isNotEmpty()) {
            // Группа не создаётся частично, если хотя бы один вариант не резолвится в idLink — та
            // же политика "без частичных групп", что у групп замены материала (там ровно 2
            // варианта), распространённая здесь на N вариантов.
            groupsSkipped.incrementAndGet()
            System.err.println(
                "Группа аналогов (родитель $parentLoodsmanId, группа $groupNumber) пропущена целиком: " +
                    "не найдена связь через get-linked-fast для childLoodsmanId=" +
                    missing.joinToString { it.childLoodsmanId.toString() }
            )
            return
        }

        val changeGroupId = loodsmanClient.objectConfiguration.newChangeGroup2(
            sessionId,
            NewChangeGroup2InputDto(
                versionId = parentLoodsmanId,
                changeGroupName = analogGroups.changeGroupNameTemplate.replace("{group}", groupNumber.toString()),
                groupType = CHANGE_GROUP_TYPE_ANALOG,
            )
        ).asInt()
        groupsCreated.incrementAndGet()

        variants.forEach { variant ->
            loodsmanClient.objectConfiguration.newChangeVariant2(
                sessionId,
                NewChangeVariant2InputDto(
                    changeGroupId = changeGroupId,
                    changeVariantName = analogGroups.variantNameTemplate.replace("{variant}", variant.variantNumber.toString()),
                    linkFirstVariantId = idLinkByChild.getValue(variant.childLoodsmanId)!!,
                    isBasic = variant.isBasic,
                )
            )
            variantsCreated.incrementAndGet()
        }
    } catch (e: Exception) {
        failures.incrementAndGet()
        System.err.println("Не удалось создать группу аналогов (родитель $parentLoodsmanId, группа $groupNumber): ${e.message}")
        (e as? ResponseException)?.let {
            System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
        }
    }
}

// Fallback для потомков групп аналогов, не резолвившихся ни в один объект шага "Объекты"
// (childClassifierId листа "Связи" не нашёл совпадения в identifiers) — типичный случай: строка
// раздела "Материалы", для которого нет типового правила mapping.types[] (тот же сценарий, что у
// bomMaterialCandidates/BomMaterialsEngine), но код классификатора есть в ПОЛИНОМ напрямую.
// Реальный инцидент: часть материалов-заменителей из "код классификатора входящего объекта"
// оказывалась не созданной в структуре Лоцман, хотя объект в ПОЛИНОМ существует — без этого шага
// такие варианты групп аналогов молча терялись бы, как обычная нерезолвленная связь.
//
// По коду классификатора: поиск в ПОЛИНОМ (те же classifierCodeProperty/searchScope, что у
// mapping.materials — по решению пользователя не заводить отдельную настройку, "код
// классификатора" один смысл на весь проект), создание объекта В ЛОЦМАН напрямую обычным
// NewObjectInputDto (НЕ через createBoObject/location, как материалы — по решению пользователя).
// Тип — тоже mapping.materials.materialTarget (по решению пользователя:
// потомок группы аналогов, не резолвившийся в структуре, ЭТО материал-заменитель, а не деталь —
// реальный инцидент: fallback-объект создавался с типом "Деталь", из-за чего группа замены
// (groupType=2 — "материал") у Лоцман отображалась с одним вариантом вместо ожидаемых N).
// Ключевой атрибут (обозначение) — свойство "наименование" найденного элемента ПОЛИНОМ
// (PropertySearchItemDto.name).
private suspend fun MigrationContext.resolveUnresolvedAnalogGroupCandidates() {
    val materials = settings.mapping.materials
    val classifierCodeProperty = materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(materials.classifierCodePropertyAbsoluteCode)
    val searchScope = resolveSearchScope()

    val objectsCreated = AtomicInteger(0)
    val linksCreated = AtomicInteger(0)
    val notFoundInPolynom = AtomicInteger(0)
    val unitsNotFound = AtomicInteger(0)
    val unitsCollision = AtomicInteger(0)
    val failures = AtomicInteger(0)

    // Резолв идёт по уникальному коду классификатора один раз — несколько кандидатов (разные
    // родители/группы) могут ссылаться на один и тот же недостающий объект.
    val byClassifierCode = unresolvedAnalogGroupCandidates.groupBy { it.childClassifierCode }

    val distinctDesignations = unresolvedAnalogGroupCandidates.mapNotNull { it.unitDesignation }.toSet()
    val unitByDesignation = coroutineScope {
        distinctDesignations.map { designation ->
            async { designation to resolveUnitId(designation, unitsNotFound, unitsCollision) }
        }.awaitAll()
    }.toMap()

    val loodsmanIdByCode = coroutineScope {
        byClassifierCode.keys.map { code ->
            async {
                code to try {
                    resolveOrCreateAnalogFallbackObject(code, classifierCodeProperty, searchScope, materials, objectsCreated)
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println("Группы аналогов: не удалось создать объект по коду классификатора '$code': ${e.message}")
                    null
                }
            }
        }.awaitAll()
    }.toMap()

    // Резолвленные кандидаты собираются здесь и добавляются в analogGroupCandidates ПОСЛЕ
    // awaitAll() — сами корутины ничего не пишут в общий MutableList напрямую (та же причина,
    // что у identifiers/materialCandidates в runObjectsMigration: конкурентная запись в один
    // MutableList из нескольких корутин не потокобезопасна).
    val resolved = coroutineScope {
        byClassifierCode.entries.map { (code, candidates) ->
            async {
                val childLoodsmanId = loodsmanIdByCode[code]
                if (childLoodsmanId == null) {
                    notFoundInPolynom.incrementAndGet()
                    println("Группы аналогов: код классификатора '$code' не найден в ПОЛИНОМ, ${candidates.size} кандидат(ов) пропущено")
                    return@async emptyList<AnalogGroupCandidate>()
                }
                candidates.map { candidate ->
                    async {
                        val unitId = candidate.unitDesignation?.let { unitByDesignation[it] }
                        val linked = try {
                            loodsmanClient.editObject.newLink(
                                sessionId,
                                NewLinkInputDto(
                                    parentVersionId = candidate.parentLoodsmanId,
                                    childVersionId = childLoodsmanId,
                                    linkType = settings.mapping.linksSheet.linkType,
                                    minQuantity = candidate.quantity,
                                    maxQuantity = candidate.quantity,
                                    unitId = unitId,
                                )
                            )
                            true
                        } catch (e: Exception) {
                            failures.incrementAndGet()
                            System.err.println(
                                "Группы аналогов: не удалось связать fallback-объект ($childLoodsmanId) с родителем " +
                                    "(${candidate.parentLoodsmanId}): ${e.message}"
                            )
                            false
                        }
                        if (linked) {
                            linksCreated.incrementAndGet()
                            AnalogGroupCandidate(
                                parentLoodsmanId = candidate.parentLoodsmanId,
                                childLoodsmanId = childLoodsmanId,
                                groupNumber = candidate.groupNumber,
                                variantNumber = candidate.variantNumber,
                                isBasic = candidate.isBasic,
                            )
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }.awaitAll().flatten()
    }
    analogGroupCandidates.addAll(resolved)

    println(
        "Группы аналогов (fallback через ПОЛИНОМ): кодов ${byClassifierCode.size}, создано объектов " +
            "${objectsCreated.get()}, создано связей ${linksCreated.get()}, не найдено в ПОЛИНОМ ${notFoundInPolynom.get()}, " +
            "ошибок ${failures.get()}"
    )
}

private suspend fun MigrationContext.resolveOrCreateAnalogFallbackObject(
    classifierCode: String,
    classifierCodeProperty: IdentifiableObjectDto,
    searchScope: IdentifiableObjectDto,
    materials: MaterialsSettings,
    objectsCreated: AtomicInteger,
): Int? {
    val found = polynomClient.search.searchByStringProperty(
        accessToken = polynomAccessToken,
        scope = searchScope,
        propertyDefinition = classifierCodeProperty,
        value = classifierCode,
    ).firstOrNull() ?: return null

    // Реальный инцидент: EditObject/new-object (обычное создание объекта, keyAttribute из
    // "наименование" ПОЛИНОМ) падает 500 "Метод NewObject неприменим для создания
    // интегрированных с ПОЛИНОМ:MDM объектов. Используйте метод NewBoObject." — materialTarget
    // ("Материал по КД") в этом экземпляре Лоцман настроен как ПОЛИНОМ-интегрированный тип,
    // создаётся ТОЛЬКО через createBoObject/location (тот же путь, что resolveOrCreateMaterial и
    // resolveBomMaterialByClassifierCode в MaterialsEngine.kt), не через обычный NewObjectInputDto.
    // Обозначение при этом берётся не отдельным атрибутом, а автоматически из location-привязки
    // к найденному элементу ПОЛИНОМ (его "наименование") — тот же результат, другим путём.
    val element = IdentifiableObjectDto(found.objectId, found.typeId)
    val location = polynomClient.classification.getLocation(polynomAccessToken, element)
    val created = loodsmanClient.editObject.createBoObject(
        sessionId,
        CreateBoObjectInputDto(type = materials.materialTarget, location = location, withLinks = false)
    )
    objectsCreated.incrementAndGet()
    return created
}
