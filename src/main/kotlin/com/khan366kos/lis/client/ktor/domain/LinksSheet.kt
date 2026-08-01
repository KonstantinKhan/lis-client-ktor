package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LinksSheet(
    override val name: String,
    @SerialName("headersRowIndex")
    override val rawHeadersRow: Int,
    @SerialName("parentColumnIndex")
    val rawParentColumnIndex: Int,
    @SerialName("childColumnIndex")
    val childColumnIndex: Int,
    val linkType: String = "",
    val quantityColumn: String = "",
    val unitColumn: String = "",
    val unitExcludeValues: List<String> = emptyList()

) : DataSheet {

    companion object {
        val None = LinksSheet(
            name = "",
            rawHeadersRow = -1,
            rawParentColumnIndex = -1,
            childColumnIndex = -1
        )
    }

    val headersRow: Int = rawHeadersRow - 1
    val parentColumn = rawParentColumnIndex - 1
    val childColumn = childColumnIndex - 1
}
