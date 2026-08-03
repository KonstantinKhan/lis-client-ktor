package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MaterialCandidate
import com.khan366kos.lis.client.ktor.domain.MaterialsSettings
import com.khan366kos.lis.client.ktor.domain.MigrationContext
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

// Постобработка после runObjectsMigration()/runLinksMigration(): materialCandidates уже
// полностью собраны (по одному на каждую строку "Деталь", см. processObjectRow), здесь они
// группируются по ключу дедупликации и обрабатываются один раз на уникальный материал.
suspend fun MigrationContext.runMaterialsMigration() {
    // PolynomClient собран с expectSuccess=true — не-2xx ответы прилетают как ResponseException;
    // печатаем статус+тело здесь (bodyAsText — suspend, в except{} воркера так нельзя, см.
    // workers/MigrationWorkers.kt), иначе видно только обрезанное сообщение исключения.
    try {
        runMaterialsMigrationInternal()
    } catch (e: ResponseException) {
        System.err.println("HTTP ${e.response.status.value}: ${e.response.bodyAsText()}")
        throw e
    }
}

private suspend fun MigrationContext.runMaterialsMigrationInternal() {
    if (materialCandidates.isEmpty() && materialSubstituteCandidates.isEmpty()) {
        println("Материалы: строк-кандидатов нет, пропускаем")
        return
    }

    val materials = settings.mapping.materials
    // Прямые id из settings.json в приоритете; иначе — резолв по полному коду (absoluteCode),
    // полученному из админки Полином, через get-by-absolute-code (без concept-скоупа, без
    // предварительного поиска понятия — единственный из перепробованных путей резолва, который
    // реально работает: concept/get-all виснет насмерть, get-by-code требует concept и даёт 404
    // даже с верным понятием, concept-property-source/get-by-id 404 даже на id из
    // get-property-sources того же понятия — все три подтверждены нерабочими на боевой системе).
    // Общий для основных материалов и заменителей (mapping.materials.substituteDrawingDesignationColumn) —
    // это тот же classifierCodeColumn/hierarchy/detailLinkType/materialTarget, см. решение пользователя.
    val classifierCodeProperty = materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(materials.classifierCodePropertyAbsoluteCode)
    // ownerScope для search/execute-property-search — без него (null) сервер падает с 500
    // NullReferenceException. Это не справочник/папка, а ПОНЯТИЕ (concept) — см.
    // resolveSearchScope().
    val searchScope = resolveSearchScope()
    // Два независимых мьютекса, каждый защищает свой ресурс, без вложенных withLock на одном и
    // том же Mutex (Kotlin Mutex не реентерабелен — вложенный withLock тем же объектом = deadlock):
    // hierarchyMutex — резолв справочник→каталог→группа (внутри resolveMaterialsGroup);
    // elementCreationMutex — поиск/создание элемента по имени внутри группы (findOrCreateElementInHierarchy);
    // elementCacheMutex — кэш "Полином-элемент -> Loodsman-id" материала (resolveOrCreateMaterial).
    // Общие для обоих проходов (основной + заменитель): если один и тот же элемент Полином
    // резолвится и как чей-то основной материал, и как чей-то заменитель — нужен ровно один
    // Loodsman-объект на него, иначе второй createBoObject упадёт на уникальном индексе.
    val hierarchyMutex = Mutex()
    val elementCreationMutex = Mutex()
    val elementCache = mutableMapOf<Pair<Int, Int>, Int>()
    val elementCacheMutex = Mutex()

    // detailLoodsmanId -> materialLoodsmanId, только для реально созданных связей (linkable),
    // нужно дальше для createSubstituteChangeGroups (деталь должна быть в ОБЕИХ map'ах сразу).
    val mainLinked = processMaterialCandidates(
        "Материалы", materialCandidates, materials, classifierCodeProperty, searchScope,
        hierarchyMutex, elementCreationMutex, elementCache, elementCacheMutex,
    )
    val substituteLinked = if (materialSubstituteCandidates.isNotEmpty()) {
        processMaterialCandidates(
            "Материалы-заменители", materialSubstituteCandidates, materials, classifierCodeProperty, searchScope,
            hierarchyMutex, elementCreationMutex, elementCache, elementCacheMutex,
        )
    } else {
        emptyMap()
    }

    if (substituteLinked.isNotEmpty()) {
        createSubstituteChangeGroups(mainLinked, substituteLinked, materials)
    }
}

