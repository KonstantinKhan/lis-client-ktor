package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class NewChangeVariant2InputDto(
    val changeGroupId: Int,
    val changeVariantName: String? = null,
    val linkFirstVariantId: Int,
    val isBasic: Boolean,
)
