package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class PropertySearchResponseDto(
    val items: List<PropertySearchItemDto> = emptyList(),
)
