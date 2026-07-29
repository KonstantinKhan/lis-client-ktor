package com.khan366kos.lis.client.ktor.polynom.api.dto

import kotlinx.serialization.Serializable

// Верхнеуровневые objectId/typeId в ответе get-by-concept-appointer принадлежат самой записи
// назначения (appointment), а не понятию — реальная ссылка на понятие лежит во вложенном поле
// "concept" (name+objectId+typeId).
@Serializable
data class AppointedConceptDto(
    val concept: ConceptDto,
)
