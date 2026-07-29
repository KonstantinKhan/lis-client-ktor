package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReferenceDto(
    val name: String? = null,
    val objectId: Int,
    val typeId: Int,
)
