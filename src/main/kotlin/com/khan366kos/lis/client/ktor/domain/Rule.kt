package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Rule(
    val type: String? = null,
    val column: String? = null,
    @SerialName("is")
    val isValue: String? = null,
)
