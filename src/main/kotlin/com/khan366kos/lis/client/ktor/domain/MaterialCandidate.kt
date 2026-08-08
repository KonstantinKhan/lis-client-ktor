package com.khan366kos.lis.client.ktor.domain

data class MaterialCandidate(
    val detailLoodsmanId: Int,
    val drawingDesignation: String?,
    val classifierCode: String?,
    // Код классификатора самой детали (identifierColumn листа "Объекты") — НЕ то же самое, что
    // classifierCode выше (тот — код классификатора материала-по-сортаменту из отдельного
    // столбца). Нужен только для диагностики (лог пропуска в MaterialsEngine.kt), чтобы можно
    // было найти конкретную деталь в Excel по её собственному коду классификатора.
    val detailClassifierCode: String? = null,
    // Атрибуты объекта "Материал по КД" (mapping.materials.attributes), резолвленные с той же
    // строки Детали при сборе кандидата — см. MigrationEngine.processObjectRow. Применяются один
    // раз, только когда MaterialsEngine реально создаёт объект (не на каждое попадание в кэш).
    val attributeValues: Map<String, String> = emptyMap(),
) {
    val dedupKey: String?
        get() = classifierCode?.takeIf { it.isNotBlank() } ?: drawingDesignation?.takeIf { it.isNotBlank() }
}
