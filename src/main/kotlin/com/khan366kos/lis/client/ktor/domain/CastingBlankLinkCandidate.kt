package com.khan366kos.lis.client.ktor.domain

// Одна строка листа "Литейные заготовки связи" (mapping.castingBlanks). Родитель хранится
// отдельно — кандидаты группируются по его classifierId (CastingBlanksEngine.kt), сам родитель
// резолвится в Identifier один раз на группу.
data class CastingBlankLinkCandidate(
    val childClassifierCode: String,
    // "Образец" / "Основной" / "Вспомогательный" (mapping.castingBlanks.materialTypeColumn) —
    // сырое значение колонки, интерпретируется в CastingBlanksEngine.kt.
    val materialType: String?,
    // Норма расхода — null означает "не проставлять" (колонка не настроена или ячейка не
    // прочиталась как число), тот же принцип, что BlankCandidate.rate.
    val quantity: Double? = null,
    val unitDesignation: String? = null,
    val workshop: String? = null,
)
