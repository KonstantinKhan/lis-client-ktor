package com.khan366kos.lis.client.ktor.migration

fun resolveLinkQuantity(quantityColumn: String, row: RowView): Double? {
    if (quantityColumn.isBlank()) return 1.0
    return row.value(quantityColumn)?.replace(",", ".")?.toDoubleOrNull()
}
