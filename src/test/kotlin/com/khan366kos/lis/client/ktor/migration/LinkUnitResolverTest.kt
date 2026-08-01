package com.khan366kos.lis.client.ktor.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun rowOf(vararg pairs: Pair<String, String?>): RowView {
    val headerMap = pairs.mapIndexed { index, (column, _) -> column to index }.toMap()
    val cells = pairs.map { it.second }
    return RowView(headerMap, cells)
}

class LinkUnitResolverTest {

    @Test
    fun `blank unitColumn always resolves to null`() {
        val row = rowOf("ед. изм." to "шт")
        assertNull(resolveLinkUnitDesignation("", emptyList(), row))
    }

    @Test
    fun `empty cell resolves to null`() {
        val row = rowOf("ед. изм." to null)
        assertNull(resolveLinkUnitDesignation("ед. изм.", emptyList(), row))
    }

    @Test
    fun `excluded value resolves to null`() {
        val row = rowOf("ед. изм." to "компл")
        assertNull(resolveLinkUnitDesignation("ед. изм.", listOf("компл", "-"), row))
    }

    @Test
    fun `non-excluded value is returned as-is`() {
        val row = rowOf("ед. изм." to "м2")
        assertEquals("м2", resolveLinkUnitDesignation("ед. изм.", listOf("компл", "-"), row))
    }

    @Test
    fun `exclude list is exact-match, not substring`() {
        val row = rowOf("ед. изм." to "компл.")
        assertEquals("компл.", resolveLinkUnitDesignation("ед. изм.", listOf("компл", "-"), row))
    }
}
