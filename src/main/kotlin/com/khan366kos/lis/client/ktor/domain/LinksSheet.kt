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
    val unitExcludeValues: List<String> = emptyList(),
    // Столбец с произвольным комментарием к строке "Связи" -> атрибут связи linkType (например
    // "Комментарий" -> "Примечание"). Пусто в любом из двух полей — атрибут не читается/не
    // проставляется, см. MigrationEngine.runLinksMigration.
    val commentColumn: String = "",
    val commentAttribute: String = ""

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
