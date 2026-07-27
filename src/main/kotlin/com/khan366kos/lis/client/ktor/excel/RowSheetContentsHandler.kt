package com.khan366kos.lis.client.ktor.excel

import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.usermodel.XSSFComment

class RowSheetContentsHandler(
    private val onRow: (rowIndex: Int, cells: List<String?>) -> Unit
) : XSSFSheetXMLHandler.SheetContentsHandler {

    private val currentRowCells = mutableMapOf<Int, String?>()
    private var currentRowNumber = -1

    override fun startRow(rowNum: Int) {
        currentRowNumber = rowNum
        currentRowCells.clear()
    }

    override fun endRow(rowNum: Int) {
        if (rowNum == currentRowNumber) {
            flushRow()
        }
    }

    override fun cell(
        cellReference: String?,
        formattedValue: String?,
        comment: XSSFComment?
    ) {
        val colIndex = CellReference(cellReference).col.toInt()
        currentRowCells[colIndex] = formattedValue
    }

    private fun flushRow() {
        if (currentRowNumber < 0) return

        val maxCol = currentRowCells.keys.maxOrNull() ?: -1
        val result = mutableListOf<String?>()

        for (col in 0..maxCol) {
            result.add(currentRowCells[col])
        }

        onRow(currentRowNumber, result.toList())
    }
}