// Общий пайплайн резолва/создания "Материала по КД" + линковки к деталям — используется и для
// основных материалов (materialCandidates), и для заменителей (materialSubstituteCandidates),
// см. вызовы в runMaterialsMigrationInternal. ВАЖНО: списки обрабатываются ОТДЕЛЬНЫМИ вызовами
// (не объединяются в один candidatesByKey) — у основного материала и заменителя ОДНОЙ детали
// одинаковый classifierCode (см. решение пользователя), и общая группировка по dedupKey
// схлопнула бы их в одну группу с одним резолвом на двоих, потеряв связь с одним из двух.
private suspend fun MigrationContext.processMaterialCandidates(
    label: String,
    candidates: List<MaterialCandidate>,
    materials: MaterialsSettings,
    classifierCodeProperty: IdentifiableObjectDto,
    searchScope: IdentifiableObjectDto,
    hierarchyMutex: Mutex,
    elementCreationMutex: Mutex,
    elementCache: MutableMap<Pair<Int, Int>, Int>,
    elementCacheMutex: Mutex,
): Map<Int, Int> {
    if (candidates.isEmpty()) {
        println("$label: строк-кандидатов нет, пропускаем")
        return emptyMap()
    }

    // Группировка — синхронно и до запуска корутин, поэтому дальше по каждому уникальному
    // ключу работает ровно одна корутина и гонок при resolve-or-create не возникает.
    val candidatesByKey = candidates.groupBy { it.dedupKey }.filterKeys { it != null }

    val materialsCreated = AtomicInteger(0)
    val linksCreated = AtomicInteger(0)
    val skipped = AtomicInteger(0)
    val detailsNotLinked = AtomicInteger(0)
    val failures = AtomicInteger(0)

    val linkedDetails = coroutineScope {
        candidatesByKey.values.map { group ->
            async {
                // Группа склеена по общему коду классификатора материала (dedupKey) — у её
                // членов может отличаться обозначение по чертежу (разные строки Excel, одна не
                // заполнила его, другая заполнила). Резолв идёт по ОДНОМУ представителю на всю
                // группу, поэтому берём того, у кого обозначение непустое: иначе поиск/сравнение
                // с найденным элементом Полином (findElementByClassifierCode) идёт по пустому
                // значению и вся группа ошибочно помечается пропущенной, хотя другой её член
                // резолвился бы нормально.
                val representative = group.firstOrNull { it.drawingDesignation?.isNotBlank() == true } ?: group.first()
                val materialLoodsmanId = try {
                    resolveOrCreateMaterial(
                        representative,
                        classifierCodeProperty,
                        searchScope,
                        hierarchyMutex,
                        elementCreationMutex,
                        elementCache,
                        elementCacheMutex,
                        materialsCreated,
                    )
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Не удалось создать материал по КД (обозначение='${representative.drawingDesignation}', " +
                            "код классификатора='${representative.classifierCode}'): ${e.message}"
                    )
                    return@async emptyList<Pair<Int, Int>>()
                }
                if (materialLoodsmanId == null) {
                    // Материал создавать не из чего: код классификатора либо пуст, либо не нашёл
                    // совпадения в ПОЛИНОМ (или обозначение найденного элемента не совпало с
                    // чертёжным), И при этом обозначения по чертежу тоже нет (иначе ушли бы в
                    // ветку создания нового элемента) — это не ошибка (см. решение пользователя),
                    // но деталь(и) остаются без "Материала по КД"/заменителя. Печатаем id деталей
                    // и оба исходных значения, чтобы можно было найти строку(и) в Excel и решить,
                    // что с ней делать (данные неполные либо код в ПОЛИНОМ не заведён).
                    skipped.incrementAndGet()
                    // Печатаем код классификатора и Loodsman id каждой ЗАТРОНУТОЙ ДЕТАЛИ (не код
                    // материала-по-сортаменту representative.classifierCode — тот к этому моменту
                    // уже не годится, чтобы идентифицировать строку Excel, ведь именно ОН не дал
                    // совпадения в ПОЛИНОМ).
                    val designation = representative.drawingDesignation
                        ?.takeIf { it.isNotBlank() }
                        ?: "отсутствует"
                    val details = group.joinToString { "${it.detailClassifierCode} (Loodsman id ${it.detailLoodsmanId})" }
                    println(
                        "$label: материал пропущен (нет данных для создания): обозначение по чертежу=" +
                            "'$designation', детали: $details"
                    )
                    return@async emptyList<Pair<Int, Int>>()
                }

                // Материал резолвится ОДИН раз на всю группу (по общему коду классификатора), но
                // линковать к нему нужно только те детали, у которых СВОЁ обозначение по чертежу
                // непустое — деталь без собственного обозначения не должна получать "Материал по
                // КД" вообще, даже если код классификатора совпал с другой деталью и материал
                // успешно резолвился через неё (явное решение пользователя).
                val (linkable, unlinkable) = group.partition { it.drawingDesignation?.isNotBlank() == true }
                if (unlinkable.isNotEmpty()) {
                    detailsNotLinked.addAndGet(unlinkable.size)
                    val details = unlinkable.joinToString { "${it.detailClassifierCode} (Loodsman id ${it.detailLoodsmanId})" }
                    println(
                        "$label: обозначение по чертежу пустое — поиск в ПОЛИНОМ/создание материала для этих " +
                            "деталей не выполнялись: $details"
                    )
                }

                linkable.map { candidate ->
                    async {
                        val linked = linkMaterialToDetail(materialLoodsmanId, candidate, materials.detailLinkType, linksCreated, failures)
                        if (linked) candidate.detailLoodsmanId to materialLoodsmanId else null
                    }
                }.awaitAll().filterNotNull()
            }
        }.awaitAll().flatten()
    }

    println(
        "$label: уникальных ${candidatesByKey.size}, создано объектов ${materialsCreated.get()}, " +
            "связей с деталями ${linksCreated.get()}, пропущено групп ${skipped.get()}, " +
            "деталей без своего обозначения не привязано ${detailsNotLinked.get()}, ошибок ${failures.get()}"
    )

    return linkedDetails.toMap()
}

