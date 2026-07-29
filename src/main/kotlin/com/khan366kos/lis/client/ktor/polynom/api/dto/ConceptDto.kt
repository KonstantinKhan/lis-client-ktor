package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ConceptDto(
    val name: String? = null,
    val code: String? = null,
    val objectId: Int,
    val typeId: Int,
)
