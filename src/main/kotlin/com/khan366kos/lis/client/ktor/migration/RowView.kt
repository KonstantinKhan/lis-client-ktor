package com.khan366kos.lis.client.ktor.migration

class RowView(
    private val headerMap: Map<String, Int>,
    private val cells: List<String?>
) {
    fun value(column: String): String? {
        val index = headerMap[column] ?: return null
        return cells.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

object SheetHeaders {
    fun build(headerRow: List<String?>): Map<String, Int> =
        headerRow.withIndex()
            .mapNotNull { (index, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { it to index } }
            .toMap()
}
