package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetByAbsoluteCodeRequestDto(
    val absoluteCode: String,
)
