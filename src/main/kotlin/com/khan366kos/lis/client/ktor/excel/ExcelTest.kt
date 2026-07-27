package com.khan366kos.lis.client.ktor.excel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

@OptIn(ExperimentalCoroutinesApi::class)
fun main(): Unit = runBlocking {
    val parser = ExcelSaxParser()
    val file =
        Path.of("/Users/khan/Projects/Структура Классификатора.xlsx")

    var rowCount = 0

    val elapsed = measureTime {
        val job = launch(Dispatchers.IO) {
            parser.parse(file.toFile().inputStream())
                .flatMapMerge(concurrency = 20) { row ->
                    flow {
                        delay(50.milliseconds) // Имитация трансформации
                        emit(row)
                    }
                }
                .buffer(128)
                .flatMapMerge(concurrency = 75) { row ->
                    flow {
                        delay(200.milliseconds) // Имитация API вызова
                        emit(row)
                    }
                }
                .collect {
                    rowCount++
                    println(it)
                }
        }

        job.join()
    }

    println("Processing completed.")
    println("Rows: $rowCount, elapsed: $elapsed")
}