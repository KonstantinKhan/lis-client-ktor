package com.khan366kos.lis.client.ktor.migration

fun resolveLinkComment(commentColumn: String, row: RowView): String? {
    if (commentColumn.isBlank()) return null
    return row.value(commentColumn)
}
