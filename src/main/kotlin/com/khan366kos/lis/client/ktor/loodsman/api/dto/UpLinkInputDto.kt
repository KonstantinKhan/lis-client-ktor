package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpLinkInputDto(
    val parentType: String? = null,
    val parentProduct: String? = null,
    val parentVersion: String? = null,
    val childType: String? = null,
    val childProduct: String? = null,
    val childVersion: String? = null,
    val linkId: Int,
    val minQuantity: Double,
    val maxQuantity: Double,
    val unitId: String? = null,
    val delLink: Boolean,
    val linkType: String
)
