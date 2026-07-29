package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

// Форма подтверждена реальным рабочим запросом, снятым из веб-клиента Полином (devtools):
// property-definition идёт в searchConditionTargetQualifier, "definition" всегда null (это
// поле для другого стиля условия), "value" — фиксированный {0,0}-плейсхолдер (само значение
// для сравнения передаётся отдельно, в PropertySearchValuesDto). operation=1 — Equal
// (StringCompareOperation, числовые значения не задокументированы, 1 подтверждён рабочим).
@Serializable
data class SimplePropertyConditionDto(
    val enabled: Boolean = true,
    val searchConditionTargetQualifier: IdentifiableObjectDto,
    val definition: IdentifiableObjectDto? = null,
    val operation: Int,
    val options: Int = 0,
    val value: IdentifiableObjectDto = IdentifiableObjectDto(objectId = 0, typeId = 0),
)
