package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ElementGroupDto(
    val name: String? = null,
    val objectId: Int,
    val typeId: Int,
)
