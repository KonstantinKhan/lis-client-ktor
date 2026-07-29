package com.khan366kos.lis.client.ktor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MaterialCandidateTest {

    @Test
    fun `dedup key uses classifier code when present`() {
        val candidate = MaterialCandidate(
            detailLoodsmanId = 1,
            drawingDesignation = "Сталь 45 ГОСТ 1050",
            classifierCode = "12345",
        )
        assertEquals("12345", candidate.dedupKey)
    }

    @Test
    fun `dedup key falls back to drawing designation when classifier code blank`() {
        val blank = MaterialCandidate(detailLoodsmanId = 1, drawingDesignation = "Сталь 45 ГОСТ 1050", classifierCode = "")
        assertEquals("Сталь 45 ГОСТ 1050", blank.dedupKey)

        val missing = MaterialCandidate(detailLoodsmanId = 1, drawingDesignation = "Сталь 45 ГОСТ 1050", classifierCode = null)
        assertEquals("Сталь 45 ГОСТ 1050", missing.dedupKey)
    }

    @Test
    fun `dedup key is null when both classifier code and drawing designation are blank`() {
        val candidate = MaterialCandidate(detailLoodsmanId = 1, drawingDesignation = null, classifierCode = "  ")
        assertNull(candidate.dedupKey)
    }

    @Test
    fun `candidates sharing classifier code group under one key regardless of drawing designation`() {
        val a = MaterialCandidate(detailLoodsmanId = 1, drawingDesignation = "Сталь 45", classifierCode = "12345")
        val b = MaterialCandidate(detailLoodsmanId = 2, drawingDesignation = "Совсем другое обозначение", classifierCode = "12345")
        val c = MaterialCandidate(detailLoodsmanId = 3, drawingDesignation = "Сталь 45", classifierCode = null)

        val grouped = listOf(a, b, c).groupBy { it.dedupKey }

        assertEquals(2, grouped.size)
        assertEquals(listOf(a, b), grouped.getValue("12345"))
        assertEquals(listOf(c), grouped.getValue("Сталь 45"))
    }
}
