package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class FindObjectsSimpleInputDto(
    val searchText: String? = null,
    val searchVariant: Int,
    val needSearchByKeyAttribute: Boolean,
    val attributes: List<String> = emptyList(),
    val types: List<String> = emptyList(),
)
