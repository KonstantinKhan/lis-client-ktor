package com.khan366kos.lis.client.ktor.domain

// Вариант группы замены "аналог" (лист "Связи", столбец "группа аналогов", формат "1-2" —
// groupNumber-variantNumber) — в отличие от BomMaterialCandidate, обе стороны уже полностью
// резолвлены в созданные Loodsman-объекты (кандидат строится из linkPairs ПОСЛЕ резолва по
// classifierId, см. MigrationEngine.runLinksMigration), отдельного резолва не требуется.
data class AnalogGroupCandidate(
    val parentLoodsmanId: Int,
    val childLoodsmanId: Int,
    val groupNumber: Int,
    val variantNumber: Int,
    val isBasic: Boolean,
)
