package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

// Client.kt Json не задаёт ignoreUnknownKeys=true — тело ответа обязано перечислять ВСЕ поля,
// которые реально возвращает ObjectSearch/find-by-simple-search (см. swagger.json), иначе
// десериализация падает на первом же незнакомом ключе.
@Serializable
data class FindObjectsSimpleOutputDto(
    val id: Int,
    val typeId: Int,
    val stateId: Int,
    val name: String? = null,
    val version: String? = null,
    val revision: Int,
    val accessLevel: Int,
    val label: Int,
    val labelName: String? = null,
    val hasLink: Int,
    val lockId: Int,
)
