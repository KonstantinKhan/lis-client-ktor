package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

// complexConditions/elementConditions/propValueConditions у нас всегда пустые — форма (пустые
// массивы, не отсутствие полей) подтверждена рабочим запросом из веб-клиента Полином.
@Serializable
data class PropertySearchConditionDto(
    val enabled: Boolean = true,
    val intersectionType: Int = 0,
    val simpleConditions: List<SimplePropertyConditionDto> = emptyList(),
    val complexConditions: List<String> = emptyList(),
    val elementConditions: List<String> = emptyList(),
    val propValueConditions: List<String> = emptyList(),
)
