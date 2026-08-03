package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpLinkAttrValuesOutputDto(
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val linkId: Int,
    val attributeName: String? = null,
)
