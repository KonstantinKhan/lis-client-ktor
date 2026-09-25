package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Лист/вкладка со сканами документов (объект/сетевой путь/имя файла) — см. DocumentsEngine.kt.
// source == null (дефолт) — читаем тот же файл, что и mapping.source (тот же excelInputStream(),
// просто другой лист); задан явно — отдельный xlsx-файл со своим путём.
// name.isBlank() (дефолт None) выключает фичу целиком, тот же приём, что BlanksSettings/MaterialsSettings.
@Serializable
data class DocumentsSheet(
    override val name: String = "",
    @SerialName("headersRowIndex")
    override val rawHeadersRow: Int = -1,
    val objectNameColumn: String = "",
    val networkPathColumn: String = "",
    val fileNameColumn: String = "",
    val source: Source? = null,
) : DataSheet {

    companion object {
        val None = DocumentsSheet()
    }

    val headersRow: Int = rawHeadersRow - 1
}
