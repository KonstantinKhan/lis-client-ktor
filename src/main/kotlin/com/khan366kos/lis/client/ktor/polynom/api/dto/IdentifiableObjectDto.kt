package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

// Универсальный идентификатор объекта ПОЛИНОМ:MDM (справочник/каталог/группа/элемент/
// определение свойства). Используется и как тело запроса (IIdentifierRequest), и как форма
// ответа (IIdentifiableObject) — в API Полином они структурно идентичны.
@Serializable
data class IdentifiableObjectDto(
    val objectId: Int,
    val typeId: Int,
)
