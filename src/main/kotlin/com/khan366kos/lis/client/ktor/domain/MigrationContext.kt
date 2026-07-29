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
    var materialsGroupId: IdentifiableObjectDto? = null,
)
