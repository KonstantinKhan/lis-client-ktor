package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpLinkAttrValuesInputDto(
    val linkId: Int,
    val attributeName: String? = null,
    val attributeValue: String? = null,
    val unitGuid: String? = null
)
