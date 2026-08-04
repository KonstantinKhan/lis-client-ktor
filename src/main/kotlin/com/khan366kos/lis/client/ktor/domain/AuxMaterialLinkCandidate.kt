package com.khan366kos.lis.client.ktor.domain

// Одна строка листа "Вспомогательные материалы" (mapping.auxMaterials). Родитель хранится
// отдельно — кандидаты группируются по его classifierId (AuxMaterialsEngine.kt), сам родитель
// резолвится в Identifier один раз на группу. Аналог CastingBlankLinkCandidate, без materialType —
// эта фича не ветвится по типу материала.
data class AuxMaterialLinkCandidate(
    val childClassifierCode: String,
    val quantity: Double? = null,
    val unitDesignation: String? = null,
    val workshop: String? = null,
)