private suspend fun MigrationContext.linkMaterialToDetail(
    materialLoodsmanId: Int,
    candidate: MaterialCandidate,
    linkType: String,
    linksCreated: AtomicInteger,
    failures: AtomicInteger,
): Boolean = try {
    loodsmanClient.editObject.newLink(
        sessionId,
        NewLinkInputDto(
            parentVersionId = candidate.detailLoodsmanId,
            childVersionId = materialLoodsmanId,
            linkType = linkType,
        )
    )
    linksCreated.incrementAndGet()
    true
} catch (e: Exception) {
    failures.incrementAndGet()
    System.err.println("Не удалось связать материал ($materialLoodsmanId) с деталью (${candidate.detailLoodsmanId}): ${e.message}")
    (e as? ResponseException)?.let {
        System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
    }
    false
}

// Loodsman ObjectConfigurationGroupTypes: 0|1|2 — 2 задано пользователем напрямую (тип группы
// замены "материал"), не вынесено в settings.json в отличие от названий группы/вариантов.
private const val CHANGE_GROUP_TYPE_MATERIAL = 2

// Группа замены "основной материал / материал-заменитель" — создаётся ТОЛЬКО для деталей,
// оказавшихся в ОБЕИХ map'ах сразу (реально резолвился и связался и основной материал, и
// заменитель). Деталь без заменителя (или без основного материала) группу не получает.
//
// ПОСЛЕДОВАТЕЛЬНО, без async/awaitAll — реальный инцидент: ObjectConfiguration/new-change-group-2
// под конкурентными вызовами (даже на РАЗНЫЕ детали) уронил Postgres в deadlock (40P01) внутри
// собственной хранимой процедуры Loodsman (dt_variants.prnewchangegroup -> ... -> блокировка
// tuple в stlocks/stchanges) — в отличие от new-object/new-link (см.
// "Конкурентность" в CLAUDE.md, там конкурентность подтверждена рабочей), этот эндпоинт свою
// блокировку кладёт не построчно-независимо. Деталей с заменителем обычно немного (в отличие от
// общего числа строк Excel), последовательность здесь не бутылочное горлышко.
private suspend fun MigrationContext.createSubstituteChangeGroups(
    mainLinked: Map<Int, Int>,
    substituteLinked: Map<Int, Int>,
    materials: MaterialsSettings,
) {
    val detailsWithBoth = substituteLinked.keys.filter { it in mainLinked }
    if (detailsWithBoth.isEmpty()) {
        println("Группы замены материала: деталей с основным материалом и заменителем нет, пропускаем")
        return
    }

    val groupsCreated = AtomicInteger(0)
    val variantsCreated = AtomicInteger(0)
    val failures = AtomicInteger(0)

    detailsWithBoth.forEach { detailLoodsmanId ->
        createSubstituteChangeGroup(
            detailLoodsmanId,
            mainLinked.getValue(detailLoodsmanId),
            substituteLinked.getValue(detailLoodsmanId),
            materials,
            groupsCreated,
            variantsCreated,
            failures,
        )
    }

    println(
        "Группы замены материала: деталей ${detailsWithBoth.size}, создано групп ${groupsCreated.get()}, " +
            "создано вариантов ${variantsCreated.get()}, ошибок ${failures.get()}"
    )
}

