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
) {
    val dedupKey: String?
        get() = classifierCode?.takeIf { it.isNotBlank() } ?: drawingDesignation?.takeIf { it.isNotBlank() }
}
