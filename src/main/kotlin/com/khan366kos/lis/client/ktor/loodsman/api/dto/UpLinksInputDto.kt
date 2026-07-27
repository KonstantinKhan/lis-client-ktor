package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpLinksInputDto(
    val objectsData: List<UpLinkInputDto>? = null,
)
