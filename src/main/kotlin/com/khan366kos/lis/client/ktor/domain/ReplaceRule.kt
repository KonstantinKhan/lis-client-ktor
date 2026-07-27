package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ReplaceRule {
    @Serializable
    @SerialName("N/A")
    data object None: ReplaceRule
    @Serializable
    @SerialName("any")
    data class ReplaceAny(
        val find: String,
        val target: String,
    ): ReplaceRule
}