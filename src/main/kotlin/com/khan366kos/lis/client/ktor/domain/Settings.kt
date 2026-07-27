package com.khan366kos.lis.client.ktor.domain

import com.khan366kos.lis.client.ktor.mapping.Mapping
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val connection: Connection,
    val mapping: Mapping
) {
    companion object {
        val None = Settings(
            connection = Connection.None,
            mapping = Mapping.None
        )
    }
}