private suspend fun MigrationContext.createSubstituteChangeGroup(
    detailLoodsmanId: Int,
    mainMaterialLoodsmanId: Int,
    substituteMaterialLoodsmanId: Int,
    materials: MaterialsSettings,
    groupsCreated: AtomicInteger,
    variantsCreated: AtomicInteger,
    failures: AtomicInteger,
) {
    try {
        val changeGroupId = loodsmanClient.objectConfiguration.newChangeGroup2(
            sessionId,
            NewChangeGroup2InputDto(
                versionId = detailLoodsmanId,
                changeGroupName = materials.changeGroupName,
                groupType = CHANGE_GROUP_TYPE_MATERIAL,
            )
        ).asInt()
        groupsCreated.incrementAndGet()

        // Обе связи ("Изготавливается из ...") — под ОДНИМ родителем (деталью), поэтому один
        // вызов get-linked-fast возвращает обе сразу; matching — по idVersion (Loodsman id
        // созданного материала), не по product/version (см. docs/link-measure-unit-migration.md
        // — там matching по product/version предлагался как рабочий вариант для чужого случая;
        // здесь у нас уже есть точный Loodsman id обеих сторон, сверка по нему надёжнее).
        val links = loodsmanClient.objectInfo.linkedFast(sessionId, detailLoodsmanId, materials.detailLinkType)
        val mainLinkId = links.firstOrNull { it.idVersion == mainMaterialLoodsmanId }?.idLink
        val substituteLinkId = links.firstOrNull { it.idVersion == substituteMaterialLoodsmanId }?.idLink

        if (mainLinkId == null || substituteLinkId == null) {
            failures.incrementAndGet()
            System.err.println(
                "Группа замены (деталь $detailLoodsmanId): не найдена связь через get-linked-fast " +
                    "(основной материал $mainMaterialLoodsmanId -> idLink=$mainLinkId, " +
                    "заменитель $substituteMaterialLoodsmanId -> idLink=$substituteLinkId)"
            )
            return
        }

        loodsmanClient.objectConfiguration.newChangeVariant2(
            sessionId,
            NewChangeVariant2InputDto(
                changeGroupId = changeGroupId,
                changeVariantName = materials.mainMaterialVariantName,
                linkFirstVariantId = mainLinkId,
                isBasic = true,
            )
        )
        variantsCreated.incrementAndGet()

        loodsmanClient.objectConfiguration.newChangeVariant2(
            sessionId,
            NewChangeVariant2InputDto(
                changeGroupId = changeGroupId,
                changeVariantName = materials.substituteMaterialVariantName,
                linkFirstVariantId = substituteLinkId,
                isBasic = false,
            )
        )
        variantsCreated.incrementAndGet()
    } catch (e: Exception) {
        failures.incrementAndGet()
        System.err.println("Не удалось создать группу замены материала (деталь $detailLoodsmanId): ${e.message}")
        (e as? ResponseException)?.let {
            System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
        }
    }
}

