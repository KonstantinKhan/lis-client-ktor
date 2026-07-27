package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class Attribute(
    val attrColumn: String,
    val loodsmanAttr: String,
    val replace: ReplaceRule? = null,
)
