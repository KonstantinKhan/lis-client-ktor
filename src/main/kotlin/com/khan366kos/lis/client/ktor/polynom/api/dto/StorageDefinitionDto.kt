package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class StorageDefinitionDto(
    val storageId: String,
    val displayName: String? = null,
)
