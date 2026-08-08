package com.khan366kos.lis.client.ktor.domain

// Кандидат на "Заготовку" + "Материал основной" (mapping.blanks) — собирается в
// MigrationEngine.processObjectRow только когда код классификатора (materials.classifierCodeColumn)
// на строке непустой, поэтому classifierCode здесь не nullable (в отличие от MaterialCandidate).
data class BlankCandidate(
    val detailLoodsmanId: Int,
    val detailDesignation: String,
    val classifierCode: String,
    // Норма расхода (mapping.blanks.rateColumn/rateUnitColumn, лист "Объекты") — для связи
    // заготовка -> материал основной. null означает "не проставлять" — либо колонка не настроена,
    // либо (для rate) значение в ячейке не читается как число (см. BlanksEngine.kt, лог там же).
    val rate: Double? = null,
    val rateUnitDesignation: String? = null,
    // Атрибуты ОБЪЕКТА "Заготовка" (mapping.blanks.attributes) — резолвлены с той же строки.
    val objectAttributes: List<ResolvedAttribute> = emptyList(),
    // Атрибуты СВЯЗИ "Изготавливается из ..." заготовка -> материал основной
    // (mapping.blanks.materialAttributes) — резолвлены с той же строки.
    val materialLinkAttributes: Map<String, String> = emptyMap(),
    // Атрибуты ОБЪЕКТА "Материал основной" (mapping.blanks.materialObjectAttributes) — резолвлены
    // с той же строки, применяются в MaterialsEngine.resolveBomMaterialByClassifierCode при
    // реальном создании объекта (не на каждое попадание в кэш).
    val materialObjectAttributes: Map<String, String> = emptyMap(),
)