// Возвращает null, если материал создавать не из чего (нет совпадения по коду классификатора
// И нет обозначения по чертежу) — тогда объект "Материал по КД" в Loodsman не создаётся вовсе.
private suspend fun MigrationContext.resolveOrCreateMaterial(
    candidate: MaterialCandidate,
    classifierCodeProperty: IdentifiableObjectDto,
    searchScope: IdentifiableObjectDto,
    hierarchyMutex: Mutex,
    elementCreationMutex: Mutex,
    elementCache: MutableMap<Pair<Int, Int>, Int>,
    elementCacheMutex: Mutex,
    materialsCreated: AtomicInteger,
): Int? {
    val materials = settings.mapping.materials
    val element = findElementByClassifierCode(candidate, classifierCodeProperty, searchScope)
        ?: findOrCreateElementInHierarchy(candidate, hierarchyMutex, elementCreationMutex)
        ?: return null

    // Разные материалы-кандидаты (разные dedupKey) могут разрешиться в один и тот же элемент
    // Полином — кэшируем по его identity, чтобы не создать в Loodsman два объекта с одинаковым
    // location (падает на уникальном индексе, см. runMaterialsMigrationInternal).
    val key = element.objectId to element.typeId
    return elementCacheMutex.withLock {
        elementCache[key]?.let { return@withLock it }

        val location = polynomClient.classification.getLocation(polynomAccessToken, element)
        val created = loodsmanClient.editObject.createBoObject(
            sessionId,
            CreateBoObjectInputDto(type = materials.materialTarget, location = location, withLinks = false)
        )
        elementCache[key] = created
        materialsCreated.incrementAndGet()
        created
    }
}

// Шаг 1-2 бизнес-логики: ищем элемент классификатора по коду (2) и сравниваем его обозначение
// с чертёжным (1). Возвращает null, если код пуст, элемент не найден, либо обозначения не
// совпадают — тогда вызывающий код переходит к ветке со справочником "Материалы по КД".
private suspend fun MigrationContext.findElementByClassifierCode(
    candidate: MaterialCandidate,
    classifierCodeProperty: IdentifiableObjectDto,
    searchScope: IdentifiableObjectDto,
): IdentifiableObjectDto? {
    val code = candidate.classifierCode?.takeIf { it.isNotBlank() } ?: return null
    val found = polynomClient.search.searchByStringProperty(
        accessToken = polynomAccessToken,
        scope = searchScope,
        propertyDefinition = classifierCodeProperty,
        value = code,
    ).firstOrNull() ?: return null

    if (found.name != candidate.drawingDesignation) return null
    return IdentifiableObjectDto(found.objectId, found.typeId)
}

// Возвращает null при пустом обозначении по чертежу — материал в этом случае не создаём (не
// ошибка, см. решение пользователя).
private suspend fun MigrationContext.findOrCreateElementInHierarchy(
    candidate: MaterialCandidate,
    hierarchyMutex: Mutex,
    elementCreationMutex: Mutex,
): IdentifiableObjectDto? {
    val designation = candidate.drawingDesignation?.takeIf { it.isNotBlank() } ?: return null

    val group = resolveMaterialsGroup(hierarchyMutex)

    // Под мьютексом: без него две корутины с одинаковым designation могут одновременно не найти
    // существующий элемент и обе создать новый (дубль в Полином либо гонка на стороне Loodsman
    // при последующем createBoObject с одинаковым location).
    return elementCreationMutex.withLock {
        val existing = polynomClient.classification.getElementsByGroup(polynomAccessToken, group)
            .firstOrNull { it.name == designation }
        if (existing != null) return@withLock IdentifiableObjectDto(existing.objectId, existing.typeId)

        polynomClient.classification.createElement(polynomAccessToken, group, designation)
    }
}

