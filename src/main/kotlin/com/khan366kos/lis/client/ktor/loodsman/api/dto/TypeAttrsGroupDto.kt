package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class TypeAttrsGroupDto(
    val typeId: Int,
    val attrs: List<GetTypeAttrsOutputDto>? = null
)
