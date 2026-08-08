package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class Attribute(
    val attrColumn: String,
    val loodsmanAttr: String,
    val replace: ReplaceRule? = null,
    // Фиксированное обозначение единицы измерения (например "мм") — константа из настроек, не
    // читается из Excel, поэтому резолвится в unitGuid один раз на уникальное значение, а не на
    // кандидата (см. AttributeResolver.kt/BlanksEngine.kt). null — атрибут без единицы.
    val unit: String? = null,
    // true — значение числовое: запятая заменяется на точку (см. AttributeResolver.normalizeIfNumeric).
    // Реальный инцидент: ExcelSaxParser.kt использует DataFormatter() без явной локали, на машине
    // с русской локалью числовые ячейки приходят как "0,3", а не "0.3" — Loodsman отвечает 410128
    // "Неверное значение для атрибута" (или значение остаётся непроставленным). Значение, не
    // читающееся как число даже после замены запятой на точку, пропускается (лог + не проставляется),
    // а не блокирует остальные атрибуты. Для строковых атрибутов (Марка/ГОСТ/Профиль сортамента и
    // т.п.) остаётся false — запятая там может быть частью текста.
    val numeric: Boolean = false,
)
