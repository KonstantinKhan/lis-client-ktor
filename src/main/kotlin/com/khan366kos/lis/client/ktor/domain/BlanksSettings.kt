package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

// Заготовка + материал основной — независимый поток, добавленный к mapping.materials (поток A):
// та же деталь может получить и "Материал по КД" (напрямую), и "Заготовку" с "Материалом
// основным". Триггер и код классификатора переиспользуются из mapping.materials
// (appliesToTargets/classifierCodeColumn) — по решению пользователя не дублировать конфиг под тот
// же смысл (см. MigrationEngine.kt).
//
// target.isBlank() выключает фичу целиком — тот же паттерн, что
// MaterialsSettings.substituteDrawingDesignationColumn == "".
@Serializable
data class BlanksSettings(
    val target: String = "",
    val state: String = "",
    val linkType: String = "",
    val materialTarget: String = "",
    val materialLinkType: String = "",
    // Норма расхода на связи заготовка -> материал основной (minQuantity/maxQuantity/unitId,
    // тот же механизм, что "Количество" у потока C, см. MaterialsEngine.linkBomMaterialToParent) —
    // читаются со строки листа "Объекты" (не "Связи"). rateColumn.isBlank() выключает — норма
    // никогда не читается и не проставляется.
    val rateColumn: String = "",
    val rateUnitColumn: String = "",
) {
    companion object {
        val None = BlanksSettings()
    }
}
