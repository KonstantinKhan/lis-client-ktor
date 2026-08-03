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
) {
    companion object {
        val None = BlanksSettings()
    }
}
