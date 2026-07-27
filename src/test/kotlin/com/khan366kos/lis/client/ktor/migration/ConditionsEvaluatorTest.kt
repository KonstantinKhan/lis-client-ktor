package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Conditions
import com.khan366kos.lis.client.ktor.domain.Rule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rowOf(vararg pairs: Pair<String, String?>): RowView {
    val headerMap = pairs.mapIndexed { index, (column, _) -> column to index }.toMap()
    val cells = pairs.map { it.second }
    return RowView(headerMap, cells)
}

class ConditionsEvaluatorTest {

    // "Папка": single = check("Раздел спецификации" == "Изделия")
    private val folderConditions = Conditions(
        single = Rule(type = "check", column = "Раздел спецификации", isValue = "Изделия"),
        or = emptyList(),
        and = emptyList(),
    )

    // "Сборочная единица": single = {} (не применимо), or = [Изделия, Сборочные единицы]
    private val assemblyConditions = Conditions(
        single = Rule(),
        or = listOf(
            Rule(type = "check", column = "Раздел спецификации", isValue = "Изделия"),
            Rule(type = "check", column = "Раздел спецификации", isValue = "Сборочные единицы"),
        ),
        and = emptyList(),
    )

    // "Деталь" (дет): and = [Тип АСУП==дет, Раздел спецификации==Детали, обозначение !endsWith Т, обозначение !endsWith T]
    private val detailManufacturedConditions = Conditions(
        single = Rule(),
        or = emptyList(),
        and = listOf(
            Rule(type = "check", column = "Тип АСУП", isValue = "дет"),
            Rule(type = "check", column = "Раздел спецификации", isValue = "Детали"),
            Rule(type = "parse", column = "обозначение", isValue = "Т"),
            Rule(type = "parse", column = "обозначение", isValue = "T"),
        ),
    )

    // "Деталь" (покуп): and = [Тип АСУП==покуп, Раздел спецификации==Детали]
    private val detailPurchasedConditions = Conditions(
        single = Rule(),
        or = emptyList(),
        and = listOf(
            Rule(type = "check", column = "Тип АСУП", isValue = "покуп"),
            Rule(type = "check", column = "Раздел спецификации", isValue = "Детали"),
        ),
    )

    @Test
    fun `single condition matches when value equal`() {
        val row = rowOf("Раздел спецификации" to "Изделия")
        assertTrue(ConditionsEvaluator.matches(folderConditions, row))
    }

    @Test
    fun `single condition does not match other value`() {
        val row = rowOf("Раздел спецификации" to "Детали")
        assertFalse(ConditionsEvaluator.matches(folderConditions, row))
    }

    @Test
    fun `empty single group does not block or group`() {
        val row = rowOf("Раздел спецификации" to "Сборочные единицы")
        assertTrue(ConditionsEvaluator.matches(assemblyConditions, row))
    }

    @Test
    fun `or condition matches any branch`() {
        assertTrue(ConditionsEvaluator.matches(assemblyConditions, rowOf("Раздел спецификации" to "Изделия")))
        assertTrue(ConditionsEvaluator.matches(assemblyConditions, rowOf("Раздел спецификации" to "Сборочные единицы")))
        assertFalse(ConditionsEvaluator.matches(assemblyConditions, rowOf("Раздел спецификации" to "Детали")))
    }

    @Test
    fun `and condition requires every rule including parse suffix check`() {
        val matchingRow = rowOf(
            "Тип АСУП" to "дет",
            "Раздел спецификации" to "Детали",
            "обозначение" to "АБВГ.12345.001",
        )
        assertTrue(ConditionsEvaluator.matches(detailManufacturedConditions, matchingRow))

        val wrongSuffixRow = rowOf(
            "Тип АСУП" to "дет",
            "Раздел спецификации" to "Детали",
            "обозначение" to "АБВГ.12345.001Т",
        )
        assertFalse(ConditionsEvaluator.matches(detailManufacturedConditions, wrongSuffixRow))
    }

    @Test
    fun `purchased detail matches with only two and rules`() {
        val row = rowOf(
            "Тип АСУП" to "покуп",
            "Раздел спецификации" to "Детали",
        )
        assertTrue(ConditionsEvaluator.matches(detailPurchasedConditions, row))
    }

    @Test
    fun `and condition fails when a rule column is missing from row`() {
        val row = rowOf("Тип АСУП" to "дет")
        assertFalse(ConditionsEvaluator.matches(detailManufacturedConditions, row))
    }
}
