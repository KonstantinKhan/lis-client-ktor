package com.khan366kos.lis.client.ktor.mapping

import com.khan366kos.lis.client.ktor.domain.LoodsmanObject
import com.khan366kos.lis.client.ktor.loodsman.api.dto.NewObjectInputDto

fun LoodsmanObject.NewObject.toApiDto(): NewObjectInputDto = NewObjectInputDto(
    typeName = type.name,
    stateName = state.name,
    keyAttribute = name,
    isProject = isProject
)