package com.khan366kos.lis.client.ktor.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun rowOf(vararg pairs: Pair<String, String?>): RowView {
    val headerMap = pairs.mapIndexed { index, (column, _) -> column to index }.toMap()
    val cells = pairs.map { it.second }
    return RowView(headerMap, cells)
}

class AnalogGroupResolverTest {

    @Test
    fun `blank analogGroupColumn resolves to null`() {
        val row = rowOf("группа аналогов" to "1-2")
        assertNull(resolveAnalogGroupNumbers("", row))
    }

    @Test
    fun `well-formed value is parsed into group and variant`() {
        val row = rowOf("группа аналогов" to "1-2")
        assertEquals(1 to 2, resolveAnalogGroupNumbers("группа аналогов", row))
    }

    @Test
    fun `empty cell resolves to null`() {
        val row = rowOf("группа аналогов" to null)
        assertNull(resolveAnalogGroupNumbers("группа аналогов", row))
    }

    @Test
    fun `missing variant part resolves to null`() {
        val row = rowOf("группа аналогов" to "1")
        assertNull(resolveAnalogGroupNumbers("группа аналогов", row))
    }

    @Test
    fun `non-numeric value resolves to null`() {
        val row = rowOf("группа аналогов" to "abc-def")
        assertNull(resolveAnalogGroupNumbers("группа аналогов", row))
    }

    @Test
    fun `blank production quantity column resolves to null`() {
        val row = rowOf("производственное количество" to "5")
        assertNull(resolveProductionQuantity("", row))
    }

    @Test
    fun `production quantity comma decimal is parsed`() {
        val row = rowOf("производственное количество" to "2,5")
        assertEquals(2.5, resolveProductionQuantity("производственное количество", row))
    }

    @Test
    fun `production quantity empty cell resolves to null`() {
        val row = rowOf("производственное количество" to null)
        assertNull(resolveProductionQuantity("производственное количество", row))
    }

    @Test
    fun `production quantity non-numeric cell resolves to null`() {
        val row = rowOf("производственное количество" to "шт")
        assertNull(resolveProductionQuantity("производственное количество", row))
    }
}
