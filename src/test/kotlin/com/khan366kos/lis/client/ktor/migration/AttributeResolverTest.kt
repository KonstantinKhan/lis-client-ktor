package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Attribute
import com.khan366kos.lis.client.ktor.domain.ReplaceRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun rowOf(vararg pairs: Pair<String, String?>): RowView {
    val headerMap = pairs.mapIndexed { index, (column, _) -> column to index }.toMap()
    val cells = pairs.map { it.second }
    return RowView(headerMap, cells)
}

class AttributeResolverTest {

    private val attributes = listOf(
        Attribute(attrColumn = "наименование", loodsmanAttr = "Наименование"),
        Attribute(
            attrColumn = "Без чертежа",
            loodsmanAttr = "Дополнение наименования",
            replace = ReplaceRule.ReplaceAny(find = "any", target = "БЧ"),
        ),
    )

    @Test
    fun `plain attribute is copied as is`() {
        val row = rowOf("наименование" to "Деталь X", "Без чертежа" to null)
        val result = resolveAttributes(attributes, row)
        assertEquals("Деталь X", result["Наименование"])
    }

    @Test
    fun `replace-any substitutes target when source cell is non-empty`() {
        val row = rowOf("наименование" to "Деталь X", "Без чертежа" to "да")
        val result = resolveAttributes(attributes, row)
        assertEquals("БЧ", result["Дополнение наименования"])
    }

    @Test
    fun `replace-any is dropped when source cell is empty`() {
        val row = rowOf("наименование" to "Деталь X", "Без чертежа" to null)
        val result = resolveAttributes(attributes, row)
        assertNull(result["Дополнение наименования"])
    }

    @Test
    fun `blank or NA values are excluded from result`() {
        val row = rowOf("наименование" to "N/A", "Без чертежа" to null)
        val result = resolveAttributes(attributes, row)
        assertNull(result["Наименование"])
    }
}
