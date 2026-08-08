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

// Числовые ячейки Excel читаются через ExcelSaxParser.kt (DataFormatter() без явной локали) —
// на машине с русской локалью значение приходит как "0,3", не "0.3". Loodsman числовые атрибуты
// принимают только точку, поэтому для attr.numeric запятая заменяется на точку. Значение, не
// читающееся как число даже после замены (мусор в ячейке) — пропускается (лог + null), чтобы не
// отправлять в Loodsman заведомо невалидное значение (410128 "Неверное значение для атрибута").
private fun normalizeIfNumeric(attr: Attribute, resolved: String): String? {
    if (!attr.numeric) return resolved
    val normalized = resolved.replace(",", ".")
    if (normalized.toDoubleOrNull() == null) {
        System.err.println(
            "Атрибуты: значение '$resolved' в столбце '${attr.attrColumn}' не читается как число — " +
                "атрибут '${attr.loodsmanAttr}' не проставлен"
        )
        return null
    }
    return normalized
}

fun resolveAttributes(attributes: List<Attribute>, row: RowView): Map<String, String> =
    attributes.mapNotNull { attr ->
        val raw = row.value(attr.attrColumn)
        val resolved = ReplaceRuleStrategies.resolve(attr.replace, raw)
        if (resolved.isNullOrEmpty() || resolved == "N/A") return@mapNotNull null
        val normalized = normalizeIfNumeric(attr, resolved) ?: return@mapNotNull null
        attr.loodsmanAttr to normalized
    }.toMap()

// Вариант resolveAttributes(), возвращающий List, а не Map — намеренно НЕ схлопывает несколько
// attrColumn на один loodsmanAttr (например "Диаметр"/"Наружные диаметр"/"Сечение" -> "Диаметр" у
// "Заготовки", см. BlanksSettings.attributes): на реальных данных заполнен только один столбец из
// такой группы на строку, а не Map с непредсказуемым порядком схлопывания.
fun resolveAttributesWithUnits(attributes: List<Attribute>, row: RowView): List<ResolvedAttribute> =
    attributes.mapNotNull { attr ->
        val raw = row.value(attr.attrColumn)
        val resolved = ReplaceRuleStrategies.resolve(attr.replace, raw)
        if (resolved.isNullOrEmpty() || resolved == "N/A") return@mapNotNull null
        val normalized = normalizeIfNumeric(attr, resolved) ?: return@mapNotNull null
        ResolvedAttribute(attr.loodsmanAttr, normalized, attr.unit)
    }
