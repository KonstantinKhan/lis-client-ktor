package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class PropertySearchRequestDto(
    val ownerScope: IdentifiableObjectDto? = null,
    val condition: PropertySearchConditionDto,
    val values: PropertySearchValuesDto,
    val pageNumber: Int = 1,
    val pageSize: Int = 50,
)
