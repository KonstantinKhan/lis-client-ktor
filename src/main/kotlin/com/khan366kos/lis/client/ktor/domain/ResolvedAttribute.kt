package com.khan366kos.lis.client.ktor.domain

// Атрибут объекта/связи вместе с его резолвленным значением и (опционально) обозначением единицы
// измерения (Attribute.unit — константа из настроек, не читается из Excel). Возвращается
// migration/AttributeResolver.kt (resolveAttributesWithUnits), несётся кандидатами (см.
// BlankCandidate.objectAttributes) до момента фактической записи атрибута в Loodsman.
data class ResolvedAttribute(val loodsmanAttr: String, val value: String, val unitDesignation: String?)
