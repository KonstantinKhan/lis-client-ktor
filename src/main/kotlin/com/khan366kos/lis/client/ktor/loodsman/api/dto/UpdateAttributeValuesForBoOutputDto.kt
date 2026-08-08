package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateAttributeValuesForBoOutputDto(
    val index: Int? = null,
    val versionId: Int? = null,
    val name: String? = null,
    // 0 (или null) — успех; не bool-флаг isSuccess, как у UpAttrValuesByIdsOutputDto, эндпоинт
    // интегрированных с ПОЛИНОМ:MDM атрибутов отдаёт код и текст ошибки.
    val errorCode: Int? = null,
    val error: String? = null,
)
