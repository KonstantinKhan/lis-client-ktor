package com.khan366kos.lis.client.ktor

import com.khan366kos.lis.client.ktor.client.Client
import com.khan366kos.lis.client.ktor.domain.Identifier
import com.khan366kos.lis.client.ktor.domain.LoodsmanType
import com.khan366kos.lis.client.ktor.domain.Settings
import com.khan366kos.lis.client.ktor.loodsman.api.dto.GetTypeAttrsOutputDto
import com.khan366kos.lis.client.ktor.ConnectConfiguration
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream

fun main(): Unit = runBlocking {

    val typeAttrs = mutableMapOf<LoodsmanType, List<GetTypeAttrsOutputDto>>()
    val elements = mutableSetOf<Identifier>()
    val classifierLinks = mutableMapOf<Long, MutableSet<Long>>()

    val json = Json {
        prettyPrint = true
    }

    val settingsFile = File("C:\\Users\\han\\Desktop\\settings.json")
    val settings: Settings = json.decodeFromString(settingsFile.readText())

    val connectConfiguration = ConnectConfiguration(
        dbName = "loodsman",
        userName = "Администратор",
        password = "Администратор",
        rememberMe = false,
        url = "http://localhost:8076/api/v4/"
    )
    val client = Client(
        settings.connection,
    )

    var sessionId = ""
    var checkout = ""
    var types = mutableListOf<LoodsmanType>()
    var rootId: Int = -1

    launch(Dispatchers.IO) {
        val loginStatus = client.login.login("Администратор", "Администратор".toCharArray())
        sessionId = loginStatus.sessionId ?: throw RuntimeException("Session id can't be null!")

        val response = client.confMetaData.attributes(sessionId)
//        types = response.map { it.toDomain() }.toMutableList()

//        rootId = client.editObject.create(
//            sessionId,
//            NewObjectInputDto(
//                typeName = "Папка",
//                stateName = "Папка для чтения",
//                keyAttribute = "Миграция",
//                isProject = true
//            )
//        ).value

//        val checkOutResponse = client.checkout(
//            sessionId
//        ) {
//            url {
//                parameters.append("typeName", "Папка")
//                parameters.append("productName", "Миграция")
//                parameters.append("mode", "0")
//            }
//        }

//        checkout = checkOutResponse.bodyAsText().replace("\"", "")

//        client.connectToCheckout(sessionId) {
//            url {
//                parameters.append("checkOutName", checkout)
//                parameters.append("dbName", connectConfiguration.dbName)
//            }
//        }
//    }

        val excelPath = "C:\\Users\\han\\Desktop\\data.xlsx"

        val workbook: XSSFWorkbook = FileInputStream(excelPath)
            .use { inputStream ->
                XSSFWorkbook(inputStream)
            }

//    workbook.use {
//        val objectsSheet: XSSFSheet = it.getSheet(settings.mapping.objectsSheet.name)
//        val objectsSheetHeadersRow = objectsSheet.getRow(settings.mapping.objectsSheet.headersRow)
//
//        val linksSheet: XSSFSheet = it.getSheet(settings.mapping.linksSheet.name)
//        val linksSheetHeadersRow = objectsSheet.getRow(settings.mapping.objectsSheet.headersRow)
//
//        val objectsSheetHeadersMap = (0 until objectsSheetHeadersRow.lastCellNum)
//            .mapNotNull { columnIndex ->
//                val cell = objectsSheetHeadersRow.getCell(columnIndex)
//                val headerName = cell?.stringCellValue?.trim()
//                if (!headerName.isNullOrEmpty()) {
//                    headerName to columnIndex
//                } else null
//            }.toMap()
//
//        val linksSheetHeadersMap = (0 until linksSheetHeadersRow.lastCellNum)
//            .mapNotNull { columnIndex ->
//                val cell = linksSheetHeadersRow.getCell(columnIndex)
//                val headerName = cell?.stringCellValue?.trim()
//                if (!headerName.isNullOrEmpty()) {
//                    headerName to columnIndex
//                } else null
//            }.toMap()
//
//        val jobs = mutableListOf<Job>()
//
//        loop@ for (i in settings.mapping.objectsSheet.headersRow until objectsSheet.physicalNumberOfRows) {
//            val row = objectsSheet.getRow(i)
//            settings.mapping.types.forEach { type ->
//                with(type.conditions) {
//                    if (single != null) {
//                        val columnIndex = objectsSheetHeadersMap[single.column]
//                        if (columnIndex != null) {
//                            val cell = row.getCell(columnIndex)
//                            if (cell != null)
//                                when (cell.cellType) {
//                                    CellType.STRING -> {
//                                        if (cell.stringCellValue == single.isValue) {
//                                            val keyAttr = row.getCell(
//                                                objectsSheetHeadersMap[type.source] ?: continue@loop
//                                            ).stringCellValue.trim()
//                                            when (type.target) {
//                                                "Папка" -> jobs.add(launch(Dispatchers.IO) {
//                                                    client.editObject.create(
//                                                        sessionId,
//                                                        NewObjectInputDto(
//                                                            typeName = type.target,
//                                                            stateName = "Папка для чтения",
//                                                            keyAttribute = keyAttr,
//                                                            isProject = true
//                                                        )
//                                                    )
//                                                })
//                                            }
//                                        }
//                                    }
//
//                                    else -> {}
//                                }
//                        }
//
//                    }
//                    if (or.isNotEmpty()) {
//                        or.forEach { rule ->
//                            val columnIndex = objectsSheetHeadersMap[rule.column]
//                            if (columnIndex != null) {
//                                val cell = row.getCell(columnIndex)
//                                if (cell != null)
//                                    when (cell.cellType) {
//                                        CellType.STRING -> {
//                                            if (cell.stringCellValue == rule.isValue) {
//                                                val keyAttr = row.getCell(
//                                                    objectsSheetHeadersMap[type.source] ?: continue@loop
//                                                ).stringCellValue.trim()
//                                                when (type.target) {
//                                                    "Сборочная единица" -> {
//                                                        val loodsmanType =
//                                                            LoodsmanType(
//                                                                types.first { element -> element.name == type.target }.id,
//                                                                type.target
//                                                            )
//                                                        typeAttrs.getOrPut(loodsmanType) {
//                                                            client.confMetaData.typeAttributes(
//                                                                sessionId,
//                                                                loodsmanType.id
//                                                            )
//                                                        }
//
//                                                        val id =
//                                                            row.getCell(
//                                                                objectsSheetHeadersMap[settings.mapping.identifierColumn]
//                                                                    ?: continue@loop
//                                                            ).stringCellValue.trim().toLong()
//
//                                                        jobs.add(launch(Dispatchers.IO) {
//                                                            val response = client.create(
//                                                                sessionId,
//                                                                NewObjectInputDto(
//                                                                    typeName = type.target,
//                                                                    stateName = "Проектирование",
//                                                                    keyAttribute = keyAttr,
//                                                                    isProject = false
//                                                                )
//                                                            )
//                                                            elements.add(Identifier(response.asInt(), id))
//
//                                                            val values = settings.mapping.attributes
//                                                                .mapNotNull { attr ->
//                                                                    val idx = objectsSheetHeadersMap[attr.attrColumn]
//                                                                    val value = idx?.let {
//                                                                        row?.getCell(it)?.getAnyValueAsString()
//                                                                    }
//
//                                                                    if (!value.isNullOrEmpty() && value != "N/A") {
//                                                                        attr.loodsmanAttr to value
//                                                                    } else null
//                                                                }.toMap()
//
//                                                            client.editObject.setValues(
//                                                                sessionId,
//                                                                values.map { (key, value) ->
//                                                                    UpAttrValuesByIdsInputDto(
//                                                                        versionId = response.asInt(),
//                                                                        attributeName = key,
//                                                                        attributeValue = value,
//                                                                    )
//                                                                }
//                                                            )
//
//                                                            client.editObject.newLink(
//                                                                sessionId,
//                                                                NewLinkInputDto(
//                                                                    parentVersionId = rootId,
//                                                                    childVersionId = response.asInt(),
//                                                                    linkType = "Состоит из ..."
//                                                                )
//                                                            )
//                                                        })
//                                                    }
//                                                }
//                                            }
//                                        }
//
//                                        else -> {}
//                                    }
//                            }
//                        }
//                    }
//
//                    if (and.isNotEmpty()) {
//                        val allConditionsMet = and.all { rule ->
//                            val columnIndex = objectsSheetHeadersMap[rule.column] ?: return@all false
//                            val cell = row.getCell(columnIndex) ?: return@all false
//                            when (cell.cellType) {
//                                CellType.STRING -> {
//                                    when (rule.type) {
//                                        "check" -> cell.stringCellValue == rule.isValue
//                                        "parse" -> rule.isValue?.let { !cell.stringCellValue.endsWith(rule.isValue) }
//                                            ?: false
//
//                                        else -> false
//                                    }
//                                }
//
//                                else -> false
//                            }
//                        }
//
//                        if (allConditionsMet) {
//                            val keyAttr = row.getCell(
//                                objectsSheetHeadersMap[type.source] ?: continue@loop
//                            ).stringCellValue.trim()
//                            when (type.target) {
//                                "Деталь" -> {
//                                    val loodsmanType =
//                                        LoodsmanType(
//                                            types.first { element -> element.name == type.target }.id,
//                                            type.target
//                                        )
//                                    typeAttrs.getOrPut(loodsmanType) {
//                                        client.confMetaData.typeAttributes(
//                                            sessionId,
//                                            loodsmanType.id
//                                        )
//                                    }
//                                    val id =
//                                        row.getCell(
//                                            objectsSheetHeadersMap[settings.mapping.identifierColumn]
//                                                ?: continue@loop
//                                        ).stringCellValue.trim().toLong()
//                                    jobs.add(launch(Dispatchers.IO) {
//                                        val response = client.create(
//                                            sessionId,
//                                            NewObjectInputDto(
//                                                typeName = type.target,
//                                                stateName = "Проектирование",
//                                                keyAttribute = keyAttr,
//                                                isProject = false
//                                            )
//                                        )
//                                        elements.add(Identifier(response.asInt(), id))
//
//                                        val values = settings.mapping.attributes
//                                            .mapNotNull { attr ->
//                                                val idx = objectsSheetHeadersMap[attr.attrColumn]
//                                                val value = when (attr.replace) {
//                                                    is ReplaceRule.ReplaceAny ->
//                                                        idx?.let { id ->
//                                                            if (row?.getCell(id)?.getAnyValueAsString()?.trim()
//                                                                    ?.isNotEmpty() == true && attr.replace.find == "any"
//                                                            ) attr.replace.target
//                                                            else "N/A"
//                                                        }
//
//                                                    else -> idx?.let { id -> row?.getCell(id)?.getAnyValueAsString() }
//                                                }
//                                                if (!value.isNullOrEmpty() && value != "N/A") {
//                                                    attr.loodsmanAttr to value
//                                                } else null
//                                            }.toMap()
//
//                                        client.editObject.setValues(
//                                            sessionId,
//                                            values.map { (key, value) ->
//                                                UpAttrValuesByIdsInputDto(
//                                                    versionId = response.asInt(),
//                                                    attributeName = key,
//                                                    attributeValue = value,
//                                                )
//                                            }
//                                        )
//                                    })
//                                }
//                            }
//                        }
//
//                    }
//                }
//            }
//        }
//
//        for (i in settings.mapping.linksSheet.headersRow + 1 until linksSheet.physicalNumberOfRows) {
//            val row = linksSheet.getRow(i)
//            with(settings.mapping.linksSheet) {
//                val parentId = row.getCell(parentColumn).stringCellValue.trim().toLong()
//                val childId = row.getCell(childColumn).stringCellValue.trim().toLong()
//                classifierLinks.getOrPut(parentId) { linkedSetOf() }.add(childId)
//            }
//        }
//
//        jobs.joinAll()
//
//        val elementsByClassifierId = elements.groupBy { identifier -> identifier.classifierId }
//
//        val links = mutableMapOf<Identifier, MutableSet<Identifier>>().apply {
//            elements.forEach { identifier -> this[identifier] = mutableSetOf() }
//            classifierLinks.forEach { (sourceClassifierId, targetClassifierIds) ->
//                val sourceIdentifiers = elementsByClassifierId[sourceClassifierId] ?: return@forEach
//
//                targetClassifierIds.forEach { targetClassifierId ->
//                    val targetIdentifiers = elementsByClassifierId[targetClassifierId] ?: return@forEach
//
//                    sourceIdentifiers.forEach { sourceId ->
//                        targetIdentifiers.forEach { targetId ->
//                            this[sourceId]?.add(targetId)
//                        }
//                    }
//                }
//            }
//        }
//
//        val job = launch(Dispatchers.IO) {
//            links.forEach { (classifierId, identifiers) ->
//                identifiers.forEach { identifier ->
//                    client.editObject.newLink(
//                        sessionId,
//                        NewLinkInputDto(
//                            parentVersionId = classifierId.loodsmanId,
//                            childVersionId = identifier.loodsmanId,
//                            linkType = "Состоит из ..."
//                        )
//                    )
//                }
//
//            }
//        }
//
//        job.join()
//
//        launch(Dispatchers.IO) {
//            client.checkIn(sessionId) {
//                setBody(CheckInModel(checkOutName = checkout, dbName = connectConfiguration.dbName))
//            }
//        }
//    }


//    ===

//
//        val validator = Validator(settings, types)
//
//        val check: Boolean = validator.isValidTarget()
//
//        println("Для всех строк из маппинга есть соответствующие типы: $check")
//
//        val requestProject = client.create(
//            sessionId,
//            LoodsmanObjectModel(
//                typeName = "Папка", stateName = "Папка для чтения", keyAttribute = projectName, isProject = true
//            )
//        )
//
//        val checkOutResponse = client.checkout(
//            sessionId
//        ) {
//            url {
//                parameters.append("typeName", "Папка")
//                parameters.append("productName", projectName)
//                parameters.append("mode", "0")
//            }
//        }
//
//        val checkout = checkOutResponse.bodyAsText().replace("\"", "")
//
//        val connectToCheckout = client.connectToCheckout(sessionId) {
//            url {
//                parameters.append("checkOutName", checkout)
//                parameters.append("dbName", connectConfiguration.dbName)
//            }
//        }
//
//        println("connectToCheckout: ${connectToCheckout.bodyAsText()}")
//
//        for (i in 0..2) {
//            client.create(
//                sessionId,
//                LoodsmanObjectModel(
//                    typeName = "Сборочная единица",
//                    stateName = "Проектирование",
//                    keyAttribute = "$assemblyName $i",
//                    isProject = false
//                )
//            )
//        }
//
//        for (i in 0..2) {
//            val res = client.insertObject(sessionId) {
//                setBody(
//                    InsertObjectModel(
//                        parentType = "Папка",
//                        parentProduct = projectName,
//                        typeName = "Сборочная единица",
//                        productName = "$assemblyName $i",
//                        versionNumber = "1.0",
//                        link = "Состоит из ...",
//                        keyInsert = true
//                    )
//                )
//            }
//            val typeId = res.bodyAsText().toInt()
//            println("assembly: $typeId")
//        }
//
//        val attrs = client.confMetaData.typeAttributes(sessionId) {
//            setBody(
//                GetTypeAttrsInputDto(listOf(59)),
//            )
//        }
//        attrs.body<List<TypeAttrsGroupDto>>().forEach { attrsGroup ->
//            println("attrsGroup: ${attrsGroup.typeId}")
//            attrsGroup.attrs?.forEach { attr ->
//                println("attr: ${attr.name}")
//            }
//        }
//
//
//        client.checkIn(sessionId) {
//            setBody(CheckInModel(checkOutName = checkout, dbName = connectConfiguration.dbName))
//        }
    }
}


fun Cell.getAnyValueAsString(): String? {
    return try {
        this.stringCellValue?.trim()
    } catch (e: Exception) {
        null
    } ?: try {
        when (this.cellType) {
            CellType.NUMERIC -> {
                val num = this.numericCellValue
                if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
            }

            CellType.BOOLEAN -> this.booleanCellValue.toString()
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}