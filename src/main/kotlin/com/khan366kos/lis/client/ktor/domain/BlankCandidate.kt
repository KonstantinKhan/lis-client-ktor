package com.khan366kos.lis.client.ktor.domain

// Кандидат на "Заготовку" + "Материал основной" (mapping.blanks) — собирается в
// MigrationEngine.processObjectRow только когда код классификатора (materials.classifierCodeColumn)
// на строке непустой, поэтому classifierCode здесь не nullable (в отличие от MaterialCandidate).
data class BlankCandidate(
    val detailLoodsmanId: Int,
    val detailDesignation: String,
    val classifierCode: String,
)
