package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class Source(
    val type: String,
    val path: String
) {
    companion object {
        val None = Source("None", "")
    }
}
