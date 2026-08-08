package com.khan366kos.lis.client.ktor.migration

import com.khan366kos.lis.client.ktor.domain.Attribute
import com.khan366kos.lis.client.ktor.domain.ReplaceRule
import com.khan366kos.lis.client.ktor.domain.ResolvedAttribute

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

// Атрибут объекта/связи вместе с его резолвленным значением и (опционально) обозначением единицы
// измерения (Attribute.unit — константа из настроек, не читается из Excel).
data class ResolvedAttribute(val loodsmanAttr: String, val value: String, val unitDesignation: String?)

// Вариант resolveAttributes(), возвращающий List, а не Map — намеренно НЕ схлопывает несколько
// attrColumn на один loodsmanAttr (например "Диаметр"/"Наружные диаметр"/"Сечение" -> "Диаметр" у
// "Заготовки", см. BlanksSettings.attributes): на реальных данных заполнен только один столбец из
// такой группы на строку, а не Map с непредсказуемым порядком схлопывания.
fun resolveAttributesWithUnits(attributes: List<Attribute>, row: RowView): List<ResolvedAttribute> =
    attributes.mapNotNull { attr ->
        val raw = row.value(attr.attrColumn)
        val resolved = ReplaceRuleStrategies.resolve(attr.replace, raw)
        if (!resolved.isNullOrEmpty() && resolved != "N/A") {
            ResolvedAttribute(attr.loodsmanAttr, resolved, attr.unit)
        } else {
            null
        }
    }
