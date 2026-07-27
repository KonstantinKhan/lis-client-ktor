package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetTypeAttrsInputDto(
    val typeIds: List<Int>
)
