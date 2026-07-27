package com.khan366kos.lis.client.ktor.dsl

import java.io.File

fun fileExistsInWorkingDir(fileName: String): Boolean {
    return File(fileName).exists()
}

fun getFileInWorkingDir(fileName: String): File {
    return File(fileName)
}