// Справочник → каталог → группа создаются вручную в админке ПОЛИНОМ (правила связывания типов
// между Loodsman и ПОЛИНОМ настраиваются руками и не подхватывают объекты, созданные программно
// через API — см. инцидент с pdmErrorCode 501). Здесь только резолв по имени, без создания;
// если чего-то нет — явная ошибка с указанием, что завести нужно руками. Резолвится один раз за
// весь прогон и кэшируется в MigrationContext.materialsGroupId — под Mutex, т.к. это единственное
// место, где несколько корутин пишут в общее состояние одновременно.
private suspend fun MigrationContext.resolveMaterialsGroup(mutex: Mutex): IdentifiableObjectDto =
    mutex.withLock {
        val cached = materialsGroupId
        if (cached != null) return@withLock cached

        val hierarchy = settings.mapping.materials.hierarchy

        val reference = polynomClient.classification.getAllReferences(polynomAccessToken)
            .firstOrNull { it.name == hierarchy.referenceName }
            ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
            ?: throw IllegalStateException(
                "Справочник «${hierarchy.referenceName}» не найден в ПОЛИНОМ — должен быть создан вручную " +
                    "вместе с правилом связывания типа, программное создание не подхватывается Loodsman"
            )

        val catalog = polynomClient.classification.getCatalogsByReference(polynomAccessToken, reference)
            .firstOrNull { it.name == hierarchy.catalogName }
            ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
            ?: throw IllegalStateException(
                "Каталог «${hierarchy.catalogName}» не найден в справочнике «${hierarchy.referenceName}» — " +
                    "должен быть создан вручную"
            )

        val group = polynomClient.classification.getGroupsByCatalog(polynomAccessToken, catalog)
            .firstOrNull { it.name == hierarchy.groupName }
            ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
            ?: throw IllegalStateException(
                "Группа «${hierarchy.groupName}» не найдена в каталоге «${hierarchy.catalogName}» — " +
                    "должна быть создана вручную"
            )

        materialsGroupId = group
        group
    }

// Резолв property-definition по полному коду (absoluteCode, берётся из админки Полином) —
// один прямой запрос, без concept-скоупа и без предварительного поиска понятия/бутстрапа.
// Без private — переиспользуется в AnalogGroupsEngine.kt (тот же смысл "код классификатора" для
// fallback-создания недостающих объектов групп аналогов, см. решение пользователя не дублировать
// настройку).
suspend fun MigrationContext.resolvePropertyDefinitionByAbsoluteCode(absoluteCode: String): IdentifiableObjectDto {
    val source = polynomClient.concepts.getPropertySourceByAbsoluteCode(polynomAccessToken, absoluteCode)
    return IdentifiableObjectDto(source.objectId, source.typeId)
}

// ownerScope в search/execute-property-search — это НЕ справочник/папка, а ПОНЯТИЕ (concept),
// см. пример GuiSearch.html (Docs.Api): CreateSimpleCondition(concept, propDef, ...) — concept
// там всегда системное понятие вроде "Элемент классификации" (аналог KnownConceptKind.Element),
// а не место расположения данных. Находим его тем же рабочим путём, что раньше использовали для
// бутстрапа property-definition (concept/get-by-concept-appointer на первую группу каталога
// справочника "Коды" — этот эндпоинт всегда работал, ломались только code/id-résolв свойств).
private const val ELEMENT_CONCEPT_NAME = "Элемент классификации"

// Постобработка после runLinksMigration(): bomMaterialCandidates собраны там для родителей,
// отмеченных DS (mapping.bomMaterials), у которых child листа "Связи" не резолвился ни в один
// созданный объект. В отличие от runMaterialsMigration() (сортаментный поток, materials.*): здесь
// НЕТ сравнения найденного элемента с обозначением по чертежу (проверка неприменима — обозначения
// у нас просто нет) и НЕТ фолбэка на создание нового элемента классификатора в ПОЛИНОМ — если код
// классификатора не нашёл совпадения, связь пропускается (лог + счётчик), это осознанное решение,
// не ошибка. Связь создаётся тем же типом, что и обычный BOM ("Состоит из ..." — linksSheet.linkType),
// а не materials.detailLinkType.
suspend fun MigrationContext.runBomMaterialsMigration() {
    try {
        runBomMaterialsMigrationInternal()
    } catch (e: ResponseException) {
        System.err.println("HTTP ${e.response.status.value}: ${e.response.bodyAsText()}")
        throw e
    }
}

