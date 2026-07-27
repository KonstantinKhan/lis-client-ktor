package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Conditions
import com.khan366kos.lis.client.ktor.domain.Rule

object ConditionsEvaluator {

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
