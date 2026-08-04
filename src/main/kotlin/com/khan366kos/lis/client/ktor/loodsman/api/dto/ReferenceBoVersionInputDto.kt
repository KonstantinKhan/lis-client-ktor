package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReferenceBoVersionInputDto(
    val versionId: Int,
    val boTypeBindingRuleId: Int = 0,
    val objectLocation: String,
)
