package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class MappingElement(
    val target: String,
    val source: String,
    val state: String,
    val isProject: Boolean = false,
    val linkToRoot: Boolean = false,
    val conditions: Conditions,
)
