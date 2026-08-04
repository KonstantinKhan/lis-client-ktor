package com.khan366kos.lis.client.ktor.mapping

import com.khan366kos.lis.client.ktor.domain.AnalogGroupsSettings
import com.khan366kos.lis.client.ktor.domain.Attribute
import com.khan366kos.lis.client.ktor.domain.BlanksSettings
import com.khan366kos.lis.client.ktor.domain.BomMaterialsSettings
import com.khan366kos.lis.client.ktor.domain.CastingBlanksSettings
import com.khan366kos.lis.client.ktor.domain.LinksSheet
import com.khan366kos.lis.client.ktor.domain.MappingElement
import com.khan366kos.lis.client.ktor.domain.MaterialsSettings
import com.khan366kos.lis.client.ktor.domain.ObjectsSheet
import com.khan366kos.lis.client.ktor.domain.Source
import kotlinx.serialization.Serializable

@Serializable
data class Mapping(
    val source: Source,
    val objectsSheet: ObjectsSheet,
    val linksSheet: LinksSheet,
    val identifierColumn: String,
    val attributes: List<Attribute>,
    val types: List<MappingElement>,
    val materials: MaterialsSettings,
    val bomMaterials: BomMaterialsSettings = BomMaterialsSettings.None,
    val analogGroups: AnalogGroupsSettings = AnalogGroupsSettings.None,
    val blanks: BlanksSettings = BlanksSettings.None,
    val castingBlanks: CastingBlanksSettings = CastingBlanksSettings.None
) {
    companion object {
        val None = Mapping(
            source = Source.None,
            objectsSheet = ObjectsSheet.None,
            linksSheet = LinksSheet.None,
            identifierColumn = "",
            attributes = emptyList(),
            types = emptyList(),
            materials = MaterialsSettings.None
        )
    }
}
