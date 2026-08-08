package com.khan366kos.lis.client.ktor.domain

// Связь листа "Связи", у которой child не резолвится ни в один созданный объект, а родитель
// отмечен признаком DS (см. BomMaterialsSettings) — childClassifierId трактуется как код
// классификатора "Материала по КД", а не как ссылка на объект листа "Объекты". quantity/
// unitDesignation читаются с ТОЙ ЖЕ строки "Связи" (linksSheet.quantityColumn/unitColumn) — без
// них связь материал-родитель создавалась бы с дефолтом API (1.0, без unit), как обычная связь
// без данных.
data class BomMaterialCandidate(
    val parentLoodsmanId: Int,
    val classifierCode: String,
    val quantity: Double,
    val unitDesignation: String?,
    // Атрибуты объекта "Материал по КД" (mapping.materials.attributes), резолвленные со строки
    // раздела "Материалы" на листе "Объекты" (MigrationContext.bomMaterialRowAttributes, ключ —
    // classifierId этой строки = childClassifierId) — см. MigrationEngine.runLinksMigration.
    val attributeValues: Map<String, String> = emptyMap(),
)
