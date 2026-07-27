package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class NewLinkInputDto(
    val parentVersionId: Int,
    val parentType: String? = null,
    val parentProduct: String? = null,
    val parentVersion: String? = null,
    val childVersionId: Int,
    val childType: String? = null,
    val childProduct: String? = null,
    val childVersion: String? = null,
    val minQuantity: Int = 1,
    val maxQuantity: Int = 1,
    val unitId: String? = null,
    val linkType: String? = null,
)
