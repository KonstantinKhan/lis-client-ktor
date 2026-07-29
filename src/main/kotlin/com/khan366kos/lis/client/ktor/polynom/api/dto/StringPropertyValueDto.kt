package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class StringPropertyValueDto(
    val objectId: Int,
    val typeId: Int,
    val value: String,
)
