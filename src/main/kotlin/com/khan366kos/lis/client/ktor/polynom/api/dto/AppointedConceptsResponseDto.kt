package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class AppointedConceptsResponseDto(
    val appointedConcepts: List<AppointedConceptDto> = emptyList(),
)
