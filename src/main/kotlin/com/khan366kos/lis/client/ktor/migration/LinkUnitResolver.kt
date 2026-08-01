package com.khan366kos.lis.client.ktor.migration

// null означает "не трогать unit связи вообще" — и для незаполненной колонки в конфиге, и для
// пустой ячейки, и для значения из unitExcludeValues (например "компл", "-"): во всех трёх
// случаях связь создаётся с дефолтным unit'ом API, без попытки резолва через Measure.
fun resolveLinkUnitDesignation(unitColumn: String, unitExcludeValues: List<String>, row: RowView): String? {
    if (unitColumn.isBlank()) return null
    val raw = row.value(unitColumn) ?: return null
    if (raw in unitExcludeValues) return null
    return raw
}
