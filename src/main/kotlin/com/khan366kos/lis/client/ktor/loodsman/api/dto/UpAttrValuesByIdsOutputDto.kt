package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpAttrValuesByIdsOutputDto(
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val versionId: Int,
    val attributeName: String? = null,
) {
}