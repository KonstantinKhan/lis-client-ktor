package com.khan366kos.lis.client.ktor.domain

data class Identifier(
    val loodsmanId: Int,
    val classifierId: Long,
    // Обозначение объекта (mappingElement.source на его строке "Объекты") — пусто для объектов,
    // резолвленных через ПОЛИНОМ (resolveViaPolynom), у них обозначение из Excel не читается.
    // Нужно, когда объект дальше выступает "родителем" для фич, читающих отдельный от "Объекты"
    // лист Excel и не имеющих доступа к исходной строке (см. CastingBlanksEngine.kt).
    val designation: String = "",
    val childLinkType: String? = null,
    val childOfSameTypeLinkType: String? = null
)
