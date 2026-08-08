package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateBoAttributeValueDto(
    val versionId: Int,
    val name: String,
    val value: String? = null,
    val unitId: String? = null,
    // Фиксирован в 0 в проекте (тот же приём, что ReferenceBoVersionInputDto.boTypeBindingRuleId).
    val bindingRuleId: Int = 0,
    val location: String,
)
