package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

// Заготовка + материал основной — независимый поток, добавленный к mapping.materials (поток A):
// та же деталь может получить и "Материал по КД" (напрямую), и "Заготовку" с "Материалом
// основным". Код классификатора переиспользуется из mapping.materials (classifierCodeColumn) —
// по решению пользователя не дублировать конфиг под тот же смысл (см. MigrationEngine.kt).
//
// target.isBlank() выключает фичу целиком — тот же паттерн, что
// MaterialsSettings.substituteDrawingDesignationColumn == "".
@Serializable
data class BlanksSettings(
    val target: String = "",
    val state: String = "",
    val linkType: String = "",
    val materialTarget: String = "",
    val materialLinkType: String = "",
    // Список типов Loodsman (mappingElement.target из mapping.types), для которых собираются
    // кандидаты на заготовку. Пусто (дефолт) — фолбэк на materials.appliesToTargets (старое
    // поведение, полностью общий триггер с потоком A). Задан явно — независимый от потока A
    // список: позволяет включить заготовки для типа (например "Технологическая деталь"), не
    // включая заодно поток A для того же типа.
    val appliesToTargets: List<String> = emptyList(),
    // Норма расхода — АТРИБУТ связи заготовка -> материал основной (величина "Масса" в схеме
    // Loodsman, не встроенное количество связи minQuantity/maxQuantity!), ставится отдельным
    // вызовом EditObject/up-link-attr-values (EditObject.setLinkAttrValues), см. BlanksEngine.kt.
    // rateColumn/rateUnitColumn — источник на листе "Объекты" (значение/обозначение единицы),
    // rateAttribute — имя атрибута в Loodsman, куда это значение пишется. rateColumn.isBlank() или
    // rateAttribute.isBlank() выключает — норма никогда не читается/не проставляется.
    val rateColumn: String = "",
    val rateUnitColumn: String = "",
    val rateAttribute: String = "",
    // Атрибуты ОБЪЕКТА "Заготовка" (Диаметр/Длина/Толщина и т.п., лист "Объекты", та же строка,
    // что у детали) — см. AttributeResolver.resolveAttributesWithUnits/BlanksEngine.kt. Несколько
    // разных attrColumn могут указывать на один и тот же loodsmanAttr (разные столбцы под разную
    // форму заготовки) — на практике заполнен только один столбец из группы на строку.
    val attributes: List<Attribute> = emptyList(),
    // Атрибуты СВЯЗИ materialLinkType ("Изготавливается из ..." заготовка -> материал основной),
    // НЕ атрибуты объекта материала — тот же приём, что уже используется для rateAttribute выше,
    // но отдельный от неё вызов EditObject/up-link-attr-values в BlanksEngine.kt.
    val materialAttributes: List<Attribute> = emptyList(),
) {
    companion object {
        val None = BlanksSettings()
    }
}
