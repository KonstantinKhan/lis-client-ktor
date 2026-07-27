package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ObjectsSheet(
    override val name: String,
    @SerialName("headersRowIndex")
    override val rawHeadersRow: Int
) : DataSheet {

    companion object {
        val None = ObjectsSheet(
            name = "",
            rawHeadersRow = -1
        )
    }

    val headersRow: Int = rawHeadersRow - 1
}
