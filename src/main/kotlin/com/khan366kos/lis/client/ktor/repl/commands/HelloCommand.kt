package com.khan366kos.lis.client.ktor.repl.commands

import com.khan366kos.lis.client.ktor.repl.Result

class HelloCommand : ICommand {
    override val name = "hello"
    override suspend fun execute(args: String): Result {
        val message = if (args.isEmpty()) "Hello World" else "Hello $args"
        return Result.Show(message)
    }
}