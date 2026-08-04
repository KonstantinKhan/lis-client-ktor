package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Литейные заготовки: связи — независимая фича, читающая СВОЙ отдельный лист Excel (не "Объекты"
// и не "Связи"). Родитель (parentColumn, код классификатора "литейной заготовки") резолвится среди
// уже созданных на шаге "Объекты" объектов (MigrationContext.identifiers), тем же приёмом, что
// parent/child листа "Связи" — поэтому класс, как и LinksSheet, сочетает координаты листа и
// бизнес-поля в одном месте.
//
// setTarget.isBlank() выключает фичу целиком — тот же паттерн, что BlanksSettings.target.
@Serializable
data class CastingBlanksSettings(
    override val name: String = "",
    @SerialName("headersRowIndex")
    override val rawHeadersRow: Int = -1,
    @SerialName("parentColumnIndex")
    val rawParentColumnIndex: Int = -1,
    @SerialName("childColumnIndex")
    val rawChildColumnIndex: Int = -1,
    val quantityColumn: String = "",
    val unitColumn: String = "",
    val workshopColumn: String = "",
    val materialTypeColumn: String = "",
    // "Комплект вспомогательных материалов" — создаётся один раз на первое встреченное значение
    // parentColumn.
    val setTarget: String = "",
    val setState: String = "",
    // Реверсивная связь комплект -> объект-родитель (parentColumn), субъект связи — комплект, тот
    // же приём, что BlanksSettings.linkType ("Заготовка для").
    val setLinkType: String = "",
    // "Материал" (materialTypeColumn == "Основной")
    val materialTarget: String = "",
    // "Материал вспомогательный" (materialTypeColumn == "Вспомогательный")
    val auxMaterialTarget: String = "",
    // Прямая связь комплект -> материал, субъект — комплект.
    val materialLinkType: String = "",
    // Норма расхода — АТРИБУТ связи комплект -> материал (EditObject/up-link-attr-values), тот же
    // механизм, что BlanksSettings.rateAttribute.
    val rateAttribute: String = "",
    // Цех-потребитель — АТРИБУТ ОБЪЕКТА материала/материала вспомогательного
    // (EditObject/up-attr-values-by-ids), НЕ атрибут связи.
    val workshopAttribute: String = "",
) : DataSheet {

    companion object {
        val None = CastingBlanksSettings()
    }

    val headersRow: Int = rawHeadersRow - 1
    val parentColumn = rawParentColumnIndex - 1
    val childColumn = rawChildColumnIndex - 1
}
