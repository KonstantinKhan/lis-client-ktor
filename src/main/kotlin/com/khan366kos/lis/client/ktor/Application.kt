package com.khan366kos.lis.client.ktor

import com.khan366kos.lis.client.ktor.repl.ReplConsole
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val repl = ReplConsole()
    repl.start()
}