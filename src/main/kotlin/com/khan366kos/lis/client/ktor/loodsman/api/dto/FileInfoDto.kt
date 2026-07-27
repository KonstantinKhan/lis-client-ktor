package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileInfoDto(
    val idVersion: Int,
    val ifFile: Int,
    val name: String? = null,
    val localName: String? = null,
    val cached: Boolean,
    val new: Boolean,
    val error: String? = null,
    val errorCode: Int
)
