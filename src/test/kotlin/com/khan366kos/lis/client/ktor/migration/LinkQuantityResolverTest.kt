package com.khan366kos.lis.client.ktor.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun rowOf(vararg pairs: Pair<String, String?>): RowView {
    val headerMap = pairs.mapIndexed { index, (column, _) -> column to index }.toMap()
    val cells = pairs.map { it.second }
    return RowView(headerMap, cells)
}

class LinkQuantityResolverTest {

    @Test
    fun `blank quantityColumn always resolves to 1_0`() {
        val row = rowOf("конструкторское количество" to "5")
        assertEquals(1.0, resolveLinkQuantity("", row))
    }

    @Test
    fun `integer value is parsed`() {
        val row = rowOf("конструкторское количество" to "5")
        assertEquals(5.0, resolveLinkQuantity("конструкторское количество", row))
    }

    @Test
    fun `comma decimal value is parsed`() {
        val row = rowOf("конструкторское количество" to "2,5")
        assertEquals(2.5, resolveLinkQuantity("конструкторское количество", row))
    }

    @Test
    fun `empty cell resolves to null`() {
        val row = rowOf("конструкторское количество" to null)
        assertNull(resolveLinkQuantity("конструкторское количество", row))
    }

    @Test
    fun `non-numeric cell resolves to null`() {
        val row = rowOf("конструкторское количество" to "шт")
        assertNull(resolveLinkQuantity("конструкторское количество", row))
    }
}
