package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetTypesOutputDto(
    val id: Int,
    val name: String? = null,
    val state: String? = null,
    val attribute: String? = null,
    val alias: String? = null,
    val description: String? = null,
    val abstract: Int,
    val noVersions: Int,
    val icon: String? = null,
    val isDocument: Int,
    val canProject: Int,
    val parentId: Int
)
