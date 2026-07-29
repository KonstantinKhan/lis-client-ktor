package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SignInRequestDto(
    val storageId: String,
    val login: String,
    val password: String,
    val moduleName: String,
    val clientType: Int,
)
