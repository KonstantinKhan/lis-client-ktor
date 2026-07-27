package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginInputDto(
    val dbName: String? = null,
    val username: String? = null,
    val password: String? = null,
    val rememberMe: Boolean? = null
)
