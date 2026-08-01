package com.khan366kos.lis.client.ktor.migration

// Разбор "группа аналогов" ("1-2" -> groupNumber=1, variantNumber=2). null — колонка не
// сконфигурирована / ячейка пустая (строка вне групп аналогов), либо значение не в формате
// "число-число" (защитный разбор, не бросает).
fun resolveAnalogGroupNumbers(analogGroupColumn: String, row: RowView): Pair<Int, Int>? {
    if (analogGroupColumn.isBlank()) return null
    val raw = row.value(analogGroupColumn) ?: return null
    val parts = raw.split("-", limit = 2)
    val groupNumber = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val variantNumber = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    return groupNumber to variantNumber
}

// Тот же паттерн, что resolveLinkQuantity (запятая -> точка, toDoubleOrNull), для одного из двух
// производственных столбцов — раздельно на каждый столбец, а не сразу в isBasic, чтобы вызывающий
// код мог залогировать оба сырых значения при срабатывании правила "ровно одно из двух — 0".
fun resolveProductionQuantity(column: String, row: RowView): Double? {
    if (column.isBlank()) return null
    return row.value(column)?.replace(",", ".")?.toDoubleOrNull()
}
