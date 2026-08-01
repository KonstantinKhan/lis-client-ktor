package com.khan366kos.lis.client.ktor.domain

// Потомок строки листа "Связи" с непустой "группой аналогов", у которого childClassifierId НЕ
// резолвится ни в один объект, созданный на шаге "Объекты" (см. runLinksMigration) — кандидат на
// fallback-резолв через ПОЛИНОМ (AnalogGroupsEngine.resolveUnresolvedAnalogGroupCandidates):
// найти элемент по коду классификатора, создать под него объект в Loodsman, связать с parentом
// (quantity/unitDesignation — с той же строки "Связи", что и у обычных BOM-связей) и уже потом
// включить в группу замены "аналог".
data class UnresolvedAnalogGroupCandidate(
    val parentLoodsmanId: Int,
    val childClassifierCode: String,
    val groupNumber: Int,
    val variantNumber: Int,
    val isBasic: Boolean,
    val quantity: Double,
    val unitDesignation: String?,
)
