package com.khan366kos.lis.client.ktor.mapping

import com.khan366kos.lis.client.ktor.domain.LoodsmanType
import com.khan366kos.lis.client.ktor.loodsman.api.dto.GetTypesOutputDto

fun GetTypesOutputDto.toDomain() = LoodsmanType.TypeWithId(
    id = id,
    name = name ?: "",
)