package com.khan366kos.lis.client.ktor.loodsman.api.dto.response

import com.khan366kos.lis.client.ktor.loodsman.api.dto.FileInfoDto
import kotlinx.serialization.Serializable

@Serializable
data class SaveFilesErrorOutputDto(
    val fileInfoList: List<FileInfoDto>? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null
)