private suspend fun MigrationContext.runBomMaterialsMigrationInternal() {
    if (bomMaterialCandidates.isEmpty()) {
        println("Материалы по КД (DS): кандидатов нет, пропускаем")
        return
    }

    val materials = settings.mapping.materials
    val classifierCodeProperty = materials.classifierCodePropertyId
        ?: resolvePropertyDefinitionByAbsoluteCode(materials.classifierCodePropertyAbsoluteCode)
    val searchScope = resolveSearchScope()

    // Свой кэш "Полином-элемент -> Loodsman-id", отдельный от runMaterialsMigration() — разные
    // dedupKey (коды классификатора) здесь тоже могут разрешиться в один и тот же элемент Полином.
    val elementCache = mutableMapOf<Pair<Int, Int>, Int>()
    val elementCacheMutex = Mutex()

    val candidatesByCode = bomMaterialCandidates.groupBy { it.classifierCode }

    val materialsCreated = AtomicInteger(0)
    val linksCreated = AtomicInteger(0)
    val notFound = AtomicInteger(0)
    val failures = AtomicInteger(0)
    val unitsNotFound = AtomicInteger(0)
    val unitsCollision = AtomicInteger(0)
    val unitsAssigned = AtomicInteger(0)

    // Тот же паттерн, что и в runLinksMigration: резолв уникальных обозначений один раз (не по
    // одной связи на обозначение), тот же resolveUnitId (см. MigrationEngine.kt).
    val distinctDesignations = bomMaterialCandidates.mapNotNull { it.unitDesignation }.toSet()
    val unitByDesignation = coroutineScope {
        distinctDesignations.map { designation ->
            async { designation to resolveUnitId(designation, unitsNotFound, unitsCollision) }
        }.awaitAll()
    }.toMap()

    coroutineScope {
        candidatesByCode.entries.map { (classifierCode, group) ->
            async {
                val materialLoodsmanId = try {
                    resolveBomMaterialByClassifierCode(
                        classifierCode,
                        materials.materialTarget,
                        classifierCodeProperty,
                        searchScope,
                        elementCache,
                        elementCacheMutex,
                        materialsCreated,
                    )
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    System.err.println(
                        "Не удалось создать материал по КД (DS) для кода классификатора '$classifierCode': ${e.message}"
                    )
                    return@async
                }
                if (materialLoodsmanId == null) {
                    notFound.incrementAndGet()
                    println("Материал по КД (DS) пропущен: код классификатора '$classifierCode' не найден в ПОЛИНОМ")
                    return@async
                }
                group.map { candidate ->
                    async {
                        val unitId = candidate.unitDesignation?.let { unitByDesignation[it] }
                        if (unitId != null) unitsAssigned.incrementAndGet()
                        linkBomMaterialToParent(
                            materialLoodsmanId,
                            candidate.parentLoodsmanId,
                            candidate.quantity,
                            unitId,
                            settings.mapping.linksSheet.linkType,
                            linksCreated,
                            failures,
                        )
                    }
                }.awaitAll()
            }
        }.awaitAll()
    }

    println(
        "Материалы по КД (DS): уникальных кодов ${candidatesByCode.size}, создано объектов ${materialsCreated.get()}, " +
            "связей ${linksCreated.get()}, не найдено в ПОЛИНОМ ${notFound.get()}, ошибок ${failures.get()}, " +
            "единиц измерения назначено ${unitsAssigned.get()}, обозначение не найдено ${unitsNotFound.get()}, " +
            "коллизий обозначения ${unitsCollision.get()}"
    )
}

