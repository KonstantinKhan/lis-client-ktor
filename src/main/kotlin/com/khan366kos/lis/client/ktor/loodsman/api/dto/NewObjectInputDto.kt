package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class NewObjectInputDto(
    val typeName: String? = null,
    val stateName: String? = null,
    val keyAttribute: String? = null,
    val isProject: Boolean
)
