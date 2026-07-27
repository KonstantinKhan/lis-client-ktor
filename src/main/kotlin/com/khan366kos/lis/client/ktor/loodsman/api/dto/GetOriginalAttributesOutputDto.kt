package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetOriginalAttributesOutputDto(
    val id: Int,
    val idVersion: Int,
    val attrId: Int,
    val attrName: String? = null,
    val attrType: Int,
    val isSystem: Int,
    val value: String? = null,
    val originalValue: String? = null,
    val idUnit: String? = null,
    val unit: String? = null,
    val idMeasure: String? = null,
    val measure: String? = null,
    val baseValue: String? = null
)
