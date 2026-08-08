package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpAttrValueForBoByIdInputDto(
    val idVersion: Int,
    val attrName: String,
    val attrValue: String? = null,
    val idUnit: String? = null,
    val delete: Boolean = false,
    // Фиксирован в 0 в проекте (тот же приём, что ReferenceBoVersionInputDto.boTypeBindingRuleId).
    val bindingRuleId: Int = 0,
    val location: String,
)
