package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateBoObjectInputDto(
    val type: String,
    val location: String,
    val manyToOneProduct: String? = null,
    val withLinks: Boolean = false,
)
