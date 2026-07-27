package com.khan366kos.lis.client.ktor.repl.commands

import com.khan366kos.lis.client.ktor.repl.Result

class ExitCommand : ICommand {
    override val name = "exit"
    override suspend fun execute(args: String): Result {
        return Result.Exit("Exiting...")
    }
}