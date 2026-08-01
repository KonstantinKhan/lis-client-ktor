package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetLinkedFastOutputDto(
    val idLink: Int,
    val idVersion: Int,
    val type: String? = null,
    val product: String? = null,
    val version: String? = null,
    val state: String? = null,
    val document: Int,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val accessLevel: Int,
    val label: Int,
    val labelName: String? = null,
    val locked: Int,
    val idUnit: String? = null,
    val idMeasure: String? = null,
    val unit: String? = null,
    val measure: String? = null,
    val minCalc: Double,
    val maxCalc: Double,
    val guid: String? = null,
    val location: String? = null,
    val linkOrder: Int? = null
)