private suspend fun MigrationContext.linkBomMaterialToParent(
    materialLoodsmanId: Int,
    parentLoodsmanId: Int,
    quantity: Double,
    unitId: String?,
    linkType: String,
    linksCreated: AtomicInteger,
    failures: AtomicInteger,
) {
    try {
        loodsmanClient.editObject.newLink(
            sessionId,
            NewLinkInputDto(
                parentVersionId = parentLoodsmanId,
                childVersionId = materialLoodsmanId,
                linkType = linkType,
                minQuantity = quantity,
                maxQuantity = quantity,
                unitId = unitId,
            )
        )
        linksCreated.incrementAndGet()
    } catch (e: Exception) {
        failures.incrementAndGet()
        System.err.println(
            "Не удалось связать материал по КД ($materialLoodsmanId) с объектом ($parentLoodsmanId): ${e.message}"
        )
        (e as? ResponseException)?.let {
            System.err.println("HTTP ${it.response.status.value}: ${it.response.bodyAsText()}")
        }
    }
}

// Только поиск по коду классификатора (шаг 1-2 из resolveOrCreateMaterial/findElementByClassifierCode,
// БЕЗ сравнения обозначения и БЕЗ фолбэка на создание элемента в ПОЛИНОМ) — возвращает null, если
// совпадения нет. Без private — переиспользуется в BlanksEngine.kt (тот же поиск-без-сравнения,
// но с другим целевым типом Loodsman, mapping.blanks.materialTarget вместо
// mapping.materials.materialTarget, отсюда параметр target).
suspend fun MigrationContext.resolveBomMaterialByClassifierCode(
    classifierCode: String,
    target: String,
    classifierCodeProperty: IdentifiableObjectDto,
    searchScope: IdentifiableObjectDto,
    elementCache: MutableMap<Pair<Int, Int>, Int>,
    elementCacheMutex: Mutex,
    materialsCreated: AtomicInteger,
): Int? {
    val found = polynomClient.search.searchByStringProperty(
        accessToken = polynomAccessToken,
        scope = searchScope,
        propertyDefinition = classifierCodeProperty,
        value = classifierCode,
    ).firstOrNull() ?: return null

    val key = found.objectId to found.typeId
    return elementCacheMutex.withLock {
        elementCache[key]?.let { return@withLock it }

        val location = polynomClient.classification.getLocation(
            polynomAccessToken,
            IdentifiableObjectDto(found.objectId, found.typeId)
        )
        val created = loodsmanClient.editObject.createBoObject(
            sessionId,
            CreateBoObjectInputDto(type = target, location = location, withLinks = false)
        )
        elementCache[key] = created
        materialsCreated.incrementAndGet()
        created
    }
}

// Без private — переиспользуется в AnalogGroupsEngine.kt, см. resolvePropertyDefinitionByAbsoluteCode выше.
suspend fun MigrationContext.resolveSearchScope(): IdentifiableObjectDto {
    val referenceName = settings.mapping.materials.codesReferenceName
    val reference = polynomClient.classification.getAllReferences(polynomAccessToken)
        .firstOrNull { it.name == referenceName }
        ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
        ?: throw IllegalStateException("Справочник «$referenceName» не найден в ПОЛИНОМ")

    val catalog = polynomClient.classification.getCatalogsByReference(polynomAccessToken, reference)
        .firstOrNull()
        ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
        ?: throw IllegalStateException("В справочнике «$referenceName» нет ни одного каталога")

    val group = polynomClient.classification.getGroupsByCatalog(polynomAccessToken, catalog)
        .firstOrNull()
        ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
        ?: throw IllegalStateException("В справочнике «$referenceName» нет ни одной группы")

    val concepts = polynomClient.concepts.getConceptsAppointedTo(polynomAccessToken, group)
    return concepts.firstOrNull { it.name == ELEMENT_CONCEPT_NAME }
        ?.let { IdentifiableObjectDto(it.objectId, it.typeId) }
        ?: throw IllegalStateException(
            "Понятие «$ELEMENT_CONCEPT_NAME» не найдено среди назначенных на первую группу справочника «$referenceName»"
        )
}
