package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MaterialCandidate
import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.loodsman.api.dto.CreateBoObjectInputDto
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
    if (materialCandidates.isEmpty()) {
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
    val hierarchyMutex = Mutex()
    val elementCreationMutex = Mutex()
    // Разные dedupKey (разные коды классификатора) могут разрешиться в один и тот же элемент
    // Полином (одинаковое обозначение по чертежу, оба кода не находят совпадения в поиске) —
    // без этого кэша обе группы параллельно вызовут createBoObject с одинаковым location и
    // Loodsman упадёт на уникальном индексе (23505 idx_uq_stmain_stkeyattr_inidtype).
    val elementCache = mutableMapOf<Pair<Int, Int>, Int>()
    val elementCacheMutex = Mutex()

    // Группировка — синхронно и до запуска корутин, поэтому дальше по каждому уникальному
    // ключу работает ровно одна корутина и гонок при resolve-or-create не возникает.
    val candidatesByKey = materialCandidates.groupBy { it.dedupKey }.filterKeys { it != null }

    val materialsCreated = AtomicInteger(0)
    val linksCreated = AtomicInteger(0)
    val skipped = AtomicInteger(0)
    val detailsNotLinked = AtomicInteger(0)
    val failures = AtomicInteger(0)

    coroutineScope {
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
                    return@async
                }
                if (materialLoodsmanId == null) {
                    // Материал создавать не из чего: код классификатора либо пуст, либо не нашёл
                    // совпадения в ПОЛИНОМ (или обозначение найденного элемента не совпало с
                    // чертёжным), И при этом обозначения по чертежу тоже нет (иначе ушли бы в
                    // ветку создания нового элемента) — это не ошибка (см. решение пользователя),
                    // но деталь(и) остаются без "Материал по КД". Печатаем id деталей и оба
                    // исходных значения, чтобы можно было найти строку(и) в Excel и решить, что
                    // с ней делать (данные неполные либо код в ПОЛИНОМ не заведён).
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
                        "Материал пропущен (нет данных для создания): обозначение по чертежу=" +
                            "'$designation', детали: $details"
                    )
                    return@async
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
                        "Материал (Loodsman id $materialLoodsmanId) не привязан к деталям без собственного " +
                            "обозначения по чертежу (код классификатора совпал с другой деталью группы): $details"
                    )
                }

                linkable.map { candidate ->
                    async { linkMaterialToDetail(materialLoodsmanId, candidate, materials.detailLinkType, linksCreated, failures) }
                }.awaitAll()
            }
        }.awaitAll()
    }

    println(
        "Материалы: уникальных ${candidatesByKey.size}, создано объектов ${materialsCreated.get()}, " +
            "связей с деталями ${linksCreated.get()}, пропущено групп ${skipped.get()}, " +
            "деталей без своего обозначения не привязано ${detailsNotLinked.get()}, ошибок ${failures.get()}"
    )
}

private suspend fun MigrationContext.linkMaterialToDetail(
    materialLoodsmanId: Int,
    candidate: MaterialCandidate,
    linkType: String,
    linksCreated: AtomicInteger,
    failures: AtomicInteger,
) {
    try {
        loodsmanClient.editObject.newLink(
            sessionId,
            NewLinkInputDto(
                parentVersionId = candidate.detailLoodsmanId,
                childVersionId = materialLoodsmanId,
                linkType = linkType,
            )
        )
        linksCreated.incrementAndGet()
    } catch (e: Exception) {
        failures.incrementAndGet()
        System.err.println("Не удалось связать материал ($materialLoodsmanId) с деталью (${candidate.detailLoodsmanId}): ${e.message}")
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
private suspend fun MigrationContext.resolvePropertyDefinitionByAbsoluteCode(absoluteCode: String): IdentifiableObjectDto {
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

private suspend fun MigrationContext.resolveSearchScope(): IdentifiableObjectDto {
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
