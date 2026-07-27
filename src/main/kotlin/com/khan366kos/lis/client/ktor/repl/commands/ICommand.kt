package com.khan366kos.lis.client.ktor.repl.commands

import com.khan366kos.lis.client.ktor.repl.Result

interface ICommand {
    val name: String
    suspend fun execute(args: String): Result
}