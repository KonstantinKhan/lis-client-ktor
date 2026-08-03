package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewLinkInputDto
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

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

                    loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = candidate.detailLoodsmanId,
                            childVersionId = blankId,
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

                    loodsmanClient.editObject.newLink(
                        sessionId,
                        NewLinkInputDto(
                            parentVersionId = blankId,
                            childVersionId = materialId,
                            linkType = blanks.materialLinkType,
                        )
                    )
                    materialLinksCreated.incrementAndGet()
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
            "материал не найден в ПОЛИНОМ ${materialsNotFound.get()}, ошибок ${failures.get()}"
    )
}
