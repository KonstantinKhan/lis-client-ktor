package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Conditions
import com.khan366kos.lis.client.ktor.domain.Rule

object ConditionsEvaluator {

    // Conditions() "по умолчанию" (single=null, or=[], and=[]) для matches() означает "всегда
    // true" — уместно для mapping.types[].conditions (элемент списка уже сам по себе opt-in), но
    // не для гейтов вроде bomMaterials.specificationConditions, где отсутствие настройки должно
    // означать "фича выключена", а не "матчит любую строку". isConfigured отличает "условие не
    // задано вовсе" от "условие задано и просто не совпало".
    fun isConfigured(conditions: Conditions): Boolean =
        conditions.single?.column != null || conditions.or.isNotEmpty() || conditions.and.isNotEmpty()

    fun matches(conditions: Conditions, row: RowView): Boolean {
        val singleResult = conditions.single
            ?.takeIf { it.column != null }
            ?.let { evaluate(it, row) }
            ?: true

        val orResult = conditions.or
            .takeIf { it.isNotEmpty() }
            ?.any { evaluate(it, row) }
            ?: true

        val andResult = conditions.and
            .takeIf { it.isNotEmpty() }
            ?.all { evaluate(it, row) }
            ?: true

        return singleResult && orResult && andResult
    }

    private fun evaluate(rule: Rule, row: RowView): Boolean {
        val column = rule.column ?: return false
        return RuleEvaluators.evaluate(rule, row.value(column))
    }
}
