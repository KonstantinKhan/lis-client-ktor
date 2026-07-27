package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpAttrValuesByIdsInputDto(
    val versionId: Int,
    val attributeName: String? = null,
    val attributeValue: String? = null,
    val unitGuid: String? = null
)
