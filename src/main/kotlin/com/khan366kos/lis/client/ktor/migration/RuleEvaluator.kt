package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Rule

fun interface RuleEvaluator {
    fun evaluate(rule: Rule, value: String?): Boolean
}

object RuleEvaluators {
    private val registry = mutableMapOf<String, RuleEvaluator>(
        "check" to RuleEvaluator { rule, value -> value != null && value == rule.isValue },
        "notEndsWith" to RuleEvaluator { rule, value ->
            value != null && rule.isValue != null && !value.endsWith(rule.isValue)
        },
        "endsWith" to RuleEvaluator { rule, value ->
            value != null && rule.isValue != null && value.endsWith(rule.isValue)
        },
        // RowView.value() уже возвращает null и для отсутствующей колонки, и для пустой/
        // пробельной ячейки (trim + takeIf isNotEmpty) — достаточно проверить value == null.
        "empty" to RuleEvaluator { _, value -> value == null },
    )

    fun register(type: String, evaluator: RuleEvaluator) {
        registry[type] = evaluator
    }

    fun evaluate(rule: Rule, value: String?): Boolean {
        val type = rule.type ?: return false
        return registry[type]?.evaluate(rule, value) ?: false
    }
}
