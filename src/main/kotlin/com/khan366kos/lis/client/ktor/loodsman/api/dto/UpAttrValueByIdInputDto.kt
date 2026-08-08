package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpAttrValueByIdInputDto(
    val idVersion: Int,
    val attrName: String,
    val attrValue: String? = null,
    val idUnit: String? = null,
    val delete: Boolean = false,
)
