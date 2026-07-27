package com.khan366kos.lis.client.ktor.excel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.SharedStrings
import org.apache.poi.xssf.model.StylesTable
import org.xml.sax.InputSource
import java.io.InputStream

data class ExcelRow(
    val sheetName: String,
    val rowIndex: Int,
    val cells: List<String?>
)

class ExcelSaxParser {

    fun parse(input: InputStream, sheetName: String? = null): Flow<ExcelRow> = channelFlow {
        try {
            OPCPackage.open(input).use { pkg ->
                val reader = XSSFReader(pkg)
                val sharedStrings = reader.sharedStringsTable
                val styles = reader.stylesTable
                val sheetsData = reader.sheetsData as? XSSFReader.SheetIterator
                    ?: throw ExcelParseException("внутренняя ошибка: неожиданный тип итератора листов")
                while (sheetsData.hasNext()) {
                    val sheetStream = sheetsData.next()
                    val currentSheetName = sheetsData.sheetName

                    if (sheetName != null && currentSheetName != sheetName) {
                        sheetStream.close()
                        continue
                    }

                    try {
                        parseSheet(sheetStream, styles, sharedStrings) { rowIndex, cells ->
                            trySendBlocking(ExcelRow(currentSheetName, rowIndex, cells))
                        }
                    } catch (e: ExcelParseException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw ExcelParseException("Ошибка при парсинге листа ${currentSheetName}: ${e.message}", e)
                    }
                }
            }
        } catch (e: ExcelParseException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ExcelParseException("Ошибка при чтении Excel", e)
        }
    }.flowOn(Dispatchers.IO)

    private fun parseSheet(
        sheetStream: InputStream,
        styles: StylesTable?,
        sharedStrings: SharedStrings,
        onRow: (rowIndex: Int, cells: List<String?>) -> Unit
    ) {

        val rowHandler = RowSheetContentsHandler(onRow)

        val handler = XSSFSheetXMLHandler(
            styles,
            null,
            sharedStrings,
            rowHandler,
            DataFormatter(),
            false
        )

        val xmlReader = XMLHelper.newXMLReader()

        xmlReader.contentHandler = handler

        xmlReader.parse(InputSource(sheetStream))
    }
}