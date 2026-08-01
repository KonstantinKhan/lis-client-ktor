package com.khan366kos.lis.client.ktor.domain

import com.khan366kos.lis.client.ktor.domain.LoodsmanObject
import com.khan366kos.lis.client.ktor.domain.LoodsmanState
import com.khan366kos.lis.client.ktor.domain.LoodsmanType
import com.khan366kos.lis.client.ktor.domain.Settings
import com.khan366kos.lis.client.ktor.client.Client
import com.khan366kos.lis.client.ktor.polynom.api.dto.IdentifiableObjectDto
import com.khan366kos.lis.client.ktor.polynom.client.PolynomClient
import com.khan366kos.lis.client.ktor.repl.ReplStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

data class MigrationContext(
    var replStatus: ReplStatus = ReplStatus.START,
    val apiScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    var status: Status = Status.START,
    var configFileName: String = "settings.json",
    var settings: Settings = Settings.None,
    var json: Json = Json { prettyPrint = true },
    var login: String = "",
    var passwordChars: CharArray? = null,
    var loodsmanClient: Client = Client(connection = settings.connection),
    var sessionId: String = "",
    var checkout: String = "",
    var rootId: Int = -1,
    val loodsmanTypes: MutableList<LoodsmanType> = mutableListOf(),
    val identifiers: MutableList<Identifier> = mutableListOf(),
    var root: LoodsmanObject = LoodsmanObject.NewObject(
        type = LoodsmanType.Folder,
        state = LoodsmanState.ReadFolder,
        name = "Миграция",
        isProject = true
    ),
    var polynomClient: PolynomClient = PolynomClient(connection = settings.polynom),
    var polynomAccessToken: String = "",
    var polynomRefreshToken: String = "",
    var polynomLoggedIn: Boolean = false,
    val materialCandidates: MutableList<MaterialCandidate> = mutableListOf(),
    // Кандидаты материалов-заменителей (mapping.materials.substituteDrawingDesignationColumn) —
    // отдельный от materialCandidates список: если резолвить их в одной группе дедупликации по
    // classifierCode (тот же столбец, что и у основного материала — см. решение пользователя),
    // основной и заменитель одной и той же детали схлопнутся в одну группу и свяжется только
    // один из двух. См. MaterialsEngine.runMaterialsMigrationInternal.
    val materialSubstituteCandidates: MutableList<MaterialCandidate> = mutableListOf(),
    var materialsGroupId: IdentifiableObjectDto? = null,
    // classifierId объектов, прошедших mapping.bomMaterials.specificationConditions (признак DS)
    // — по нему runLinksMigration() решает, для каких нерезолвленных child листа "Связи"
    // пытаться резолвить "Материал по КД" по коду классификатора (см. BomMaterialsEngine).
    val bomMaterialSpecClassifierIds: MutableSet<Long> = mutableSetOf(),
    // classifierId СТРОК листа "Объекты" (не только созданных объектов — материалы обычно не
    // матчат ни одно mapping.types[] правило), прошедших mapping.bomMaterials.materialConditions
    // (например "Раздел спецификации"="Материалы"). Лист "Связи" раздела спецификации не содержит
    // вообще — поэтому childClassifierId сверяется с этим набором, а не с полем на самой связи.
    val bomMaterialRowClassifierIds: MutableSet<Long> = mutableSetOf(),
    val bomMaterialCandidates: MutableList<BomMaterialCandidate> = mutableListOf(),
)
