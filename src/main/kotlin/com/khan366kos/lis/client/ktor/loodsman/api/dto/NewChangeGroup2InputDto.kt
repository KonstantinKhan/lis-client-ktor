package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class NewChangeGroup2InputDto(
    val versionId: Int,
    val changeGroupName: String? = null,
    val groupType: Int,
)
