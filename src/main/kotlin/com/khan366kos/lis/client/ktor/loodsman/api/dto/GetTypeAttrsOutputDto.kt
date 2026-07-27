package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetTypeAttrsOutputDto(
    val attrTypeId: Int,
    val id: Int,
    val name: String? = null,
    val alias: String? = null,
    val inherited: Int,
    val rule: String? = null,
    val obligatory: Int,
    val system: Int,
    val attrType: Int,
    val copyOnCreateVersion: Int,
    val copyOnCreateCopy: Int,
)
