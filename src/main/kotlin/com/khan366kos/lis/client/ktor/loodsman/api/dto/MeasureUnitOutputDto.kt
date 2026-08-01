package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeasureUnitOutputDto(
    val id: String? = null,
    val name: String? = null,
    val designation: String? = null,
    val okei: String? = null,
    val measureName: String? = null,
    val measureId: String? = null,
    val isBasic: Boolean,
    val fromBasicFactor: Double
)
