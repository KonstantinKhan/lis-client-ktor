package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class Attribute(
    val attrColumn: String,
    val loodsmanAttr: String,
    val replace: ReplaceRule? = null,
    // Фиксированное обозначение единицы измерения (например "мм") — константа из настроек, не
    // читается из Excel, поэтому резолвится в unitGuid один раз на уникальное значение, а не на
    // кандидата (см. AttributeResolver.kt/BlanksEngine.kt). null — атрибут без единицы.
    val unit: String? = null,
)
