package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

// Группы замены "аналог" (ObjectConfiguration/new-change-group-2 + new-change-variant-2) — строятся
// на листе "Связи" по столбцу analogGroupColumn (формат "1-2": 1 — номер группы на родителя, 2 —
// номер варианта внутри группы), см. AnalogGroupsEngine. "" в analogGroupColumn — фича выключена.
// groupType жёстко 2, тот же код, что у групп замены материала (см. CHANGE_GROUP_TYPE_ANALOG в
// AnalogGroupsEngine.kt) — не вынесено в настройки по решению пользователя.
@Serializable
data class AnalogGroupsSettings(
    val analogGroupColumn: String = "",
    val productionQuantityColumn: String = "",
    val productionQuantityWithoutMergeColumn: String = "",
    // {group}/{variant} — подстановка номера: у одного родителя может быть несколько групп
    // аналогов, без номера в имени они были бы неразличимы в Loodsman.
    val changeGroupNameTemplate: String = "Группа аналогов {group}",
    val variantNameTemplate: String = "Вариант {variant}",
) {
    companion object {
        val None = AnalogGroupsSettings()
    }
}
