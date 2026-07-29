package com.khan366kos.lis.client.ktor.domain

import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import kotlinx.serialization.Serializable

@Serializable
data class MaterialsHierarchy(
    val referenceName: String,
    val catalogName: String,
    val groupName: String,
) {
    companion object {
        val None = MaterialsHierarchy(referenceName = "", catalogName = "", groupName = "")
    }
}

@Serializable
data class MaterialsSettings(
    val appliesToTargets: List<String>,
    val drawingDesignationColumn: String,
    val classifierCodeColumn: String,
    // Полный код (absoluteCode) property-definition — берётся из админки Полином напрямую,
    // резолвится через concept-property-source/get-by-absolute-code (единственный рабочий путь
    // из перепробованных: concept/get-all зависает насмерть, get-by-code требует concept и всё
    // равно даёт 404, get-by-id 404 даже на id из соседнего эндпоинта того же понятия).
    val classifierCodePropertyAbsoluteCode: String,
    // Существующий справочник (создаётся не нами) — его {objectId,typeId} нужен как ownerScope
    // для search/execute-property-search: без scope (null) сервер падает с 500 NullReference.
    val codesReferenceName: String,
    val materialTarget: String,
    val materialState: String,
    val detailLinkType: String,
    // Справочник → каталог → группа для новых элементов "материал без совпадения по коду" —
    // тоже создаются НЕ нами (см. codesReferenceName выше): правила связывания типов между
    // Loodsman и ПОЛИНОМ настраиваются вручную в админке и не подхватывают объекты, созданные
    // программно через API (см. resolveMaterialsGroup в MaterialsEngine.kt — только резолв по
    // имени, явная ошибка если чего-то не существует).
    val hierarchy: MaterialsHierarchy,
    // Прямое указание objectId/typeId property-definition в обход резолва по absoluteCode — на
    // случай, если и этот путь не подойдёт на каком-то экземпляре Полином.
    val classifierCodePropertyId: IdentifiableObjectDto? = null,
) {
    companion object {
        val None = MaterialsSettings(
            appliesToTargets = emptyList(),
            drawingDesignationColumn = "",
            classifierCodeColumn = "",
            classifierCodePropertyAbsoluteCode = "",
            codesReferenceName = "",
            materialTarget = "",
            materialState = "",
            detailLinkType = "",
            hierarchy = MaterialsHierarchy.None,
        )
    }
}
