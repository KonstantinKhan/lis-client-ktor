package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Вспомогательные материалы для Детали — независимая фича, читающая СВОЙ отдельный лист Excel
// (не "Объекты" и не "Связи"), архитектурно аналогичная mapping.castingBlanks
// (CastingBlanksSettings/CastingBlanksEngine.kt), но без ветвления по "Тип материала" — на каждую
// строку всегда создаётся один тип объекта, "Материал вспомогательный".
//
// parentColumn/childColumn резолвятся ПО ИМЕНИ (в отличие от castingBlanks.parentColumn/
// childColumn — там столбцы заданы буквами A/B, здесь пользователь дал только названия колонок).
// Родитель резолвится среди уже созданных на шаге "Объекты" объектов (MigrationContext.identifiers)
// тем же приёмом, что в castingBlanks — тип объекта-родителя код нигде не проверяет.
//
// setTarget.isBlank() выключает фичу целиком — тот же паттерн, что BlanksSettings.target.
@Serializable
data class AuxMaterialsSettings(
    override val name: String = "",
    @SerialName("headersRowIndex")
    override val rawHeadersRow: Int = -1,
    val parentColumn: String = "",
    val childColumn: String = "",
    val quantityColumn: String = "",
    val unitColumn: String = "",
    val workshopColumn: String = "",
    // "Комплект вспомогательных материалов" — создаётся один раз на первое встреченное значение
    // parentColumn.
    val setTarget: String = "",
    val setState: String = "",
    // Реверсивная связь комплект -> объект-родитель, субъект связи — комплект, тот же приём, что
    // castingBlanks.setLinkType ("Потребность для").
    val setLinkType: String = "",
    // "Материал вспомогательный" — единственный тип, без ветвления.
    val materialTarget: String = "",
    // Прямая связь комплект -> материал, субъект — комплект.
    val materialLinkType: String = "",
    // Норма расхода — АТРИБУТ связи комплект -> материал (EditObject/up-link-attr-values).
    val rateAttribute: String = "",
    // Цех-потребитель — АТРИБУТ СВЯЗИ комплект->материал (EditObject/up-link-attr-values), тот же
    // механизм, что rateAttribute — НЕ атрибут объекта материала.
    val workshopAttribute: String = "",
) : DataSheet {

    companion object {
        val None = AuxMaterialsSettings()
    }

    val headersRow: Int = rawHeadersRow - 1
}
