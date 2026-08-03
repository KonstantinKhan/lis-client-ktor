package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class MappingElement(
    val target: String,
    val source: String,
    // Игнорируется, если resolveViaPolynom=true — объект создаётся через create-bo-object
    // (состояние резолвит сам Loodsman через привязку типа к свойству "применяемость" ПОЛИНОМ),
    // а не через new-object, у которого stateName задаётся отсюда.
    val state: String? = null,
    val isProject: Boolean = false,
    val isFolder: Boolean = false,
    val linkToRoot: Boolean = false,
    val conditions: Conditions,
    val childLinkType: String? = null,
    val childOfSameTypeLinkType: String? = null,
    val resolveViaPolynom: Boolean = false,
)
