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
    // Норма расхода — АТРИБУТ связи заготовка -> материал основной (величина "Масса" в схеме
    // Loodsman, не встроенное количество связи minQuantity/maxQuantity!), ставится отдельным
    // вызовом EditObject/up-link-attr-values (EditObject.setLinkAttrValues), см. BlanksEngine.kt.
    // rateColumn/rateUnitColumn — источник на листе "Объекты" (значение/обозначение единицы),
    // rateAttribute — имя атрибута в Loodsman, куда это значение пишется. rateColumn.isBlank() или
    // rateAttribute.isBlank() выключает — норма никогда не читается/не проставляется.
    val rateColumn: String = "",
    val rateUnitColumn: String = "",
    val rateAttribute: String = "",
) {
    companion object {
        val None = BlanksSettings()
    }
}
