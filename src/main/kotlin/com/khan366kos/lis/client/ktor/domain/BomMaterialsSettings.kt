package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

// Обе Conditions — та же Conditions/ConditionsEvaluator, что и mapping.types[].conditions;
// ConditionsEvaluator.matches работает по RowView независимо от листа-источника. Пустая
// Conditions() (по умолчанию) ничего не ограничивает — см. ConditionsEvaluator.matches.
//
// specificationConditions — проверяется на строке листа "Объекты" (processObjectRow), например
// {"single": {"type":"check","column":"конструкторская спецификация","is":"DS"}} — признак "у
// объекта есть собственная конструкторская спецификация" (DS). Пустая Conditions() выключает
// фичу целиком: никакие связи "Состоит из ..." на несуществующий child не пытаются резолвиться
// как "Материал по КД" (см. BomMaterialsEngine).
//
// materialConditions — ТОЖЕ проверяется на строке листа "Объекты" (не "Связи" — лист "Связи" не
// содержит раздела спецификации вообще, только коды и количества), например
// {"single": {"type":"check","column":"Раздел спецификации","is":"Материалы"}}. Строка "Материалы"
// обычно не матчит ни одно правило mapping.types[] (нет типа для этого раздела) и поэтому не
// становится объектом — но её classifierId всё равно собирается (processObjectRow), чтобы
// runLinksMigration() мог опознать её по childClassifierId листа "Связи" и создать "Материал по
// КД" вместо тихой потери связи (см. BomMaterialsEngine).
@Serializable
data class BomMaterialsSettings(
    val specificationConditions: Conditions = Conditions(),
    val materialConditions: Conditions = Conditions(),
) {
    companion object {
        val None = BomMaterialsSettings()
    }
}
