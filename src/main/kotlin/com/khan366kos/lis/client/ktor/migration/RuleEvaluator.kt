package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Rule

fun interface RuleEvaluator {
    fun evaluate(rule: Rule, value: String?): Boolean
}

object RuleEvaluators {
    private val registry = mutableMapOf<String, RuleEvaluator>(
        "check" to RuleEvaluator { rule, value -> value != null && value == rule.isValue },
        "parse" to RuleEvaluator { rule, value ->
            value != null && rule.isValue != null && !value.endsWith(rule.isValue)
        },
    )

    fun register(type: String, evaluator: RuleEvaluator) {
        registry[type] = evaluator
    }

    fun evaluate(rule: Rule, value: String?): Boolean {
        val type = rule.type ?: return false
        return registry[type]?.evaluate(rule, value) ?: false
    }
}
