package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

// Плоский массив, БЕЗ обёртки {hasValue, value} — подтверждено рабочим запросом из веб-клиента
// Полином. tableProperties тоже всегда пустой, но форма (пустой массив) там присутствует.
@Serializable
data class PropertySearchValuesDto(
    val stringProperties: List<StringPropertyValueDto> = emptyList(),
    val tableProperties: List<String> = emptyList(),
)
