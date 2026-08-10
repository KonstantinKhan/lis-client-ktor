package com.khan366kos.lis.client.ktor.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun rowOf(vararg pairs: Pair<String, String?>): RowView {
    val headerMap = pairs.mapIndexed { index, (column, _) -> column to index }.toMap()
    val cells = pairs.map { it.second }
    return RowView(headerMap, cells)
}

class LinkCommentResolverTest {

    @Test
    fun `blank commentColumn always resolves to null`() {
        val row = rowOf("Комментарий" to "видно только на этой связи")
        assertNull(resolveLinkComment("", row))
    }

    @Test
    fun `empty cell resolves to null`() {
        val row = rowOf("Комментарий" to null)
        assertNull(resolveLinkComment("Комментарий", row))
    }

    @Test
    fun `non-empty value is returned as-is`() {
        val row = rowOf("Комментарий" to "требует проверки")
        assertEquals("требует проверки", resolveLinkComment("Комментарий", row))
    }
}
