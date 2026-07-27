package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Attribute
import com.khan366kos.lis.client.ktor.domain.ReplaceRule

object ReplaceRuleStrategies {
    fun resolve(rule: ReplaceRule?, rawValue: String?): String? = when (rule) {
        null, ReplaceRule.None -> rawValue
        is ReplaceRule.ReplaceAny -> if (!rawValue.isNullOrBlank()) rule.target else null
    }
}

fun resolveAttributes(attributes: List<Attribute>, row: RowView): Map<String, String> =
    attributes.mapNotNull { attr ->
        val raw = row.value(attr.attrColumn)
        val resolved = ReplaceRuleStrategies.resolve(attr.replace, raw)
        if (!resolved.isNullOrEmpty() && resolved != "N/A") attr.loodsmanAttr to resolved else null
    }.toMap()
